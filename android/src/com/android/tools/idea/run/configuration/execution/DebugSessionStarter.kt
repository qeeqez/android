/*
 * Copyright (C) 2022 The Android Open Source Project
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
package com.android.tools.idea.run.configuration.execution

import com.android.annotations.concurrency.WorkerThread
import com.android.ddmlib.Client
import com.android.ddmlib.IDevice
import com.android.tools.idea.projectsystem.getProjectSystem
import com.android.tools.idea.run.debug.attachJavaDebuggerToClient
import com.android.tools.idea.run.debug.waitForClientReadyForDebug
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.ui.ConsoleView
import com.intellij.openapi.progress.ProgressIndicatorProvider
import com.intellij.openapi.progress.ProgressManager
import com.intellij.xdebugger.impl.XDebugSessionImpl
import org.jetbrains.concurrency.Promise

class DebugSessionStarter(private val environment: ExecutionEnvironment) {

  private val project = environment.project
  private val appId = project.getProjectSystem().getApplicationIdProvider(environment.runProfile as RunConfiguration)?.packageName
                      ?: throw RuntimeException("Cannot get ApplicationIdProvider")

  @WorkerThread
  fun attachDebuggerToClient(device: IDevice,
                             destroyRunningProcess: (Client) -> Unit,
                             consoleView: ConsoleView): Promise<XDebugSessionImpl> {

    val indicator = ProgressIndicatorProvider.getGlobalProgressIndicator()
    ProgressManager.checkCanceled()
    indicator?.text = "Waiting for a client"
    val client = waitForClientReadyForDebug(device, listOf(appId))

    ProgressManager.checkCanceled()
    indicator?.text = "Attaching debugger"
    return attachJavaDebuggerToClient(
      project,
      client,
      environment,
      consoleView,
      null,
      destroyRunningProcess
    )
      .onError { destroyRunningProcess(client) }
  }
}