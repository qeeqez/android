/*
 * Copyright (C) 2013 The Android Open Source Project
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
package com.android.tools.idea.gradle.task

import com.android.tools.idea.gradle.project.build.invoker.GradleBuildInvoker
import com.android.tools.idea.gradle.project.build.invoker.GradleBuildInvoker.Companion.getInstance
import com.android.tools.idea.gradle.util.GradleProjects
import com.intellij.openapi.externalSystem.model.ExternalSystemException
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.util.Key
import org.jetbrains.plugins.gradle.service.task.GradleTaskManager
import org.jetbrains.plugins.gradle.service.task.GradleTaskManagerExtension
import org.jetbrains.plugins.gradle.settings.DistributionType
import org.jetbrains.plugins.gradle.settings.GradleExecutionSettings
import java.io.File

/**
 * Executes Gradle tasks.
 */
class AndroidGradleTaskManager : GradleTaskManagerExtension {

  @Throws(ExternalSystemException::class)
  override fun executeTasks(
    id: ExternalSystemTaskId,
    taskNames: List<String>,
    projectPath: String,
    settings: GradleExecutionSettings?,
    jvmParametersSetup: String?,
    listener: ExternalSystemTaskNotificationListener
  ): Boolean {
    val gradleBuildInvoker = findGradleInvoker(id, projectPath) ?: return false
    val effectiveSettings = settings ?: GradleExecutionSettings(null, null, DistributionType.BUNDLED, false)
    GradleTaskManager.setupGradleScriptDebugging(effectiveSettings)
    GradleTaskManager.setupDebuggerDispatchPort(effectiveSettings)
    GradleTaskManager.appendInitScriptArgument(taskNames, jvmParametersSetup, effectiveSettings)
    @Suppress("DEPRECATION") val doNotShowBuildOutputOnFailure =
      effectiveSettings.getUserData(ANDROID_GRADLE_TASK_MANAGER_DO_NOT_SHOW_BUILD_OUTPUT_ON_FAILURE) == true
    val request = GradleBuildInvoker.Request(
      mode = null,
      project = gradleBuildInvoker.project,
      rootProjectPath = File(projectPath),
      gradleTasks = taskNames,
      taskId = id,
      jvmArguments = effectiveSettings.jvmArguments,
      commandLineArguments = effectiveSettings.arguments,
      env = effectiveSettings.env,
      isPassParentEnvs = effectiveSettings.isPassParentEnvs,
      listener = listener,
      isWaitForCompletion = true,
      doNotShowBuildOutputOnFailure = doNotShowBuildOutputOnFailure
    )
    gradleBuildInvoker.executeTasks(request)
    return true
  }

  override fun cancelTask(id: ExternalSystemTaskId, listener: ExternalSystemTaskNotificationListener): Boolean {
    return id.findProject()?.let { getInstance(it).stopBuild(id) } ?: false
  }
}

/**
 * You can use this key to put a user data to [GradleExecutionSettings] when you run a
 * Gradle task from Android Studio. The value is passed to the request and set by
 * [GradleBuildInvoker.Request.Builder.setDoNotShowBuildOutputOnFailure].
 */
@Deprecated("Please use GradleBuildInvoker directly")
val ANDROID_GRADLE_TASK_MANAGER_DO_NOT_SHOW_BUILD_OUTPUT_ON_FAILURE =
  Key.create<Boolean>("ANDROID_GRADLE_TASK_MANAGER_DO_NOT_SHOW_BUILD_OUTPUT_ON_FAILURE")

private fun findGradleInvoker(
  id: ExternalSystemTaskId,
  projectPath: String
): GradleBuildInvoker? {

  // TODO(b/232006839): In AndroidStudio we should instrument all Gradle invocations regardless of whether they directly include
  //                    any Android modules or not. Not doing so disables features like the build analyzer, results in builds invoked with
  //                    with different arguments or in different environments
  //                    In IDEA ideally we should behave similarly if there are any Android modules present but it is up to JB to decide.

  // TODO(b/139179869): Replace with the common way to detect Android-Gradle projects.

  val project = id.findProject() ?: return null

  val anyAndroidModule = ModuleManager.getInstance(project).modules.asSequence()
    .any { projectPath == ExternalSystemApiUtil.getExternalRootProjectPath(it) && GradleProjects.isIdeaAndroidModule(it) }

  return if (anyAndroidModule) getInstance(project) else null
}
