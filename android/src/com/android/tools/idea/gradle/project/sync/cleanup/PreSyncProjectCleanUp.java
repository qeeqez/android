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
package com.android.tools.idea.gradle.project.sync.cleanup;

import com.google.common.annotations.VisibleForTesting;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

public class PreSyncProjectCleanUp {
  @NotNull private final ProjectCleanUpTask[] myCleanUpTasks;

  @NotNull
  public static PreSyncProjectCleanUp getInstance(@NotNull Project project) {
    return ServiceManager.getService(project, PreSyncProjectCleanUp.class);
  }

  public PreSyncProjectCleanUp(@NotNull Project project) {
    this(new ProjectPreferencesCleanUpTask(project), new GradleRunnerCleanupTask(project), new HttpProxySettingsCleanUpTask(project),
         new GradleDistributionCleanUpTask(project));
  }

  @VisibleForTesting
  PreSyncProjectCleanUp(@NotNull ProjectCleanUpTask... cleanUpTasks) {
    myCleanUpTasks = cleanUpTasks;
  }

  public void execute() {
    for (ProjectCleanUpTask task : myCleanUpTasks) {
      task.execute();
    }
  }

  @VisibleForTesting
  @NotNull
  ProjectCleanUpTask[] getCleanUpTasks() {
    return myCleanUpTasks;
  }
}
