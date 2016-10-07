/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.tools.idea.monitor.ui.visual;

import com.android.annotations.NonNull;
import com.android.tools.adtui.Animatable;
import com.android.tools.adtui.AnimatedTimeRange;
import com.android.tools.adtui.Range;
import com.android.tools.adtui.RangeScrollbar;
import com.android.tools.adtui.model.DefaultDataSeries;
import com.android.tools.adtui.model.RangedSeries;
import com.android.tools.adtui.visualtests.VisualTest;
import com.android.tools.datastore.SeriesDataStore;
import com.android.tools.datastore.profilerclient.ProfilerEventListener;
import com.android.tools.idea.monitor.ui.network.view.NetworkCaptureSegment;
import com.android.tools.idea.monitor.ui.network.view.NetworkDetailedView;
import com.android.tools.idea.monitor.ui.network.view.NetworkRadioSegment;
import com.android.tools.idea.monitor.ui.network.view.NetworkSegment;
import com.intellij.util.EventDispatcher;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

public class NetworkProfilerVisualTest extends VisualTest {
  private static final String NETWORK_PROFILER_NAME = "Network Profiler";

  // Number of fake network capture data
  private static final int CAPTURE_SIZE = 10;

  private SeriesDataStore mDataStore;

  private NetworkSegment mSegment;

  private NetworkRadioSegment mRadioSegment;

  private NetworkCaptureSegment mCaptureSegment;

  private List<DefaultDataSeries<NetworkCaptureSegment.NetworkState>> mCaptureData;

  private Thread mSimulateTestDataThread;

  private NetworkDetailedView mDetailedView;

  @Override
  protected void initialize() {
    mDataStore = new VisualTestSeriesDataStore();
    super.initialize();
  }

  @Override
  protected void reset() {
    if (mDataStore != null) {
      mDataStore.reset();
    }
    if (mSimulateTestDataThread != null) {
      mSimulateTestDataThread.interrupt();
    }
    super.reset();
  }

  @Override
  public String getName() {
    return NETWORK_PROFILER_NAME;
  }

  @Override
  protected List<Animatable> createComponentsList() {
    long startTimeUs = mDataStore.getLatestTimeUs();
    Range timeCurrentRangeUs = new Range(startTimeUs - RangeScrollbar.DEFAULT_VIEW_LENGTH_US, startTimeUs);
    AnimatedTimeRange animatedTimeRange = new AnimatedTimeRange(timeCurrentRangeUs, 0);

    EventDispatcher<ProfilerEventListener> dummyDispatcher = EventDispatcher.create(ProfilerEventListener.class);
    mSegment = new NetworkSegment(timeCurrentRangeUs, mDataStore, dummyDispatcher);
    mDetailedView = new NetworkDetailedView();

    ArrayList<RangedSeries<NetworkCaptureSegment.NetworkState>> rangedSeries = new ArrayList();
    mCaptureData = new ArrayList<>();
    for (int i = 0; i < CAPTURE_SIZE; ++i) {
      DefaultDataSeries seriesData = new DefaultDataSeries<NetworkCaptureSegment.NetworkState>();
      mCaptureData.add(seriesData);
      rangedSeries.add(new RangedSeries<>(timeCurrentRangeUs, seriesData));
    }
    mDetailedView.setVisible(false);

    mCaptureSegment = new NetworkCaptureSegment(timeCurrentRangeUs, mDataStore, connectionId -> {
      // TODO: Fix test
      //mDetailedView.showConnectionDetails(connectionId);
      mDetailedView.setVisible(true);
    }, dummyDispatcher);

    mRadioSegment = new NetworkRadioSegment(timeCurrentRangeUs, mDataStore, dummyDispatcher);

    List<Animatable> animatables = new ArrayList<>();
    animatables.add(animatedTimeRange);
    mSegment.createComponentsList(animatables);
    mCaptureSegment.createComponentsList(animatables);
    mRadioSegment.createComponentsList(animatables);

    return animatables;
  }

  @Override
  protected void populateUi(@NonNull JPanel panel) {
    panel.setLayout(new GridBagLayout());
    GridBagConstraints constraints = new GridBagConstraints();
    constraints.fill = GridBagConstraints.BOTH;


    constraints.gridx = 0;
    constraints.gridy = 0;
    constraints.weighty = 0;
    constraints.weightx = 1;
    mRadioSegment.initializeComponents();
    mRadioSegment.setPreferredSize(new Dimension(0, 40));
    panel.add(mRadioSegment, constraints);

    constraints.weighty = .5;
    constraints.gridx = 0;
    constraints.gridy = 1;
    mSegment.initializeComponents();
    mSegment.toggleView(true);
    panel.add(mSegment, constraints);


    constraints.gridx = 0;
    constraints.gridy = 2;
    constraints.weighty = 1;
    mCaptureSegment.initializeComponents();
    panel.add(mCaptureSegment, constraints);

    constraints.gridx = 1;
    constraints.gridy = 0;
    constraints.gridheight = 3;
    constraints.weightx = 0;
    constraints.weighty = 0;
    panel.add(mDetailedView, constraints);

    simulateTestData();
  }

  private void simulateTestData() {
    mSimulateTestDataThread = new Thread() {
      @Override
      public void run() {
        try {
          Random rnd = new Random();
          while (true) {
            //  Insert new data point at now.
            long nowUs = TimeUnit.NANOSECONDS.toMicros(System.nanoTime());
            for (DefaultDataSeries<NetworkCaptureSegment.NetworkState> series : mCaptureData) {
              NetworkCaptureSegment.NetworkState[] states = NetworkCaptureSegment.NetworkState.values();
              // Hard coded value 10 to make the 'NONE' state more frequent
              int index = rnd.nextInt(10);
              series.add(nowUs, (index < states.length) ? states[index] : NetworkCaptureSegment.NetworkState.NONE);
            }
            Thread.sleep(1000);
          }
        }
        catch (InterruptedException e) {
        }
      }
    };
    mSimulateTestDataThread.start();
  }
}
