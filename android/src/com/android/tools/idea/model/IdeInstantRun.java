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
package com.android.tools.idea.model;

import com.android.builder.model.InstantRun;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.Serializable;
import java.util.Objects;

/**
 * Creates a deep copy of {@link InstantRun}.
 *
 * @see IdeAndroidProject
 */
final public class IdeInstantRun implements InstantRun, Serializable {
  @NotNull private final File myInfoFile;
  private final boolean mySupportedByArtifact;
  private final int mySupportStatus;

  public IdeInstantRun(@NotNull InstantRun run) {
    myInfoFile = run.getInfoFile();
    mySupportedByArtifact = run.isSupportedByArtifact();
    mySupportStatus = run.getSupportStatus();
  }

  @Override
  @NotNull
  public File getInfoFile() {
    return myInfoFile;
  }

  @Override
  public boolean isSupportedByArtifact() {
    return mySupportedByArtifact;
  }

  @Override
  public int getSupportStatus() {
    return mySupportStatus;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof InstantRun)) return false;
    InstantRun run = (InstantRun)o;
    return isSupportedByArtifact() == run.isSupportedByArtifact() &&
           getSupportStatus() == run.getSupportStatus() &&
           Objects.equals(getInfoFile(), run.getInfoFile());
  }

  @Override
  public int hashCode() {
    return Objects.hash(getInfoFile(), isSupportedByArtifact(), getSupportStatus());
  }
}
