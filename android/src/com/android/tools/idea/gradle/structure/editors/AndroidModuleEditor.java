/*
 * Copyright (C) 2013 The Android Open Source Project
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
package com.android.tools.idea.gradle.structure.editors;

import static javax.swing.SwingConstants.TOP;

import com.android.ide.common.repository.GradleCoordinate;
import com.android.tools.analytics.UsageTracker;
import com.android.tools.idea.gradle.parser.BuildFileKey;
import com.android.tools.idea.gradle.project.facet.gradle.GradleFacet;
import com.android.tools.idea.gradle.util.GradleUtil;
import com.android.tools.idea.stats.UsageTrackerUtils;
import com.google.common.collect.ImmutableList;
import com.google.wireless.android.sdk.stats.AndroidStudioEvent;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleConfigurationEditor;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectBundle;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.ui.navigation.Place;
import java.awt.Component;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import org.jetbrains.android.facet.AndroidFacet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Provides a Project Structure editor for an individual module. Can load a number of sub-editors in a tabbed pane.
 */
public class AndroidModuleEditor implements Place.Navigator, Disposable {
  public static final ImmutableList<BuildFileKey> BUILD_FILE_GENERIC_PROPERTIES =
    ImmutableList.of(BuildFileKey.COMPILE_SDK_VERSION, BuildFileKey.BUILD_TOOLS_VERSION,
                     BuildFileKey.LIBRARY_REPOSITORY, BuildFileKey.IGNORE_ASSETS_PATTERN,
                     BuildFileKey.INCREMENTAL_DEX, BuildFileKey.SOURCE_COMPATIBILITY,
                     BuildFileKey.TARGET_COMPATIBILITY);

  private static final String SIGNING_TAB_TITLE = "Signing";
  private static final String BUILD_TYPES_TAB_TITLE = "Build Types";
  private static final String FLAVORS_TAB_TITLE = "Flavors";

  private final Project myProject;
  private final String myName;
  private final List<ModuleConfigurationEditor> myEditors = new ArrayList<>();
  private JBTabbedPane myTabbedPane;
  private JComponent myGenericSettingsPanel;

  public AndroidModuleEditor(@NotNull Project project, @NotNull String moduleName) {
    myProject = project;
    myName = moduleName;
  }

  @NotNull
  public JComponent getPanel() {
    Module module = GradleUtil.findModuleByGradlePath(myProject, myName);
    if (module == null || GradleUtil.getGradleBuildFile(module) == null) {
      return new JPanel();
    }

    final NamedObjectPanel.PanelGroup panelGroup = new NamedObjectPanel.PanelGroup();

    if (myGenericSettingsPanel == null) {
      myEditors.clear();
      AndroidFacet facet = AndroidFacet.getInstance(module);
      if (facet != null && facet.requiresAndroidModel() && GradleFacet.isAppliedTo(module)) {
        myEditors.add(new GenericEditor<>("Properties", () -> {
          SingleObjectPanel panel = new SingleObjectPanel(myProject, myName, null, BUILD_FILE_GENERIC_PROPERTIES);
          panel.init();
          return panel;
        }));
        myEditors.add(new GenericEditor<>(SIGNING_TAB_TITLE, () -> {
          NamedObjectPanel panel = new NamedObjectPanel(myProject, myName, BuildFileKey.SIGNING_CONFIGS, "config", panelGroup);
          panel.init();
          return panel;
        }));
        myEditors.add(new GenericEditor<>(FLAVORS_TAB_TITLE, () -> {
          NamedObjectPanel panel = new NamedObjectPanel(myProject, myName, BuildFileKey.FLAVORS, "flavor", panelGroup);
          panel.init();
          return panel;
        }));
        myEditors.add(new GenericEditor<>(BUILD_TYPES_TAB_TITLE, () -> {
          NamedObjectPanel panel = new NamedObjectPanel(myProject, myName, BuildFileKey.BUILD_TYPES, "buildType", panelGroup);
          panel.init();
          return panel;
        }));
      }

      myEditors.add(new GenericEditor<>(ProjectBundle.message("modules.classpath.title"),
                                        () -> new ModuleDependenciesPanel(myProject, myName)));

      myTabbedPane = new JBTabbedPane(TOP);
      for (ModuleConfigurationEditor editor : myEditors) {
        JComponent component = editor.createComponent();
        if (component != null) {
          myTabbedPane.addTab(editor.getDisplayName(), component);
          editor.reset();
        }
      }
      myTabbedPane.addChangeListener(e -> {
        String tabName = myEditors.get(myTabbedPane.getSelectedIndex()).getDisplayName();

          UsageTracker.log(UsageTrackerUtils.withProjectId(
            AndroidStudioEvent.newBuilder()
               .setCategory(AndroidStudioEvent.EventCategory.PROJECT_STRUCTURE_DIALOG)
               .setKind(AndroidStudioEvent.EventKind.PROJECT_STRUCTURE_DIALOG_TOP_TAB_CLICK)
               , myProject));
      });
      myGenericSettingsPanel = myTabbedPane;
    }
    return myGenericSettingsPanel;
  }

  @Override
  public void dispose() {
    for (final ModuleConfigurationEditor myEditor : myEditors) {
      myEditor.disposeUIResources();
    }
    myEditors.clear();
    myGenericSettingsPanel = null;
  }

  public boolean isModified() {
    for (ModuleConfigurationEditor moduleElementsEditor : myEditors) {
      if (moduleElementsEditor.isModified()) {
        return true;
      }
    }
    return false;
  }

  public void apply() throws ConfigurationException {
    for (ModuleConfigurationEditor editor : myEditors) {
        UsageTracker.log(UsageTrackerUtils.withProjectId(
          AndroidStudioEvent.newBuilder()
           .setCategory(AndroidStudioEvent.EventCategory.PROJECT_STRUCTURE_DIALOG)
           .setKind(AndroidStudioEvent.EventKind.PROJECT_STRUCTURE_DIALOG_TOP_TAB_SAVE),
          myProject));
      editor.saveData();
      editor.apply();
    }
  }

  public String getName() {
    return myName;
  }

  public void selectBuildTypesTab() {
    selectAndGetTabComponent(BUILD_TYPES_TAB_TITLE);
  }

  public void selectFlavorsTab() {
    selectAndGetTabComponent(FLAVORS_TAB_TITLE);
  }

  public void selectDependency(@NotNull GradleCoordinate dependency) {
    Component selected = selectAndGetDependenciesTab();
    if (selected instanceof ModuleDependenciesPanel) {
      ModuleDependenciesPanel dependenciesPanel = (ModuleDependenciesPanel)selected;
      dependenciesPanel.select(dependency);
    }
  }

  public void selectDependenciesTab() {
    selectAndGetDependenciesTab();
  }

  @Nullable
  private Component selectAndGetDependenciesTab() {
    return selectAndGetTabComponent(ProjectBundle.message("modules.classpath.title"));
  }

  public void openSigningConfiguration() {
    selectAndGetTabComponent(SIGNING_TAB_TITLE);
  }

  @Nullable
  private Component selectAndGetTabComponent(@NotNull String tabTitle) {
    int tabCount = myTabbedPane.getTabCount();
    for (int i = 0; i < tabCount; i++) {
      Component component = myTabbedPane.getTabComponentAt(i);
      if (component instanceof JLabel && tabTitle.equals(((JLabel)component).getText())) {
        myTabbedPane.setSelectedIndex(i);
        return myTabbedPane.getSelectedComponent();
      }
    }
    return null;
  }
}
