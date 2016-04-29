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
package com.android.tools.idea.updater.configure;


import com.android.annotations.Nullable;
import com.android.repository.api.UpdatablePackage;
import org.jetbrains.annotations.NotNull;

/**
 * State of a row in {@link SdkUpdaterConfigurable}.
 */
class NodeStateHolder {
  enum SelectedState {
    NOT_INSTALLED,
    MIXED,
    INSTALLED
  }

  private final UpdatablePackage myPkg;
  private SelectedState myState;

  public NodeStateHolder(@NotNull UpdatablePackage pkg) {
    myPkg = pkg;
  }

  @NotNull
  public UpdatablePackage getPkg() {
    return myPkg;
  }

  @Nullable  // Should only be null if it hasn't been initialized yet.
  public SelectedState getState() {
    return myState;
  }

  public void setState(@NotNull SelectedState state) {
    myState = state;
  }
}
