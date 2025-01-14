/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.tools.idea.layoutinspector.ui

import com.android.testutils.waitForCondition
import com.android.tools.idea.appinspection.test.DEFAULT_TEST_INSPECTION_STREAM
import com.android.tools.idea.layoutinspector.LayoutInspectorRule
import com.android.tools.idea.layoutinspector.MODERN_DEVICE
import com.android.tools.idea.layoutinspector.createProcess
import com.android.tools.idea.layoutinspector.pipeline.appinspection.AppInspectionInspectorRule
import com.android.tools.idea.layoutinspector.runningdevices.withEmbeddedLayoutInspector
import com.android.tools.idea.layoutinspector.util.ReportingCountDownLatch
import com.android.tools.idea.layoutinspector.window
import com.android.tools.idea.testing.AndroidProjectRule
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.RunsInEdt
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import java.util.concurrent.TimeUnit

private val MODERN_PROCESS =
  MODERN_DEVICE.createProcess(streamId = DEFAULT_TEST_INSPECTION_STREAM.streamId)

@RunsInEdt
class LayoutInspectorLoadingObserverTest {

  private val projectRule: AndroidProjectRule = AndroidProjectRule.onDisk()
  private val appInspectorRule =
    AppInspectionInspectorRule(projectRule, withDefaultResponse = false)
  private val inspectorRule =
    LayoutInspectorRule(
      clientProviders = listOf(appInspectorRule.createInspectorClientProvider()),
      projectRule = projectRule,
      isPreferredProcess = { it.name == MODERN_PROCESS.name }
    )

  @get:Rule
  val ruleChain: RuleChain =
    RuleChain.outerRule(projectRule)
      .around(appInspectorRule)
      .around(inspectorRule)
      .around(EdtRule())

  @Before
  fun before() {
    inspectorRule.attachDevice(MODERN_DEVICE)
  }

  @Test
  fun testLoadingStateUpdatesAsExpected() = withEmbeddedLayoutInspector {
    val latch = ReportingCountDownLatch(1)
    inspectorRule.launchSynchronously = false
    appInspectorRule.viewInspector.listenWhen({ true }) {
      latch.await(20, TimeUnit.SECONDS)
      // update the model, once the process is connected
      inspectorRule.inspectorModel.update(window("w1", 1L), listOf("w1"), 1)
    }

    val layoutInspectorLoadingObserver =
      LayoutInspectorLoadingObserver(inspectorRule.disposable, inspectorRule.inspector)
    val listenerInvocations = mutableListOf<Boolean>()
    layoutInspectorLoadingObserver.listeners.add(
      object : LayoutInspectorLoadingObserver.Listener {
        override fun onStartLoading() {
          listenerInvocations.add(true)
        }

        override fun onStopLoading() {
          listenerInvocations.add(false)
        }
      }
    )

    assertThat(layoutInspectorLoadingObserver.isLoading).isFalse()

    // Start connecting, loading should show
    inspectorRule.startLaunch(2)
    inspectorRule.processes.selectedProcess = MODERN_PROCESS

    waitForCondition(1, TimeUnit.SECONDS) { layoutInspectorLoadingObserver.isLoading }

    // Release the response from the agent and wait for connection.
    // The loading should stop and the empty text should not be visible, because now we are
    // connected and showing views on screen
    latch.countDown()
    inspectorRule.awaitLaunch()

    waitForCondition(1, TimeUnit.SECONDS) { !layoutInspectorLoadingObserver.isLoading }

    assertThat(listenerInvocations).containsExactly(true, false, false)
  }

  @Test
  fun testDispose() {
    val layoutInspectorLoadingObserver =
      LayoutInspectorLoadingObserver(inspectorRule.disposable, inspectorRule.inspector)
    layoutInspectorLoadingObserver.listeners.add(
      object : LayoutInspectorLoadingObserver.Listener {
        override fun onStartLoading() {}
        override fun onStopLoading() {}
      }
    )

    var modificationListeners1 = 0
    inspectorRule.inspectorModel.modificationListeners.forEach { modificationListeners1 += 1 }
    assertThat(modificationListeners1).isEqualTo(3)

    Disposer.dispose(layoutInspectorLoadingObserver)

    var modificationListeners2 = 0
    inspectorRule.inspectorModel.modificationListeners.forEach { modificationListeners2 += 1 }
    assertThat(modificationListeners2).isEqualTo(2)

    assertThat(layoutInspectorLoadingObserver.listeners).isEmpty()
    assertThat(inspectorRule.inspector.stopInspectorListeners).isEmpty()
  }
}
