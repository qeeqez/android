/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.android.tools.idea.compose.preview

import com.android.tools.idea.common.model.NlModel
import com.android.tools.idea.common.surface.DesignSurface
import com.android.tools.idea.common.surface.DesignSurfaceListener
import com.android.tools.idea.compose.ComposeProjectRule
import com.android.tools.idea.compose.preview.navigation.ComposePreviewNavigationHandler
import com.android.tools.idea.compose.preview.util.ComposePreviewElement
import com.android.tools.idea.concurrency.AndroidDispatchers.workerThread
import com.android.tools.idea.preview.PreviewElementProvider
import com.android.tools.idea.projectsystem.ProjectSystemService
import com.android.tools.idea.projectsystem.TestProjectSystem
import com.android.tools.idea.testing.addFileToProjectAndInvalidate
import com.android.tools.idea.uibuilder.editor.multirepresentation.PreferredVisibility
import com.android.tools.idea.uibuilder.scene.LayoutlibSceneManager
import com.android.tools.idea.uibuilder.surface.NlDesignSurface
import com.intellij.openapi.application.runWriteActionAndWait
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.runInEdtAndWait
import java.util.concurrent.CountDownLatch
import javax.swing.JComponent
import javax.swing.JPanel
import kotlin.test.assertContains
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

internal class TestComposePreviewView(
  override val surfaces: List<NlDesignSurface>,
  override val mainSurface: NlDesignSurface
) : ComposePreviewView {
  override val component: JComponent
    get() = JPanel()
  override var bottomPanel: JComponent? = null
  override var showPinToolbar: Boolean = true
  override val isMessageBeingDisplayed: Boolean = false
  override var hasContent: Boolean = true
  override var hasRendered: Boolean = true

  override fun updateNotifications(parentEditor: FileEditor) {}

  override fun updateVisibilityAndNotifications() {}

  override fun updateProgress(message: String) {}

  override fun setPinnedSurfaceVisibility(visible: Boolean) {}

  override fun onRefreshCancelledByTheUser() {}

  override fun onRefreshCompleted() {}
}

class ComposePreviewRepresentationTest {
  @get:Rule
  val projectRule =
    ComposeProjectRule(
      previewAnnotationPackage = "androidx.compose.ui.tooling.preview",
      composableAnnotationPackage = "androidx.compose.runtime"
    )
  private val project
    get() = projectRule.project
  private val fixture
    get() = projectRule.fixture

  @Before
  fun setup() {
    val testProjectSystem = TestProjectSystem(project)
    runInEdtAndWait { testProjectSystem.useInTests() }
  }

  @Test
  fun testPreviewInitialization() =
    runBlocking(workerThread) {
      val composeTest = runWriteActionAndWait {
        fixture.addFileToProjectAndInvalidate(
          "Test.kt",
          // language=kotlin
          """
        import androidx.compose.ui.tooling.preview.Devices
        import androidx.compose.ui.tooling.preview.Preview
        import androidx.compose.runtime.Composable

        @Composable
        @Preview
        fun Preview1() {
        }

        @Composable
        @Preview(name = "preview2", apiLevel = 12, group = "groupA", showBackground = true)
        fun Preview2() {
        }
      """.trimIndent()
        )
      }

      val navigationHandler = ComposePreviewNavigationHandler()
      val pinnedSurface =
        NlDesignSurface.builder(project, fixture.testRootDisposable)
          .setNavigationHandler(navigationHandler)
          .build()
      val mainSurface =
        NlDesignSurface.builder(project, fixture.testRootDisposable)
          .setNavigationHandler(navigationHandler)
          .build()
      val modelRenderedLatch = CountDownLatch(2)

      mainSurface.addListener(
        object : DesignSurfaceListener {
          override fun modelChanged(surface: DesignSurface<*>, model: NlModel?) {
            (surface.getSceneManager(model!!) as? LayoutlibSceneManager)?.addRenderListener {
              modelRenderedLatch.countDown()
            }
          }
        }
      )

      val composeView = TestComposePreviewView(listOf(pinnedSurface), mainSurface)
      val preview =
        ComposePreviewRepresentation(
          composeTest,
          object : PreviewElementProvider<ComposePreviewElement> {
            override suspend fun previewElements(): Sequence<ComposePreviewElement> =
              AnnotationFilePreviewElementFinder.findPreviewMethods(
                  project,
                  composeTest.virtualFile
                )
                .asSequence()
          },
          PreferredVisibility.SPLIT
        ) { _, _, _, _, _, _, _, _, _ -> composeView }
      Disposer.register(fixture.testRootDisposable, preview)
      withContext(Dispatchers.IO) {
        ProjectSystemService.getInstance(project).projectSystem.getBuildManager().compileProject()
        preview.onActivate()

        modelRenderedLatch.await()

        while (preview.status().isRefreshing || DumbService.getInstance(project).isDumb) kotlinx
          .coroutines.delay(250)
      }

      mainSurface.models.forEach { assertContains(navigationHandler.defaultNavigationMap, it) }

      assertArrayEquals(
        arrayOf("groupA"),
        preview.availableGroups.map { it.displayName }.toTypedArray()
      )

      val status = preview.status()
      val debugStatus = preview.debugStatusForTesting()
      assertFalse(debugStatus.toString(), status.isOutOfDate)
      // Ensure the only warning message is the missing Android SDK message
      assertTrue(
        debugStatus.renderResult.flatMap { it.logger.messages }.none {
          !it.html.contains("No Android SDK found.")
        }
      )
      preview.onDeactivate()
    }
}
