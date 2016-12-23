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
package com.android.tools.idea.profilers;

import com.android.tools.idea.run.editor.ProfilerState;
import com.intellij.openapi.components.ApplicationComponent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.project.ProjectManagerAdapter;
import com.intellij.openapi.startup.StartupManager;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowAnchor;
import com.intellij.openapi.wm.ToolWindowManager;
import icons.AndroidIcons;
import org.jetbrains.annotations.NotNull;

public class ProfilerComponent implements ApplicationComponent {
  @NotNull
  @Override
  public String getComponentName() {
    return "Android Profilers";
  }

  @Override
  public void initComponent() {
    if (ProfilerState.EXPERIMENTAL_PROFILING_FLAG_ENABLED) {
      ProjectManager.getInstance().addProjectManagerListener(new ProjectManagerAdapter() {
        @Override
        public void projectOpened(final Project project) {
          StartupManager.getInstance(project).runWhenProjectIsInitialized(
            () -> {
              ToolWindowManager toolWindowManager = ToolWindowManager.getInstance(project);
              ToolWindow toolWindow = toolWindowManager.registerToolWindow(AndroidMonitorToolWindowFactory.ID, false, ToolWindowAnchor.BOTTOM, project, true);
              toolWindow.setIcon(AndroidIcons.AndroidToolWindow);
              new AndroidMonitorToolWindowFactory().createToolWindowContent(project, toolWindow);
              toolWindow.show(null);
            }
          );
        }
      });
    }
  }

  @Override
  public void disposeComponent() {

  }
}
