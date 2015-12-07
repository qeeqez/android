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
package com.android.tools.idea.tests.gui.theme;

import com.android.ide.common.rendering.api.ItemResourceValue;
import com.android.tools.idea.editors.theme.ResolutionUtils;
import com.android.tools.idea.editors.theme.datamodels.ConfiguredElement;
import com.android.tools.idea.editors.theme.datamodels.EditedStyleItem;
import com.android.tools.idea.editors.theme.datamodels.ConfiguredThemeEditorStyle;
import com.android.tools.idea.editors.theme.ThemeAttributeResolver;
import com.android.tools.idea.tests.gui.framework.GuiTests;
import com.android.tools.idea.tests.gui.framework.fixture.EditorFixture;
import com.android.tools.idea.tests.gui.framework.fixture.EditorNotificationPanelFixture;
import com.android.tools.idea.tests.gui.framework.fixture.IdeFrameFixture;
import com.android.tools.idea.tests.gui.framework.fixture.theme.ThemeEditorFixture;
import com.google.common.base.Predicate;
import com.google.common.collect.Collections2;
import com.google.common.collect.Sets;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.ui.components.JBList;
import org.fest.swing.cell.JListCellReader;
import org.fest.swing.core.*;
import org.fest.swing.fixture.JListFixture;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.Collection;
import java.util.Set;

/**
 * Utility class for static methods used in UI tests for the Theme Editor
 */
public class ThemeEditorTestUtils {
  private ThemeEditorTestUtils() {}

  @NotNull
  public static ThemeEditorFixture openThemeEditor(@NotNull IdeFrameFixture projectFrame) {
    // Makes sure Android Studio has focus, useful when running with a window manager
    projectFrame.focus();

    EditorFixture editor = projectFrame.getEditor();
    editor.open("app/src/main/res/values/styles.xml", EditorFixture.Tab.EDITOR);
    EditorNotificationPanelFixture notificationPanel =
      projectFrame.requireEditorNotification("Edit all themes in the project in the theme editor.");
    notificationPanel.performAction("Open editor");

    ThemeEditorFixture themeEditor = editor.getThemeEditor();

    themeEditor.getThemePreviewPanel().getPreviewPanel().waitForRender();

    return themeEditor;
  }

  /**
   * Returns the attributes that were defined in the theme itself and not its parents.
   */
  public static Collection<EditedStyleItem> getStyleLocalValues(@NotNull final ConfiguredThemeEditorStyle style) {
    final Set<String> localAttributes = Sets.newHashSet();
    for (ConfiguredElement<ItemResourceValue> value : style.getConfiguredValues()) {
      localAttributes.add(ResolutionUtils.getQualifiedItemName(value.getElement()));
    }

    return Collections2.filter(ThemeAttributeResolver.resolveAll(style, style.getConfiguration().getConfigurationManager()), new Predicate<EditedStyleItem>() {
      @Override
      public boolean apply(@javax.annotation.Nullable EditedStyleItem input) {
        assert input != null;
        return localAttributes.contains(input.getQualifiedName());
      }
    });
  }

  /**
   * Returns a {@link JListFixture} for the auto-completion popup
   */
  @NotNull
  public static JListFixture getCompletionPopup(@NotNull Robot robot) {
    JBList list = GuiTests.waitUntilFound(robot, new GenericTypeMatcher<JBList>(JBList.class) {
      @Override
      protected boolean isMatching(@NotNull JBList component) {
        ListModel listModel = component.getModel();
        return listModel.getSize() > 0 && listModel.getElementAt(0) instanceof LookupElement;
      }
    });
    JListFixture listFixture = new JListFixture(robot, list);
    listFixture.replaceCellReader(new JListCellReader() {
      @Nullable
      @Override
      public String valueAt(@NotNull JList list, int index) {
        return ((LookupElement)list.getModel().getElementAt(index)).getLookupString();
      }
    });
    return listFixture;
  }
}
