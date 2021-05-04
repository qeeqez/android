/*
 * Copyright (C) 2021 The Android Open Source Project
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
package com.android.tools.idea.devicemanager.physicaltab;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.android.tools.idea.adb.wireless.PairDevicesUsingWiFiService;
import com.android.tools.idea.adb.wireless.WiFiPairingController;
import com.android.tools.idea.devicemanager.physicaltab.PhysicalDevice.ConnectionType;
import com.android.tools.idea.devicemanager.physicaltab.PhysicalDevicePanel.SetTableModel;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import icons.StudioIcons;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import javax.swing.AbstractButton;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;

@RunWith(JUnit4.class)
public final class PhysicalDevicePanelTest {
  private PhysicalDevicePanel myPanel;
  private PairDevicesUsingWiFiService myService;
  private PhysicalTabPersistentStateComponent myComponent;
  private Disposable myListener;

  private PhysicalDevice myOnlinePixel3;
  private PhysicalDeviceAsyncSupplier mySupplier;

  private CountDownLatch myLatch;

  @Before
  public void mockService() {
    myService = Mockito.mock(PairDevicesUsingWiFiService.class);
  }

  @Before
  public void initComponent() {
    myComponent = new PhysicalTabPersistentStateComponent();
  }

  @Before
  public void mockListener() {
    myListener = Mockito.mock(Disposable.class);
  }

  @Before
  public void mockSupplier() {
    myOnlinePixel3 = new PhysicalDevice.Builder()
      .setSerialNumber("86UX00F4R")
      .setLastOnlineTime(Instant.parse("2021-03-24T22:38:05.890570Z"))
      .setName("Google Pixel 3")
      .setOnline(true)
      .setTarget("Android 12 Preview")
      .setApi("S")
      .setConnectionType(ConnectionType.USB)
      .build();

    mySupplier = Mockito.mock(PhysicalDeviceAsyncSupplier.class);
    Mockito.when(mySupplier.get()).thenReturn(Futures.immediateFuture(Collections.singletonList(myOnlinePixel3)));
  }

  @Before
  public void initLatch() {
    myLatch = new CountDownLatch(1);
  }

  @After
  public void disposeOfPanel() {
    Disposer.dispose(myPanel);
  }

  @Test
  public void newPhysicalDevicePanel() throws InterruptedException {
    // Act
    myPanel = new PhysicalDevicePanel(null,
                                      project -> myService,
                                      () -> myComponent,
                                      model -> myListener,
                                      mySupplier,
                                      this::newSetTableModel);

    // Assert
    CountDownLatchAssert.await(myLatch, Duration.ofMillis(128));

    Object data = Collections.singletonList(Arrays.asList(myOnlinePixel3, "S", StudioIcons.Common.CIRCLE_GREEN, "Actions"));
    assertEquals(data, myPanel.getData());
  }

  @Test
  public void newPhysicalDevicePanelPersistentStateComponentSuppliesDevice() throws InterruptedException {
    // Arrange
    myComponent.set(Collections.singletonList(TestPhysicalDevices.GOOGLE_PIXEL_5));

    // Act
    myPanel = new PhysicalDevicePanel(null,
                                      project -> myService,
                                      () -> myComponent,
                                      model -> myListener,
                                      mySupplier,
                                      this::newSetTableModel);

    // Assert
    CountDownLatchAssert.await(myLatch, Duration.ofMillis(128));

    // @formatter:off
    Object data = Arrays.asList(
      Arrays.asList(myOnlinePixel3,                     "S",  StudioIcons.Common.CIRCLE_GREEN, "Actions"),
      Arrays.asList(TestPhysicalDevices.GOOGLE_PIXEL_5, "30", StudioIcons.Common.CIRCLE_GREEN, "Actions"));
    // @formatter:on

    assertEquals(data, myPanel.getData());
  }

  private @NotNull FutureCallback<@Nullable List<@NotNull PhysicalDevice>> newSetTableModel(@NotNull PhysicalDevicePanel panel) {
    return new CountDownLatchFutureCallback<>(new SetTableModel(panel), myLatch);
  }

  @Test
  public void initPairUsingWiFiButtonFeatureIsntEnabled() {
    // Arrange
    Project project = Mockito.mock(Project.class);

    // Act
    myPanel = new PhysicalDevicePanel(project, p -> myService, () -> myComponent, model -> myListener, mySupplier, SetTableModel::new);

    // Assert
    assertNull(myPanel.getPairUsingWiFiButton());
  }

  @Test
  public void initPairUsingWiFiButton() {
    // Arrange
    Project project = Mockito.mock(Project.class);

    WiFiPairingController controller = Mockito.mock(WiFiPairingController.class);

    Mockito.when(myService.isFeatureEnabled()).thenReturn(true);
    Mockito.when(myService.createPairingDialogController()).thenReturn(controller);

    // Act
    myPanel = new PhysicalDevicePanel(project, p -> myService, () -> myComponent, model -> myListener, mySupplier, SetTableModel::new);

    AbstractButton button = myPanel.getPairUsingWiFiButton();
    assert button != null;

    button.doClick();

    // Assert
    assertEquals("Pair using Wi-Fi", button.getText());
    Mockito.verify(controller).showDialog();
  }
}