/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.tools.idea.whatsnew.assistant;

import com.android.tools.analytics.UsageTracker;
import com.android.tools.idea.assistant.AssistantBundleCreator;
import com.android.tools.idea.assistant.OpenAssistSidePanelAction;
import com.google.wireless.android.sdk.stats.AndroidStudioEvent;
import com.google.wireless.android.sdk.stats.WhatsNewAssistantEvent;
import com.intellij.ide.BrowserUtil;
import com.intellij.ide.actions.WhatsNewAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ex.ApplicationInfoEx;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.project.ProjectManagerListener;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.openapi.wm.ex.ToolWindowManagerListener;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;

public class WhatsNewAssistantSidePanelAction extends OpenAssistSidePanelAction {
  @NotNull
  private static WhatsNewAction action = new WhatsNewAction();

  @NotNull
  private final Map<Project, WhatsNewToolWindowListener> myProjectToListenerMap;

  public WhatsNewAssistantSidePanelAction() {
    myProjectToListenerMap = new HashMap<>();
  }

  @Override
  public void update(@NotNull AnActionEvent e) {
    // Project being null can happen when Studio first starts and doesn't have window focus
    Presentation presentation = e.getPresentation();
    if (e.getProject() == null) {
      presentation.setEnabled(false);
    }
    else if (!presentation.isEnabled()) {
      presentation.setEnabled(true);
    }

    action.update(e);
  }

  @Override
  public void actionPerformed(@NotNull AnActionEvent event) {
    openWhatsNewSidePanel(Objects.requireNonNull(event.getProject()));
  }

  public void openWhatsNewSidePanel(@NotNull Project project) {
    WhatsNewAssistantBundleCreator bundleCreator = AssistantBundleCreator.EP_NAME.findExtension(WhatsNewAssistantBundleCreator.class);
    if (bundleCreator == null || !bundleCreator.shouldShowWhatsNew()) {
      BrowserUtil.browse(ApplicationInfoEx.getInstanceEx().getWhatsNewUrl());
      return;
    }

    openWindow(WhatsNewAssistantBundleCreator.BUNDLE_ID, project);

    // Only register a new listener if there isn't already one, to avoid multiple OPEN/CLOSE events
    myProjectToListenerMap.computeIfAbsent(project, this::newWhatsNewToolWindowListener);
  }

  @NotNull
  private WhatsNewToolWindowListener newWhatsNewToolWindowListener(@NotNull Project project) {
    WhatsNewToolWindowListener listener = new WhatsNewToolWindowListener(project, myProjectToListenerMap);
    project.getMessageBus().connect(project).subscribe(ToolWindowManagerListener.TOPIC, listener);
    return listener;
  }

  private static class WhatsNewToolWindowListener implements ToolWindowManagerListener {
    @NotNull private Project myProject;
    @NotNull Map<Project, WhatsNewToolWindowListener> myProjectToListenerMap;
    private boolean isOpen;

    private WhatsNewToolWindowListener(@NotNull Project project,
                                       @NotNull Map<Project, WhatsNewToolWindowListener> projectToListenerMap) {
      myProject = project;
      myProjectToListenerMap = projectToListenerMap;
      isOpen = false;

      // Need an additional listener for project close, because the below invokeLater isn't fired in time before closing
      project.getMessageBus().connect(project).subscribe(ProjectManager.TOPIC, new ProjectManagerListener() {
        @Override
        public void projectClosed(@NotNull Project project) {
          if (!project.equals(myProject)) {
            return;
          }
          if (isOpen) {
            fireClosedEvent();
            isOpen = false;
          }
          myProjectToListenerMap.remove(project);
        }
      });
    }

    @Override
    public void toolWindowRegistered(@NotNull String id) {
    }

    @Override
    public void toolWindowUnregistered(@NotNull String id, @NotNull ToolWindow toolWindow) {
      myProjectToListenerMap.remove(myProject);
    }

    /**
     * Fire WNA OPEN/CLOSE metrics and update the actual state after a state change is received.
     * The logic is wrapped in invokeLater because dragging and dropping the StripeButton temporarily
     * hides and then shows the window. Otherwise, the handler would think the window was closed,
     * even though it was only dragged.
     */
    @Override
    public void stateChanged() {
      ApplicationManager.getApplication().invokeLater(() -> {
        if (myProject.isDisposed()) {
          myProjectToListenerMap.remove(myProject);
          return;
        }

        ToolWindow window = ToolWindowManager.getInstance(myProject).getToolWindow(OpenAssistSidePanelAction.TOOL_WINDOW_TITLE);
        if (window == null) {
          return;
        }
        if (isOpen && !window.isVisible()) {
          fireClosedEvent();
          isOpen = false;
        }
        else if (!isOpen && window.isVisible()){
          fireOpenEvent();
          isOpen = true;
        }
      });
    }

    private static void fireOpenEvent() {
      UsageTracker.log(AndroidStudioEvent.newBuilder()
                         .setKind(AndroidStudioEvent.EventKind.WHATS_NEW_ASSISTANT_EVENT)
                         .setWhatsNewAssistantEvent(WhatsNewAssistantEvent.newBuilder()
                                                      .setType(WhatsNewAssistantEvent.WhatsNewAssistantEventType.OPEN)));
    }

    private static void fireClosedEvent() {
      UsageTracker.log(AndroidStudioEvent.newBuilder()
                         .setKind(AndroidStudioEvent.EventKind.WHATS_NEW_ASSISTANT_EVENT)
                         .setWhatsNewAssistantEvent(WhatsNewAssistantEvent.newBuilder()
                                                      .setType(WhatsNewAssistantEvent.WhatsNewAssistantEventType.CLOSED)));
    }
  }
}
