/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.tools.idea.testartifacts.instrumented

import com.android.ddmlib.IDevice
import com.android.tools.deployer.DeployerException
import com.android.tools.idea.concurrency.AndroidDispatchers.uiThread
import com.android.tools.idea.execution.common.AndroidExecutionException
import com.android.tools.idea.execution.common.ApplicationTerminator
import com.android.tools.idea.execution.common.processhandler.AndroidProcessHandler
import com.android.tools.idea.model.AndroidModel
import com.android.tools.idea.model.TestOptions
import com.android.tools.idea.projectsystem.getModuleSystem
import com.android.tools.idea.run.ApkProvider
import com.android.tools.idea.run.ApkProvisionException
import com.android.tools.idea.run.ClearLogcatListener
import com.android.tools.idea.run.DeviceFutures
import com.android.tools.idea.run.DeviceHeadsUpListener
import com.android.tools.idea.run.configuration.execution.AndroidConfigurationExecutor
import com.android.tools.idea.run.configuration.execution.createRunContentDescriptor
import com.android.tools.idea.run.configuration.execution.getDevices
import com.android.tools.idea.run.configuration.execution.println
import com.android.tools.idea.run.tasks.AppLaunchTask
import com.android.tools.idea.run.tasks.DeployTask
import com.android.tools.idea.run.tasks.LaunchContext
import com.android.tools.idea.run.tasks.LaunchTask
import com.android.tools.idea.run.tasks.getBaseDebuggerTask
import com.android.tools.idea.run.ui.BaseAction
import com.android.tools.idea.run.util.LaunchUtils
import com.android.tools.idea.stats.RunStats
import com.android.tools.idea.testartifacts.instrumented.AndroidTestApplicationLaunchTask.Companion.allInModuleTest
import com.android.tools.idea.testartifacts.instrumented.AndroidTestApplicationLaunchTask.Companion.allInPackageTest
import com.android.tools.idea.testartifacts.instrumented.AndroidTestApplicationLaunchTask.Companion.classTest
import com.android.tools.idea.testartifacts.instrumented.AndroidTestApplicationLaunchTask.Companion.methodTest
import com.android.tools.idea.testartifacts.instrumented.orchestrator.MAP_EXECUTION_TYPE_TO_MASTER_ANDROID_PROCESS_NAME
import com.android.tools.idea.testartifacts.instrumented.testsuite.api.ANDROID_TEST_RESULT_LISTENER_KEY
import com.android.tools.idea.testartifacts.instrumented.testsuite.view.AndroidTestSuiteView
import com.android.tools.idea.util.androidFacet
import com.google.common.base.Joiner
import com.google.common.collect.ImmutableList
import com.intellij.execution.ExecutionException
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.process.NopProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.runBlockingCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.jetbrains.android.facet.AndroidFacet
import org.jetbrains.annotations.VisibleForTesting
import org.jetbrains.kotlin.utils.keysToMap
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Implementation of [AndroidConfigurationExecutor] for [AndroidTestRunConfiguration] without using Gradle.
 */
class AndroidTestRunConfigurationExecutor @JvmOverloads constructor(
  private val env: ExecutionEnvironment,
  override val deviceFutures: DeviceFutures,
  getApkProvider: (AndroidTestRunConfiguration) -> ApkProvider = { it.apkProvider ?: throw RuntimeException("Cannot get ApkProvider") }
) : AndroidConfigurationExecutor {

  val project = env.project
  override val configuration = env.runProfile as AndroidTestRunConfiguration
  val applicationIdProvider = configuration.applicationIdProvider ?: throw RuntimeException("Cannot get ApplicationIdProvider")
  val apkProvider = getApkProvider(configuration)
  val module = configuration.configurationModule.module!!
  val facet = module.androidFacet ?: throw ExecutionException("Can't find AndroidFacet for module $module")
  var runner = configuration.INSTRUMENTATION_RUNNER_CLASS.takeIf { it.isNotBlank() }
               ?: AndroidTestRunConfiguration.getDefaultInstrumentationRunner(facet)
  private val LOG = Logger.getInstance(this::class.java)

  /**
   * Returns a target Android process ID to be monitored by [AndroidProcessHandler].
   *
   * If this run is instrumentation test without test orchestration, the target Android process ID is simply the application name.
   * Otherwise, we should monitor the test orchestration process because the orchestrator starts and
   * kills the target application process per test case which confuses AndroidProcessHandler (b/150320657).
   */
  private fun getMasterAndroidProcessId(): String {
    return MAP_EXECUTION_TYPE_TO_MASTER_ANDROID_PROCESS_NAME.getOrDefault(
      configuration.getTestExecutionOption(AndroidFacet.getInstance(configuration.configurationModule.module!!)),
      applicationIdProvider.testPackageName!!)
  }

  override fun run(indicator: ProgressIndicator): RunContentDescriptor = runBlockingCancellable(indicator) {
    BaseAction.findRunningProcessHandler(project, configuration, env.executionTarget)?.destroyProcess()

    val devices = getDevices(deviceFutures, indicator, RunStats.from(env))

    waitPreviousProcessTermination(devices, getMasterAndroidProcessId(), indicator)

    val processId = getMasterAndroidProcessId()
    // AndroidProcessHandler should not be closed even if the target application process is killed. During an
    // instrumentation tests, the target application may be killed in between test cases by test runner. Only test
    // runner knows when all test run completes.
    val shouldAutoTerminate = false
    val processHandler = AndroidProcessHandler(project, processId, { it.forceStop(processId) }, shouldAutoTerminate)

    val console = createAndroidTestSuiteView()
    processHandler.putCopyableUserData(ANDROID_TEST_RESULT_LISTENER_KEY, console)
    doRun(devices, processHandler, indicator, console)

    devices.forEach { device -> processHandler.addTargetDevice(device) }

    createRunContentDescriptor(processHandler, console, env)
  }

  private suspend fun doRun(devices: List<IDevice>,
                            processHandler: ProcessHandler,
                            indicator: ProgressIndicator,
                            console: AndroidTestSuiteView) = coroutineScope {
    val testAppId = try {
      applicationIdProvider.testPackageName ?: throw ExecutionException("Unable to determine test package name")
    }
    catch (e: ApkProvisionException) {
      throw ExecutionException("Unable to determine test package name")
    }
    RunStats.from(env).apply { setPackage(testAppId) }
    printLaunchTaskStartedMessage(console)

    // Create launch tasks for each device.
    indicator.text = "Getting task for devices"
    val launchTaskMap = devices.keysToMap { getTask(it, testAppId) }

    // A list of devices that we have launched application successfully.
    indicator.text = "Launching on devices"
    launchTaskMap.entries.map { (device, tasks) ->
      async {
        LOG.info("Launching on device ${device.name}")
        val launchContext = LaunchContext(env, device, console, processHandler, indicator)
        runLaunchTasks(tasks, launchContext)
        // Notify listeners of the deployment.
        project.messageBus.syncPublisher(DeviceHeadsUpListener.TOPIC).launchingTest(device.serialNumber, project)
      }
    }.awaitAll()
  }

  private fun getTask(device: IDevice, testAppId: String): List<LaunchTask> {
    val launchTasks = mutableListOf<LaunchTask>()
    if (configuration.CLEAR_LOGCAT) {
      project.messageBus.syncPublisher(ClearLogcatListener.TOPIC).clearLogcat(device.serialNumber)
    }
    LaunchUtils.initiateDismissKeyguard(device)

    val packages = apkProvider.getApks(device)
    val pmInstallOptions = if (device.version.apiLevel >= 23) {
      "-t -g"
    }
    else {
      "-t"
    }
    launchTasks.add(DeployTask(project, packages, pmInstallOptions, false, false))

    val appLaunchTask: AppLaunchTask = getApplicationLaunchTask(device, testAppId)

    launchTasks.add(appLaunchTask)
    return launchTasks;
  }


  /**
   * Retrieves instrumentation options from the given facet. Extra instrumentation options are not included.
   *
   * @return instrumentation options string. All instrumentation options specified by the facet are concatenated by a single space.
   */
  private fun getInstrumentationOptions(testOptions: TestOptions?): String {
    val builder = ImmutableList.Builder<String>()
    val isAnimationDisabled = testOptions?.animationsDisabled ?: false
    if (isAnimationDisabled) {
      builder.add("--no-window-animation")
    }
    return Joiner.on(" ").join(builder.build())
  }


  @VisibleForTesting
  fun getApplicationLaunchTask(device: IDevice, testAppId: String): AppLaunchTask {
    val androidModel = AndroidModel.get(facet)
    val testOptions = androidModel?.testOptions
    val extraParams = configuration.getExtraInstrumentationOptions(facet)
    val instrumentationOptions = Joiner.on(" ").join(extraParams, getInstrumentationOptions(testOptions))
    val waitForDebugger = env.executor.id == DefaultDebugExecutor.EXECUTOR_ID
    val moduleSystem = facet.getModuleSystem()
    val testLibrariesInUse = moduleSystem.getTestLibrariesInUse()
    val testExecutionOption = testOptions?.executionOption

    return when (configuration.TESTING_TYPE) {
      AndroidTestRunConfiguration.TEST_ALL_IN_MODULE -> allInModuleTest(runner,
                                                                        testAppId,
                                                                        waitForDebugger,
                                                                        instrumentationOptions,
                                                                        testLibrariesInUse,
                                                                        testExecutionOption,
                                                                        device)

      AndroidTestRunConfiguration.TEST_ALL_IN_PACKAGE -> allInPackageTest(runner,
                                                                          testAppId,
                                                                          waitForDebugger,
                                                                          instrumentationOptions,
                                                                          testLibrariesInUse,
                                                                          testExecutionOption,
                                                                          device,
                                                                          configuration.PACKAGE_NAME)

      AndroidTestRunConfiguration.TEST_CLASS -> classTest(runner,
                                                          testAppId,
                                                          waitForDebugger,
                                                          instrumentationOptions,
                                                          testLibrariesInUse,
                                                          testExecutionOption,
                                                          device,
                                                          configuration.CLASS_NAME)

      AndroidTestRunConfiguration.TEST_METHOD -> methodTest(runner,
                                                            testAppId,
                                                            waitForDebugger,
                                                            instrumentationOptions,
                                                            testLibrariesInUse,
                                                            testExecutionOption,
                                                            device,
                                                            configuration.CLASS_NAME,
                                                            configuration.METHOD_NAME)

      else -> throw java.lang.RuntimeException("Unknown testing type is selected")
    }
  }

  private suspend fun waitPreviousProcessTermination(devices: List<IDevice>,
                                                     applicationId: String,
                                                     indicator: ProgressIndicator) = coroutineScope {
    indicator.text = "Terminating the app"
    val results = devices.map { async { ApplicationTerminator(it, applicationId).killApp() } }.awaitAll()
    if (results.any { !it }) {
      throw ExecutionException("Couldn't terminate previous instance of app")
    }
  }

  override fun debug(indicator: ProgressIndicator): RunContentDescriptor = runBlockingCancellable(indicator) {
    BaseAction.findRunningProcessHandler(project, configuration, env.executionTarget)?.destroyProcess()

    val devices = getDevices(deviceFutures, indicator, RunStats.from(env))

    if (devices.size != 1) {
      throw ExecutionException("Cannot launch a debug session on more than 1 device.")
    }
    val testAppId = try {
      applicationIdProvider.testPackageName ?: throw ExecutionException("Unable to determine test package name")
    }
    catch (e: ApkProvisionException) {
      throw ExecutionException(e)
    }
    waitPreviousProcessTermination(devices, getMasterAndroidProcessId(), indicator)


    val processHandler = NopProcessHandler()
    val console = createAndroidTestSuiteView()
    processHandler.putCopyableUserData(ANDROID_TEST_RESULT_LISTENER_KEY, console)
    doRun(devices, processHandler, indicator, console)

    val device = devices.single()
    val debuggerTask = getBaseDebuggerTask(configuration.androidDebuggerContext, facet, env, Int.MAX_VALUE)
    indicator.text = "Connecting debugger"
    val session = debuggerTask.perform(device, testAppId, env, indicator, console)
    session.runContentDescriptor
  }

  private suspend fun createAndroidTestSuiteView() = withContext(uiThread) {
    AndroidTestSuiteView(project, project, configuration.configurationModule.androidTestModule, env.executor.toolWindowId, configuration)
  }

  private fun runLaunchTasks(launchTasks: List<LaunchTask>, launchContext: LaunchContext) {
    val stat = RunStats.from(env)
    for (task in launchTasks) {
      if (task.shouldRun(launchContext)) {
        val details = stat.beginLaunchTask(task)
        try {
          task.run(launchContext)
          stat.endLaunchTask(task, details, true)
        }
        catch (e: Exception) {
          stat.endLaunchTask(task, details, false)
          if (e is DeployerException) {
            throw AndroidExecutionException(e.id, e.message)
          }
          throw e
        }
      }
    }
  }

  private fun printLaunchTaskStartedMessage(consoleView: AndroidTestSuiteView) {
    val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
    consoleView.println("$dateFormat: Launching ${configuration.name} on '${env.executionTarget.displayName}.")
  }

  override fun applyChanges(indicator: ProgressIndicator) = throw UnsupportedOperationException(
    "Apply Changes are not supported for Instrumented tests")

  override fun applyCodeChanges(indicator: ProgressIndicator) = throw UnsupportedOperationException(
    "Apply Code Changes are not supported for Instrumented tests")
}