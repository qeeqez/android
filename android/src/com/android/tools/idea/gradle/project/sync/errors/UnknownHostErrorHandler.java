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
package com.android.tools.idea.gradle.project.sync.errors;

import com.android.tools.idea.gradle.service.notification.hyperlink.NotificationHyperlink;
import com.android.tools.idea.gradle.service.notification.hyperlink.OpenUrlHyperlink;
import com.android.tools.idea.gradle.service.notification.hyperlink.ToggleOfflineModeHyperlink;
import com.intellij.openapi.externalSystem.service.notification.NotificationData;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

import static com.google.wireless.android.sdk.stats.AndroidStudioEvent.GradleSyncFailure.UNKNOWN_HOST;
import static com.intellij.openapi.util.text.StringUtil.isNotEmpty;

public class UnknownHostErrorHandler extends SyncErrorHandler {

  private static final String GRADLE_PROXY_ACCESS_DOCS_URL =
    "https://docs.gradle.org/current/userguide/userguide_single.html#sec:accessing_the_web_via_a_proxy";

  @Override
  @Nullable
  protected String findErrorMessage(@NotNull Throwable rootCause, @NotNull NotificationData notification, @NotNull Project project) {
    String text = rootCause.getMessage();
    if (isNotEmpty(text) && rootCause instanceof UnknownHostException) {
      updateUsageTracker(UNKNOWN_HOST);
      return String.format("Unknown host '%1$s'. You may need to adjust the proxy settings in Gradle.", text);
    }
    return null;
  }

  @Override
  @NotNull
  protected List<NotificationHyperlink> getQuickFixHyperlinks(@NotNull NotificationData notification,
                                                              @NotNull Project project,
                                                              @NotNull String text) {
    List<NotificationHyperlink> hyperlinks = new ArrayList<>();
    NotificationHyperlink enableOfflineMode = ToggleOfflineModeHyperlink.enableOfflineMode(project);
    if (enableOfflineMode != null) {
      hyperlinks.add(enableOfflineMode);
    }
    hyperlinks.add(new OpenUrlHyperlink(GRADLE_PROXY_ACCESS_DOCS_URL, "Learn about configuring HTTP proxies in Gradle"));
    return hyperlinks;
  }
}