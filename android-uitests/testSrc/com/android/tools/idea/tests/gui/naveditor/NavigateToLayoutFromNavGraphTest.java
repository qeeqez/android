/*
 * Copyright (C) 2022 The Android Open Source Project
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
package com.android.tools.idea.tests.gui.naveditor;

import static com.android.tools.idea.wizard.template.Language.Java;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertEquals;

import com.android.tools.idea.tests.gui.framework.GuiTestRule;
import com.android.tools.idea.tests.gui.framework.RunIn;
import com.android.tools.idea.tests.gui.framework.TestGroup;
import com.android.tools.idea.tests.gui.framework.fixture.EditorFixture;
import com.android.tools.idea.tests.gui.framework.fixture.IdeFrameFixture;
import com.android.tools.idea.tests.gui.framework.fixture.designer.NlEditorFixture;
import com.android.tools.idea.tests.gui.framework.fixture.designer.naveditor.NavDesignSurfaceFixture;
import com.android.tools.idea.tests.util.WizardUtils;
import com.intellij.testGuiFramework.framework.GuiTestRemoteRunner;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(GuiTestRemoteRunner.class)
public class NavigateToLayoutFromNavGraphTest {
  @Rule public final GuiTestRule guiTest = new GuiTestRule().withTimeout(15, TimeUnit.MINUTES);
  private IdeFrameFixture ideFrame = null;
  private NlEditorFixture editorFixture = null;

  protected static final String BASIC_ACTIVITY_TEMPLATE = "Basic Views Activity";
  protected static final String APP_NAME = "App";
  protected static final String PACKAGE_NAME = "android.com.app";
  protected static final int MIN_SDK_API = 30;

  private static final String FRAGMENT_FILE_NAME = "fragment_first.xml";
  private static final String FRAGMENT_FILE_PATH = "app/src/main/res/layout/fragment_first.xml";


  @Before
  public void setUp() throws Exception{
    guiTest.waitForAllBackgroundTasksToBeCompleted();

    WizardUtils.createNewProject(guiTest, BASIC_ACTIVITY_TEMPLATE, APP_NAME, PACKAGE_NAME, MIN_SDK_API, Java);
    guiTest.waitForAllBackgroundTasksToBeCompleted();

    IdeFrameFixture ideFrame = guiTest.ideFrame();
    ideFrame.clearNotificationsPresentOnIdeFrame();
    guiTest.waitForAllBackgroundTasksToBeCompleted();

    ideFrame.getProjectView().assertFilesExist(
      "app/src/main/res/navigation/nav_graph.xml"
    );
  }

  /**
   * Verify cursor jumps to layout file from navigation graph
   * <p>
   * This is run to qualify releases. Please involve the test team in substantial changes.
   * <p>
   * TT ID: cfd64185-f34e-40be-8fe7-8dfbf0990f49
   * <p>
   *   <pre>
   *   Test Steps:
   *   1. Create a project using 'Basic Views Activity' template.
   *   2. Open nav_graph. xml from the navigation resource directory and wait for the file to load.
   *   3. Click the 'Design' mode from the top-right corner
   *   4. In the Visual view of the editor, double click FirstFragment. (Verify 1)
   *   5. Close fragment_first.xml file
   *   6. Click the 'Split' mode from the top-right corner of nav_graph.xml
   *   7. In the Visual view of the editor, double click FirstFragment. (Verify 1)
   *
   *   Verify:
   *   1. fragment_first.xml opens and is active
   *   </pre>
   *
   */
  @RunIn(TestGroup.FAST_BAZEL)
  @Test
  public void navigateToLayoutFromDesignTab() throws Exception{

    //Loading the navigation layout from Design View
    editorFixture  = guiTest.ideFrame()
      .getEditor()
      .open("app/src/main/res/navigation/nav_graph.xml", EditorFixture.Tab.DESIGN)
      .getLayoutEditor()
      .waitForSurfaceToLoad()
      .waitForRenderToFinish();
    guiTest.waitForAllBackgroundTasksToBeCompleted();
    assertThat(editorFixture.canInteractWithSurface()).isTrue();

    // Modify "Set Start Destination" in right click menu
    ((NavDesignSurfaceFixture)editorFixture.getSurface()).findDestination("FirstFragment").doubleClick();

    //Wait for fragment file to open
    waitForFragmentFile();

    assertEquals(FRAGMENT_FILE_NAME, guiTest.ideFrame().getEditor().getCurrentFileName());

    guiTest.ideFrame().getEditor().closeFile(FRAGMENT_FILE_PATH);
    guiTest.robot().focusAndWaitForFocusGain(editorFixture.target());

    //Loading the navigation layout from Split View
    guiTest.ideFrame().getEditor().selectEditorTab(EditorFixture.Tab.SPLIT);
    guiTest.waitForAllBackgroundTasksToBeCompleted();

    // Modify "Set Start Destination" in right click menu
    ((NavDesignSurfaceFixture)editorFixture.getSurface()).findDestination("FirstFragment").doubleClick();

    //Wait for fragment file to open
    waitForFragmentFile();

    assertEquals(FRAGMENT_FILE_NAME, guiTest.ideFrame().getEditor().getCurrentFileName());
  }

  private void waitForFragmentFile() {
    guiTest.waitForAllBackgroundTasksToBeCompleted();
    guiTest.robot().waitForIdle();

    guiTest.ideFrame().getEditor()
      .selectEditorTab(EditorFixture.Tab.EDITOR);

    guiTest.ideFrame().requestFocusIfLost();

    guiTest.waitForAllBackgroundTasksToBeCompleted();
    guiTest.robot().waitForIdle();
  }
}
