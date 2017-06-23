/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.tools.idea.uibuilder.menu;

import com.android.tools.idea.uibuilder.model.NlAttributesHolder;
import com.android.tools.idea.uibuilder.model.NlComponent;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

import static com.android.SdkConstants.AUTO_URI;

public final class CastButtonHandler extends MenuHandler {
  static boolean handles(@NotNull NlAttributesHolder button) {
    return Objects.equals(button.getAttribute(AUTO_URI, "actionProviderClass"), "android.support.v7.app.MediaRouteActionProvider");
  }

  @NotNull
  @Override
  public String getGradleCoordinateId(@NotNull NlComponent button) {
    return "com.android.support:mediarouter-v7";
  }
}
