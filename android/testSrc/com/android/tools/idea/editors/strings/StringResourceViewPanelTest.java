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
package com.android.tools.idea.editors.strings;

import static com.android.tools.idea.concurrency.AsyncTestUtils.waitForCondition;
import static com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents;

import com.android.tools.idea.editors.strings.table.NeedsTranslationsRowFilter;
import com.android.tools.idea.editors.strings.table.StringResourceTable;
import com.android.tools.idea.editors.strings.table.StringResourceTableModel;
import com.android.tools.idea.editors.strings.table.StringTableCellEditor;
import com.android.tools.idea.res.LocalResourceRepository;
import com.android.tools.idea.res.ResourcesTestsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.concurrency.SameThreadExecutor;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.AbstractButton;
import javax.swing.CellEditor;
import javax.swing.DefaultCellEditor;
import org.jetbrains.android.AndroidTestCase;
import org.jetbrains.annotations.NotNull;

/**
 * Tests for {@link StringResourceViewPanel}.
 */
public final class StringResourceViewPanelTest extends AndroidTestCase {
  private StringResourceViewPanel myPanel;
  private StringResourceTable myTable;
  private LocalResourceRepository myRepository;

  @Override
  protected void setUp() throws Exception {
    super.setUp();

    myPanel = new StringResourceViewPanel(myFacet, getTestRootDisposable());
    myTable = myPanel.getTable();

    VirtualFile resourceDirectory = myFixture.copyDirectoryToProject("stringsEditor/base/res", "res");
    myRepository = ResourcesTestsUtil.createTestModuleRepository(myFacet, Collections.singletonList(resourceDirectory));
    myPanel.getTable().setModel(new StringResourceTableModel(Utils.createStringRepository(myRepository), myFacet.getModule().getProject()));
  }

  public void testSetShowingOnlyKeysNeedingTranslations() {
    Object expectedColumn = Arrays.asList(
      "key1",
      "key2",
      "key3",
      "key5",
      "key6",
      "key7",
      "key8",
      "key4",
      "key9",
      "key10");

    assertEquals(expectedColumn, myTable.getColumnAt(StringResourceTableModel.KEY_COLUMN));

    myTable.setRowFilter(new NeedsTranslationsRowFilter());

    expectedColumn = Arrays.asList(
      "key1",
      "key3",
      "key7",
      "key8",
      "key4",
      "key9",
      "key10");

    assertEquals(expectedColumn, myTable.getColumnAt(StringResourceTableModel.KEY_COLUMN));
  }

  public void testTableDoesntRefilterAfterEditingUntranslatableCell() throws Exception {
    myTable.setRowFilter(new NeedsTranslationsRowFilter());
    editCellAt(true, 0, StringResourceTableModel.UNTRANSLATABLE_COLUMN);

    Object expectedColumn = Arrays.asList(
      "key1",
      "key3",
      "key7",
      "key8",
      "key4",
      "key9",
      "key10");

    assertEquals(expectedColumn, myTable.getColumnAt(StringResourceTableModel.KEY_COLUMN));
  }

  public void testTableDoesntRefilterAfterEditingTranslationCell() throws Exception {
    myTable.setRowFilter(new NeedsTranslationsRowFilter());
    editCellAt("Key 3 en-rGB", 2, 6);

    Object expectedColumn = Arrays.asList(
      "key1",
      "key3",
      "key7",
      "key8",
      "key4",
      "key9",
      "key10");

    assertEquals(expectedColumn, myTable.getColumnAt(StringResourceTableModel.KEY_COLUMN));
  }

  public void testSelectingCell() {
    myTable.setRowFilter(new NeedsTranslationsRowFilter());
    myTable.selectCellAt(1, StringResourceTableModel.DEFAULT_VALUE_COLUMN);

    assertEquals("Key 3 default", myPanel.myDefaultValueTextField.getTextField().getText());
  }

  private void editCellAt(@NotNull Object value, int viewRowIndex, int viewColumnIndex) throws TimeoutException {
    myTable.selectCellAt(viewRowIndex, viewColumnIndex);
    myTable.editCellAt(viewRowIndex, viewColumnIndex);

    CellEditor cellEditor = myTable.getCellEditor();

    if (viewColumnIndex == StringResourceTableModel.UNTRANSLATABLE_COLUMN) {
      Object component = ((DefaultCellEditor)cellEditor).getComponent();
      ((AbstractButton)component).setSelected((Boolean)value);
    }
    else {
      ((StringTableCellEditor)cellEditor).setCellEditorValue(value);
    }

    cellEditor.stopCellEditing();

    AtomicBoolean done = new AtomicBoolean();
    myRepository.invokeAfterPendingUpdatesFinish(SameThreadExecutor.INSTANCE, () -> done.set(true));
    waitForCondition(2, TimeUnit.SECONDS, done::get);
    dispatchAllInvocationEvents();
  }
}
