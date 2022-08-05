/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.android.tools.idea.layoutinspector.metrics

import com.android.testutils.MockitoKt.whenever
import com.android.testutils.VirtualTimeScheduler
import com.android.tools.analytics.LoggedUsage
import com.android.tools.idea.concurrency.waitForCondition
import com.android.tools.idea.layoutinspector.InspectorClientProvider
import com.android.tools.idea.layoutinspector.LEGACY_DEVICE
import com.android.tools.idea.layoutinspector.LayoutInspectorRule
import com.android.tools.idea.layoutinspector.LegacyClientProvider
import com.android.tools.idea.layoutinspector.createProcess
import com.android.tools.idea.layoutinspector.pipeline.CONNECT_TIMEOUT_SECONDS
import com.android.tools.idea.layoutinspector.pipeline.InspectorClientLaunchMonitor
import com.android.tools.idea.layoutinspector.pipeline.legacy.LegacyClient
import com.android.tools.idea.layoutinspector.pipeline.legacy.LegacyTreeLoader
import com.android.tools.idea.stats.AnonymizerUtil
import com.android.tools.idea.util.ListenerCollection
import com.google.wireless.android.sdk.stats.AndroidStudioEvent
import com.google.wireless.android.sdk.stats.DeviceInfo
import com.google.wireless.android.sdk.stats.DynamicLayoutInspectorAttachToProcess.ClientType.LEGACY_CLIENT
import com.google.wireless.android.sdk.stats.DynamicLayoutInspectorErrorInfo.AttachErrorCode
import com.google.wireless.android.sdk.stats.DynamicLayoutInspectorErrorInfo.AttachErrorState
import com.google.wireless.android.sdk.stats.DynamicLayoutInspectorEvent.DynamicLayoutInspectorEventType
import com.intellij.testFramework.DisposableRule
import org.junit.Assert
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.mockito.ArgumentMatchers
import org.mockito.Mockito
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LegacyInspectorMetricsTest {

  private val disposableRule = DisposableRule()
  private val scheduler = VirtualTimeScheduler()
  private val launchMonitor = InspectorClientLaunchMonitor(ListenerCollection.createWithDirectExecutor(), scheduler)
  private val windowIdsRetrievedLock = CountDownLatch(1)

  private val windowIds = mutableListOf<String>()
  private val legacyClientProvider = InspectorClientProvider { params, inspector ->
    val loader = Mockito.mock(LegacyTreeLoader::class.java)
    whenever(loader.getAllWindowIds(ArgumentMatchers.any())).thenAnswer {
      windowIdsRetrievedLock.countDown()
      windowIds
    }
    val client = LegacyClientProvider(disposableRule.disposable, loader).create(params, inspector) as LegacyClient
    client.launchMonitor = launchMonitor
    client
  }

  private val inspectorRule = LayoutInspectorRule(listOf(legacyClientProvider))

  @get:Rule
  val ruleChain = RuleChain.outerRule(inspectorRule).around(disposableRule)!!

  @get:Rule
  val usageTrackerRule = MetricsTrackerRule()

  @Before
  fun setUp() {
    inspectorRule.attachDevice(LEGACY_DEVICE)
  }

  @Test
  fun testAttachSuccessAfterProcessConnected() {
    windowIds.addAll(listOf("window1", "window2", "window3"))
    val process = LEGACY_DEVICE.createProcess()
    inspectorRule.processes.selectedProcess = process
    inspectorRule.processNotifier.fireDisconnected(process)

    val usages = waitFor3Events()
    var studioEvent = usages[0].studioEvent

    val deviceInfo = studioEvent.deviceInfo
    Assert.assertEquals(AnonymizerUtil.anonymizeUtf8(LEGACY_DEVICE.serial), deviceInfo.anonymizedSerialNumber)
    Assert.assertEquals(LEGACY_DEVICE.model, deviceInfo.model)
    Assert.assertEquals(LEGACY_DEVICE.manufacturer, deviceInfo.manufacturer)
    Assert.assertEquals(DeviceInfo.DeviceType.LOCAL_PHYSICAL, deviceInfo.deviceType)

    var inspectorEvent = studioEvent.dynamicLayoutInspectorEvent
    Assert.assertEquals(DynamicLayoutInspectorEventType.COMPATIBILITY_REQUEST, inspectorEvent.type)

    studioEvent = usages[1].studioEvent
    Assert.assertEquals(deviceInfo, studioEvent.deviceInfo)
    Assert.assertEquals(DynamicLayoutInspectorEventType.COMPATIBILITY_SUCCESS, studioEvent.dynamicLayoutInspectorEvent.type)
    Assert.assertEquals(AnonymizerUtil.anonymizeUtf8(inspectorRule.project.basePath!!), studioEvent.projectId)

    studioEvent = usages[2].studioEvent
    inspectorEvent = studioEvent.dynamicLayoutInspectorEvent
    Assert.assertEquals(deviceInfo, studioEvent.deviceInfo)
    Assert.assertEquals(AnonymizerUtil.anonymizeUtf8(inspectorRule.project.basePath!!), studioEvent.projectId)
    Assert.assertEquals(DynamicLayoutInspectorEventType.SESSION_DATA, studioEvent.dynamicLayoutInspectorEvent.type)
    Assert.assertEquals(LEGACY_CLIENT, inspectorEvent.session.attach.clientType)
    Assert.assertTrue(inspectorEvent.session.attach.success)
  }

  @Test
  fun testAttachFailAfterProcessConnected() {
    Assert.assertTrue(windowIds.isEmpty()) // No window IDs will cause attaching to fail
    val connectThread = Thread {
      inspectorRule.processes.selectedProcess = LEGACY_DEVICE.createProcess()
    }
    connectThread.start()
    windowIdsRetrievedLock.await()
    // Launch monitor will kill the connection attempt
    scheduler.advanceBy(CONNECT_TIMEOUT_SECONDS + 1, TimeUnit.SECONDS)
    connectThread.join()
    val usages = waitFor3Events()
    var studioEvent = usages[0].studioEvent

    val deviceInfo = studioEvent.deviceInfo
    Assert.assertEquals(AnonymizerUtil.anonymizeUtf8(LEGACY_DEVICE.serial), deviceInfo.anonymizedSerialNumber)
    Assert.assertEquals(LEGACY_DEVICE.model, deviceInfo.model)
    Assert.assertEquals(LEGACY_DEVICE.manufacturer, deviceInfo.manufacturer)
    Assert.assertEquals(DeviceInfo.DeviceType.LOCAL_PHYSICAL, deviceInfo.deviceType)

    var inspectorEvent = studioEvent.dynamicLayoutInspectorEvent
    Assert.assertEquals(DynamicLayoutInspectorEventType.COMPATIBILITY_REQUEST, inspectorEvent.type)

    studioEvent = usages[1].studioEvent
    inspectorEvent = studioEvent.dynamicLayoutInspectorEvent
    Assert.assertEquals(deviceInfo, studioEvent.deviceInfo)
    Assert.assertEquals(DynamicLayoutInspectorEventType.ATTACH_ERROR, studioEvent.dynamicLayoutInspectorEvent.type)
    Assert.assertEquals(AttachErrorState.ADB_PING, inspectorEvent.errorInfo.attachErrorState)
    Assert.assertEquals(AttachErrorCode.CONNECT_TIMEOUT, inspectorEvent.errorInfo.attachErrorCode)

    studioEvent = usages[2].studioEvent
    inspectorEvent = studioEvent.dynamicLayoutInspectorEvent
    Assert.assertEquals(deviceInfo, studioEvent.deviceInfo)
    Assert.assertEquals(DynamicLayoutInspectorEventType.SESSION_DATA, studioEvent.dynamicLayoutInspectorEvent.type)
    Assert.assertEquals(LEGACY_CLIENT, inspectorEvent.session.attach.clientType)
    Assert.assertFalse(inspectorEvent.session.attach.success)
    Assert.assertEquals(AttachErrorState.ADB_PING, inspectorEvent.session.attach.errorInfo.attachErrorState)
    Assert.assertEquals(AttachErrorCode.CONNECT_TIMEOUT, inspectorEvent.session.attach.errorInfo.attachErrorCode)
  }

  private fun waitFor3Events(): List<LoggedUsage> {
    var usages: List<LoggedUsage> = emptyList()
    waitForCondition(10, TimeUnit.SECONDS) {
      usages = usageTrackerRule.testTracker.usages
        .filter { it.studioEvent.kind == AndroidStudioEvent.EventKind.DYNAMIC_LAYOUT_INSPECTOR_EVENT }
      usages.size >= 3
    }
    return usages
  }
}
