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
package com.android.tools.idea.run

import com.android.ddmlib.IDevice
import com.android.tools.deployer.DeployerException
import com.android.tools.idea.concurrency.AndroidDispatchers.uiThread
import com.android.tools.idea.execution.common.AndroidExecutionException
import com.android.tools.idea.execution.common.ApplicationTerminator
import com.android.tools.idea.execution.common.processhandler.AndroidProcessHandler
import com.android.tools.idea.flags.StudioFlags
import com.android.tools.idea.run.ShowLogcatListener.Companion.getShowLogcatLinkText
import com.android.tools.idea.run.applychanges.findExistingSessionAndMaybeDetachForColdSwap
import com.android.tools.idea.run.configuration.execution.AndroidConfigurationExecutor
import com.android.tools.idea.run.configuration.execution.ConsoleViewToConsolePrinter
import com.android.tools.idea.run.configuration.execution.createRunContentDescriptor
import com.android.tools.idea.run.configuration.execution.getDevices
import com.android.tools.idea.run.editor.DeployTarget
import com.android.tools.idea.run.tasks.LaunchContext
import com.android.tools.idea.run.tasks.LaunchTask
import com.android.tools.idea.run.tasks.LaunchTasksProvider
import com.android.tools.idea.run.util.SwapInfo
import com.android.tools.idea.stats.RunStats
import com.android.tools.idea.testartifacts.instrumented.AndroidTestRunConfiguration
import com.android.tools.idea.testartifacts.instrumented.orchestrator.MAP_EXECUTION_TYPE_TO_MASTER_ANDROID_PROCESS_NAME
import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.process.NopProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.util.text.StringUtil
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.jetbrains.android.facet.AndroidFacet
import org.jetbrains.android.util.AndroidBuildCommonUtils.isInstrumentationTestConfiguration
import org.jetbrains.android.util.AndroidBuildCommonUtils.isTestConfiguration
import org.jetbrains.kotlin.utils.keysToMap
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LaunchTaskRunner(
  private val consoleProvider: ConsoleProvider,
  private val applicationIdProvider: ApplicationIdProvider,
  private val myEnv: ExecutionEnvironment,
  override val deployTarget: DeployTarget,
  private val myLaunchTasksProvider: LaunchTasksProvider,
) : AndroidConfigurationExecutor {

  val project = myEnv.project
  override val configuration = myEnv.runProfile as RunConfiguration
  private val LOG = Logger.getInstance(this::class.java)

  /**
   * Returns a target Android process ID to be monitored by [AndroidProcessHandler].
   *
   * If this run is a standard Android application or instrumentation test without test orchestration, the target Android process ID
   * is simply the application name. Otherwise we should monitor the test orchestration process because the orchestrator starts and
   * kills the target application process per test case which confuses AndroidProcessHandler (b/150320657).
   */
  @Throws(ExecutionException::class)
  private fun getMasterAndroidProcessId(runProfile: RunProfile): String {
    if (runProfile !is AndroidTestRunConfiguration) {
      return applicationIdProvider.packageName
    }
    return MAP_EXECUTION_TYPE_TO_MASTER_ANDROID_PROCESS_NAME.getOrDefault(
      runProfile.getTestExecutionOption(AndroidFacet.getInstance(runProfile.configurationModule.module!!)),
      applicationIdProvider.packageName)
  }

  override fun run(indicator: ProgressIndicator): RunContentDescriptor = runBlockingCancellable(indicator) {
    findExistingSessionAndMaybeDetachForColdSwap(myEnv)
    val devices = getDevices(project, indicator, deployTarget, RunStats.from(myEnv))

    waitPreviousProcessTermination(devices, applicationIdProvider.packageName, indicator)

    val processHandler = AndroidProcessHandler(
      project,
      getMasterAndroidProcessId(myEnv.runProfile),
      { it.forceStop(getMasterAndroidProcessId(myEnv.runProfile)) },
      shouldAutoTerminate()
    )

    val console = createConsole(processHandler)
    doRun(devices, processHandler, indicator, console)

    devices.forEach { device ->
      processHandler.addTargetDevice(device)
      if (!StudioFlags.RUNDEBUG_LOGCAT_CONSOLE_OUTPUT_ENABLED.get()) {
        console.printHyperlink(getShowLogcatLinkText(device)) { project ->
          project.messageBus.syncPublisher(ShowLogcatListener.TOPIC).showLogcat(device, applicationIdProvider.packageName)
        }
      }
    }

    createRunContentDescriptor(processHandler, console, myEnv)
  }

  private suspend fun doRun(devices: List<IDevice>,
                            processHandler: ProcessHandler,
                            indicator: ProgressIndicator,
                            console: ConsoleView) = coroutineScope {
    val applicationId = applicationIdProvider.packageName
    val stat = RunStats.from(myEnv).apply { setPackage(applicationId) }
    stat.beginLaunchTasks()
    try {
      val consolePrinter = ConsoleViewToConsolePrinter(console)

      printLaunchTaskStartedMessage(consolePrinter)
      myLaunchTasksProvider.fillStats(stat)

      // Create launch tasks for each device.
      indicator.text = "Getting task for devices"
      val launchTaskMap = devices.keysToMap { myLaunchTasksProvider.getTasks(it, consolePrinter) }

      // A list of devices that we have launched application successfully.
      indicator.text = "Launching on devices"
      launchTaskMap.entries.map { (device, tasks) ->
        async {
          LOG.info("Launching on device ${device.name}")
          val launchContext = LaunchContext(myEnv, device, consolePrinter, processHandler, indicator)
          runLaunchTasks(tasks, launchContext)
          // Notify listeners of the deployment.
          if (isLaunchingTest()) {
            project.messageBus.syncPublisher(DeviceHeadsUpListener.TOPIC).launchingTest(device.serialNumber, project)
          }
          else {
            project.messageBus.syncPublisher(DeviceHeadsUpListener.TOPIC).launchingApp(device.serialNumber, project)
          }
        }
      }.awaitAll()
    }
    finally {
      stat.endLaunchTasks()
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

  override fun runAsInstantApp(indicator: ProgressIndicator): RunContentDescriptor {
    return run(indicator)
  }

  override fun debug(indicator: ProgressIndicator): RunContentDescriptor = runBlockingCancellable(indicator) {
    val applicationId = applicationIdProvider.packageName

    val devices = getDevices(project, indicator, deployTarget, RunStats.from(myEnv))

    if (devices.size != 1) {
      throw ExecutionException("Cannot launch a debug session on more than 1 device.")
    }
    waitPreviousProcessTermination(devices, applicationId, indicator)

    findExistingSessionAndMaybeDetachForColdSwap(myEnv)

    val processHandler = NopProcessHandler()
    val console = createConsole(processHandler)
    doRun(devices, processHandler, indicator, console)

    val device = devices.single()
    val debuggerTask = myLaunchTasksProvider.connectDebuggerTask
                       ?: throw RuntimeException(
                         "ConnectDebuggerTask is null for task provider " + myLaunchTasksProvider.javaClass.name)
    indicator.text = "Connecting debugger"
    val session = debuggerTask.perform(device, applicationId, myEnv, indicator, console)
    session.runContentDescriptor
  }

  override fun applyChanges(indicator: ProgressIndicator): RunContentDescriptor = runBlockingCancellable(indicator) {
    val devices = getDevices(project, indicator, deployTarget, RunStats.from(myEnv))
    val oldSession = findExistingSessionAndMaybeDetachForColdSwap(myEnv)
    val processHandler = oldSession.processHandler ?: AndroidProcessHandler(
      project,
      getMasterAndroidProcessId(myEnv.runProfile),
      { it.forceStop(getMasterAndroidProcessId(myEnv.runProfile)) },
      shouldAutoTerminate()
    )

    val console = oldSession.executionConsole as? ConsoleView ?: createConsole(processHandler)

    doRun(devices, processHandler, indicator, console)

    if (oldSession.processHandler == null && processHandler is AndroidProcessHandler) {
      devices.forEach { device ->
        processHandler.addTargetDevice(device)
        if (!StudioFlags.RUNDEBUG_LOGCAT_CONSOLE_OUTPUT_ENABLED.get()) {
          console.printHyperlink(getShowLogcatLinkText(device)) { project ->
            project.messageBus.syncPublisher(ShowLogcatListener.TOPIC).showLogcat(device, applicationIdProvider.packageName)
          }
        }
      }
    }

    withContext(uiThread) {
      val descriptor = RunContentManager.getInstance(project).findContentDescriptor(myEnv.executor, processHandler)

      if (descriptor?.attachedContent == null) {
        createRunContentDescriptor(processHandler, console, myEnv)
      }
      else {
        descriptor.takeIf { it is HiddenRunContentDescriptor } ?: HiddenRunContentDescriptor(descriptor)
      }
    }
  }

  private suspend fun createConsole(processHandler: ProcessHandler): ConsoleView = withContext(uiThread) {
    consoleProvider.createAndAttach(project, processHandler, myEnv.executor)
  }

  override fun applyCodeChanges(indicator: ProgressIndicator): RunContentDescriptor {
    return applyChanges(indicator)
  }

  private fun runLaunchTasks(launchTasks: List<LaunchTask>, launchContext: LaunchContext) {
    val stat = RunStats.from(myEnv)
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

  private fun isLaunchingTest(): Boolean {
    val configTypeId = myEnv.runnerAndConfigurationSettings?.type?.id ?: return false
    return isTestConfiguration(configTypeId) || isInstrumentationTestConfiguration(configTypeId)
  }

  private fun shouldAutoTerminate(): Boolean {
    // AndroidProcessHandler should not be closed even if the target application process is killed. During an
    // instrumentation tests, the target application may be killed in between test cases by test runner. Only test
    // runner knows when all test run completes.
    return myEnv.runProfile !is AndroidTestRunConfiguration
  }

  private fun printLaunchTaskStartedMessage(consolePrinter: ConsolePrinter) {
    val launchString = StringBuilder("\n")
    val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
    launchString.append(dateFormat.format(Date())).append(": ")
    val launchVerb = when (myEnv.getUserData(SwapInfo.SWAP_INFO_KEY)?.type) {
      SwapInfo.SwapType.APPLY_CHANGES -> "Applying changes to"
      SwapInfo.SwapType.APPLY_CODE_CHANGES -> "Applying code changes to"
      else -> "Launching"
    }
    launchString.append(launchVerb).append(" ")
    launchString.append("'").append(configuration.name).append("'")
    if (!StringUtil.isEmpty(myEnv.executionTarget.displayName)) {
      launchString.append(" on ")
      launchString.append(myEnv.executionTarget.displayName)
    }
    launchString.append(".")
    consolePrinter.stdout(launchString.toString())
  }
}