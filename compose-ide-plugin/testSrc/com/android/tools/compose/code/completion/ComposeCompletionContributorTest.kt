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
package com.android.tools.compose.code.completion

import com.android.testutils.MockitoKt.mock
import com.android.testutils.MockitoKt.whenever
import com.android.tools.compose.COMPOSABLE_FQ_NAMES_ROOT
import com.android.tools.compose.ComposeSettings
import com.android.tools.compose.code.completion.ComposeMaterialIconLookupElement.Companion.resourcePathFromFqName
import com.android.tools.idea.project.DefaultModuleSystem
import com.android.tools.idea.projectsystem.getModuleSystem
import com.android.tools.idea.testing.AndroidProjectRule
import com.android.tools.idea.testing.caret
import com.android.tools.idea.testing.loadNewFile
import com.google.common.truth.Truth.assertThat
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.openapi.application.runReadAction
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import org.jetbrains.android.compose.stubComposableAnnotation
import org.jetbrains.kotlin.psi.KtProperty
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Tests for [ComposeCompletionContributor].
 */
class ComposeCompletionContributorTest {

  @get:Rule
  val projectRule = AndroidProjectRule.inMemory()

  private val myFixture: CodeInsightTestFixture by lazy { projectRule.fixture }

  @Before
  fun setUp() {
    (myFixture.module.getModuleSystem() as DefaultModuleSystem).usesCompose = true
    myFixture.stubComposableAnnotation(COMPOSABLE_FQ_NAMES_ROOT)
  }

  @Test
  fun testSignatures() {
    myFixture.addFileToProject(
      "src/com/example/MyViews.kt",
      // language=kotlin
      """
      package com.example

      import androidx.compose.runtime.Composable

      // "Foobar" is a unique prefix that no other lookup elements will match.

      @Composable
      fun FoobarOne(required: Int) {}

      @Composable
      fun FoobarTwo(required: Int, optional: Int = 42) {}

      @Composable
      fun FoobarThree(optional: Int = 42, children: @Composable() () -> Unit) {}

      @Composable
      fun FoobarFour(children: @Composable() () -> Unit) {}

      @Composable
      fun FoobarFive(icon: String, onClick: () -> Unit) {}
      """.trimIndent()
    )

    val expectedLookupItems = listOf(
      "FoobarOne(required: Int)",
      "FoobarTwo(required: Int, ...)",
      "FoobarThree(...) {...}",
      "FoobarFour {...}",
      "FoobarFive(icon: String, onClick: () -> Unit)"
    )

    // Given:
    myFixture.loadNewFile(
      "src/com/example/Test.kt",
      // language=kotlin
      """
      package com.example

      import androidx.compose.runtime.Composable

      @Composable
      fun HomeScreen() {
        Foobar${caret}
      }
      """.trimIndent()
    )

    // When:
    myFixture.completeBasic()

    // Then:
    // Order doesn't matter here, since we're just validating that the elements are displayed with the correct signature text.
    assertThat(myFixture.renderedLookupElements).containsExactlyElementsIn(expectedLookupItems)

    // Given:
    myFixture.loadNewFile(
      "src/com/example/Test2.kt",
      // language=kotlin
      """
      package com.example

      import androidx.compose.runtime.Composable

      fun setContent(content: @Composable() () -> Unit) { TODO() }

      class MainActivity {
        fun onCreate() {
          setContent {
            Foobar${caret}
          }
        }
      }
      """.trimIndent()
    )

    // When:
    myFixture.completeBasic()

    // Then:
    // Order doesn't matter here, since we're just validating that the elements are displayed with the correct signature text.
    assertThat(myFixture.renderedLookupElements).containsExactlyElementsIn(expectedLookupItems)
  }

  @Test
  fun testInsertHandler() {
    // Given:
    myFixture.addFileToProject(
      "src/com/example/MyViews.kt",
      // language=kotlin
      """
      package com.example

      import androidx.compose.runtime.Composable

      @Composable
      fun FoobarOne(first: Int, second: String, third: String? = null) {}

      """.trimIndent()
    )

    val file = myFixture.addFileToProject(
      "src/com/example/Test.kt",
      // language=kotlin
      """
      package com.example

      import androidx.compose.runtime.Composable

      @Composable
      fun HomeScreen() {
        Foobar${caret}
      }
      """.trimIndent()
    )

    // When:
    myFixture.configureFromExistingVirtualFile(file.virtualFile)
    myFixture.completeBasic()

    // Then:
    myFixture.checkResult(
      // language=kotlin
      """
      package com.example

      import androidx.compose.runtime.Composable

      @Composable
      fun HomeScreen() {
        FoobarOne(first = , second = )
      }
      """.trimIndent()
    )
  }

  @Test
  fun testInsertHandler_dont_insert_before_parenthesis() {
    // Given:
    myFixture.addFileToProject(
      "src/com/example/MyViews.kt",
      // language=kotlin
      """
      package com.example

      import androidx.compose.runtime.Composable

      @Composable
      fun FoobarOne(first: Int, second: String, third: String? = null) {}

      """.trimIndent()
    )

    var file = myFixture.addFileToProject(
      "src/com/example/Test.kt",
      // language=kotlin
      """
      package com.example

      import androidx.compose.runtime.Composable

      @Composable
      fun HomeScreen() {
        Foobar${caret}()
      }
      """.trimIndent()
    )

    // When:
    myFixture.configureFromExistingVirtualFile(file.virtualFile)
    myFixture.completeBasic()

    // Then:
    myFixture.checkResult(
      // language=kotlin
      """
      package com.example

      import androidx.compose.runtime.Composable

      @Composable
      fun HomeScreen() {
        FoobarOne()
      }
      """.trimIndent()
    )


    // Check completion with tab
    file = myFixture.addFileToProject(
      "src/com/example/Test2.kt",
      // language=kotlin
      """
      package com.example

      import androidx.compose.runtime.Composable

      @Composable
      fun HomeScreen() {
        ${caret}()
      }
      """.trimIndent()
    )

    // When:
    myFixture.configureFromExistingVirtualFile(file.virtualFile)
    myFixture.completeBasic()
    myFixture.type("Foobar\t")

    // Then:
    myFixture.checkResult(
      // language=kotlin
      """
      package com.example

      import androidx.compose.runtime.Composable

      @Composable
      fun HomeScreen() {
        FoobarOne()
      }
      """.trimIndent()
    )
  }

  @Test
  fun testInsertHandler_lambda() {
    // Given:
    myFixture.addFileToProject(
      "src/com/example/MyViews.kt",
      // language=kotlin
      """
      package com.example

      import androidx.compose.runtime.Composable

      @Composable
      fun FoobarOne(children: @Composable() () -> Unit) {}

      @Composable
      fun FoobarTwo(children: () -> Unit) {}

      """.trimIndent()
    )

    val file = myFixture.addFileToProject(
      "src/com/example/Test.kt",
      // language=kotlin
      """
      package com.example

      import androidx.compose.runtime.Composable

      @Composable
      fun HomeScreen() {
        FoobarO${caret}
      }
      """.trimIndent()
    )

    // When:
    myFixture.configureFromExistingVirtualFile(file.virtualFile)
    myFixture.completeBasic()

    // Then:
    myFixture.checkResult(
      // language=kotlin
      """
      package com.example

      import androidx.compose.runtime.Composable

      @Composable
      fun HomeScreen() {
        FoobarOne {

        }
      }
      """.trimIndent()
      , true)

    val file2 = myFixture.loadNewFile(
      "src/com/example/Test2.kt",
      // language=kotlin
      """
      package com.example

      import androidx.compose.runtime.Composable

      @Composable
      fun HomeScreen() {
        FoobarT${caret}
      }
      """.trimIndent()
    )

    // When:
    myFixture.configureFromExistingVirtualFile(file2.virtualFile)
    myFixture.completeBasic()

    // Then:
    myFixture.checkResult(
      // language=kotlin
      """
      package com.example

      import androidx.compose.runtime.Composable

      @Composable
      fun HomeScreen() {
        FoobarTwo {
          
        }
      }
      """.trimIndent()
      , true)
  }

  @Test
  fun testInsertHandler_lambda_before_curly_braces() {
    // Given:
    myFixture.addFileToProject(
      "src/com/example/MyViews.kt",
      // language=kotlin
      """
      package com.example

      import androidx.compose.runtime.Composable

      @Composable
      fun FoobarOne(children: @Composable() () -> Unit) {}

      """.trimIndent()
    )

    var file = myFixture.addFileToProject(
      "src/com/example/Test.kt",
      // language=kotlin
      """
      package com.example

      import androidx.compose.runtime.Composable

      @Composable
      fun HomeScreen() {
      // Space after caret.
        $caret {

        }
      }
      """.trimIndent()
    )

    // When:
    myFixture.configureFromExistingVirtualFile(file.virtualFile)
    myFixture.type("Foobar")
    myFixture.completeBasic()

    // Then:
    myFixture.checkResult(
      // language=kotlin
      """
      package com.example

      import androidx.compose.runtime.Composable

      @Composable
      fun HomeScreen() {
      // Space after caret.
        FoobarOne {

        }
      }
      """.trimIndent()
      , true)

    // Given:
    file = myFixture.addFileToProject(
      "src/com/example/Test2.kt",
      // language=kotlin
      """
      package com.example

      import androidx.compose.runtime.Composable

      @Composable
      fun HomeScreen() {
      // No space after caret.
        $caret{

        }
      }
      """.trimIndent()
    )

    // When:
    myFixture.configureFromExistingVirtualFile(file.virtualFile)
    myFixture.type("Foobar")
    myFixture.completeBasic()

    // Then:
    myFixture.checkResult(
      // language=kotlin
      """
      package com.example

      import androidx.compose.runtime.Composable

      @Composable
      fun HomeScreen() {
      // No space after caret.
        FoobarOne{

        }
      }
      """.trimIndent()
      , true)
  }

  @Test
  fun testInsertHandler_lambdaWithOptional() {
    // Given:
    myFixture.addFileToProject(
      "src/com/example/MyViews.kt",
      // language=kotlin
      """
      package com.example

      import androidx.compose.runtime.Composable

      @Composable
      fun FoobarOne(optional: String? = null, children: @Composable() () -> Unit) {}

      """.trimIndent()
    )

    val file = myFixture.addFileToProject(
      "src/com/example/Test.kt",
      // language=kotlin
      """
      package com.example

      import androidx.compose.runtime.Composable

      @Composable
      fun HomeScreen() {
        Foobar${caret}
      }
      """.trimIndent()
    )

    // When:
    myFixture.configureFromExistingVirtualFile(file.virtualFile)
    myFixture.completeBasic()

    // Then:
    myFixture.checkResult(
      // language=kotlin
      """
      package com.example

      import androidx.compose.runtime.Composable

      @Composable
      fun HomeScreen() {
        FoobarOne {

        }
      }
      """.trimIndent()
      , true)
  }

  @Test
  fun testInsertHandler_onClick() {
    // Given:
    myFixture.addFileToProject(
      "src/com/example/MyViews.kt",
      // language=kotlin
      """
      package com.example

      import androidx.compose.runtime.Composable

      @Composable
      fun AppBarIcon(icon: String, onClick: () -> Unit) {}

      """.trimIndent()
    )

    val file = myFixture.addFileToProject(
      "src/com/example/Test.kt",
      // language=kotlin
      """
      package com.example

      import androidx.compose.runtime.Composable

      @Composable
      fun HomeScreen() {
        AppBarIcon${caret}
      }
      """.trimIndent()
    )

    // When:
    myFixture.configureFromExistingVirtualFile(file.virtualFile)
    myFixture.completeBasic()

    // Then:
    myFixture.checkResult(
      // language=kotlin
      """
      package com.example

      import androidx.compose.runtime.Composable

      @Composable
      fun HomeScreen() {
        AppBarIcon(icon = ) {
          
        }
      }
      """.trimIndent()
      , true)
  }

  @Test
  fun testInsertHandler_onClick_lastParameterIsNotLambdaOrRequired() {
    // Given:
    myFixture.addFileToProject(
      "src/com/example/MyViews.kt",
      // language=kotlin
      """
      package com.example

      import androidx.compose.runtime.Composable

      @Composable
      fun RadioButton(text: String, onClick: () -> Unit, label: String = "label") {}

      """.trimIndent()
    )

    val file = myFixture.addFileToProject(
      "src/com/example/Test.kt",
      // language=kotlin
      """
      package com.example

      import androidx.compose.runtime.Composable

      @Composable
      fun HomeScreen() {
        RadioButton${caret}
      }
      """.trimIndent()
    )

    // When:
    myFixture.configureFromExistingVirtualFile(file.virtualFile)
    myFixture.completeBasic()

    // Then:
    myFixture.checkResult(
      // language=kotlin
      """
      package com.example

      import androidx.compose.runtime.Composable

      @Composable
      fun HomeScreen() {
        RadioButton(text = , onClick = { /*TODO*/ })
      }
      """.trimIndent()
      , true)
  }

  @Test
  fun testInsertHandler_disabledThroughSettings() {
    // Given:
    myFixture.addFileToProject(
      "src/com/example/MyViews.kt",
      // language=kotlin
      """
      package com.example

      import androidx.compose.runtime.Composable

      @Composable
      fun FoobarOne(first: Int, second: String, third: String? = null) {}

      """.trimIndent()
    )

    val file = myFixture.addFileToProject(
      "src/com/example/Test.kt",
      // language=kotlin
      """
      package com.example

      import androidx.compose.runtime.Composable

      @Composable
      fun HomeScreen() {
        Foobar${caret}
      }
      """.trimIndent()
    )

    // When:
    ComposeSettings.getInstance().state.isComposeInsertHandlerEnabled = false
    myFixture.configureFromExistingVirtualFile(file.virtualFile)
    myFixture.completeBasic()

    // Then:
    myFixture.checkResult(
      // language=kotlin
      """
      package com.example

      import androidx.compose.runtime.Composable

      @Composable
      fun HomeScreen() {
        FoobarOne()
      }
      """.trimIndent()
    )

    ComposeSettings.getInstance().state.isComposeInsertHandlerEnabled = true
  }

  @Test
  fun testInsertHandler_inKDoc() {
    // Given:
    myFixture.addFileToProject(
      "src/com/example/MyViews.kt",
      // language=kotlin
      """
      package com.example

      import androidx.compose.runtime.Composable

      @Composable
      fun FoobarOne(first: Int, second: String, third: String? = null) {}

      """.trimIndent()
    )

    val file = myFixture.addFileToProject(
      "src/com/example/Test.kt",
      // language=kotlin
      """
      package com.example

      import androidx.compose.runtime.Composable

      /**
       * [Foobar${caret}]
       */
      @Composable
      fun HomeScreen() {
      }
      """.trimIndent()
    )

    // When:
    myFixture.configureFromExistingVirtualFile(file.virtualFile)
    myFixture.completeBasic()

    // Then:
    myFixture.checkResult(
      // language=kotlin
      """
      package com.example

      import androidx.compose.runtime.Composable

      /**
       * [FoobarOne]
       */
      @Composable
      fun HomeScreen() {
      }
      """.trimIndent()
    )
  }

  /**
   * Regression test for b/153769933. The Compose insertion handler adds the parameters automatically when completing the name
   * of a Composable. This is incorrect if the insertion point is not a call statement. This ensures that the insertion is not triggered
   * for imports.
   */
  @Test
  fun testImportCompletionDoesNotTriggerInsertionHandler() {
    myFixture.addFileToProject(
      "src/androidx/compose/foundation/Canvas.kt",
      // language=kotlin
      """
      package androidx.compose.foundation

      import androidx.compose.runtime.Composable

      // This simulates the Canvas composable
      @Composable
      fun Canvas(children: @Composable() () -> Unit) {}
    """)


    // Given:
    myFixture.loadNewFile(
      "src/com/example/Test.kt",
      // language=kotlin
      """
      package com.example

      import androidx.compose.runtime.Composable
      import androidx.compose.foundation.Ca${caret}

      @Composable
      fun Test() {
      }
      """.trimIndent()
    )

    // When:
    myFixture.completeBasic()

    // Then:
    myFixture.checkResult(
      // language=kotlin
      """
      package com.example

      import androidx.compose.runtime.Composable
      import androidx.compose.foundation.Canvas

      @Composable
      fun Test() {
      }
      """.trimIndent()
      , true)
  }

  /**
   * Regression test for b/209672710. Ensure that completing Composables that are not top-level does not fully qualify them incorrectly.
   */
  @Test
  fun testCompletingComposablesWithinObjects() {
    myFixture.addFileToProject(
      "src/com/example/ObjectWithComposables.kt",
      // language=kotlin
      """
      package com.example

      import androidx.compose.runtime.Composable

      object ObjectWithComposables {
        // This simulates the Canvas composable
        @Composable
        fun TestMethod(children: @Composable() () -> Unit) {}
      }
    """)


    // Given:
    myFixture.loadNewFile(
      "src/com/example/Test.kt",
      // language=kotlin
      """
      package com.example

      import androidx.compose.runtime.Composable

      @Composable
      fun Test() {
        ObjectWithComposables.Test${caret}
      }
      """.trimIndent()
    )

    // When:
    myFixture.completeBasic()

    // Then:
    myFixture.checkResult(
      // language=kotlin
      """
      package com.example

      import androidx.compose.runtime.Composable

      @Composable
      fun Test() {
        ObjectWithComposables.TestMethod {

        }
      }
      """.trimIndent()
      , true)
  }

  /**
   * Regression test for b/209060418. Autocomplete should not treat required composable method specially if it's not the final argument (ie,
   * there are optional arguments specified after it.
   */
  @Test
  fun testSignaturesWithRequiredComposableBeforeOptionalArgs() {
    myFixture.addFileToProject(
      "src/com/example/ObjectWithComposables.kt",
      // language=kotlin
      """
      package com.example

      import androidx.compose.runtime.Composable

      // "Foobar" is a unique prefix that no other lookup elements will match.

      @Composable
      fun FoobarOne(requiredArg: @Composable () -> Unit, optionalArg: Int = 0) {}

      @Composable
      fun FoobarTwo(optionalArg: Int = 0) {}

      fun FoobarThree(requiredArg: @Composable () -> Unit, optionalArg: Int = 0) {}

      fun FoobarFour(optionalArg: Int = 0) {}

      @Composable
      fun FoobarFive(requiredArg: () -> Unit, optionalArg: Int = 0) {}

      @Composable
      fun FoobarSix(optionalArg: Int = 0) {}
      """.trimIndent()
    )

    val expectedLookupItems = listOf(
      "FoobarOne(requiredArg: () -> Unit, ...)",
      "FoobarTwo(...)",
      "FoobarThree(requiredArg: () -> Unit, optionalArg: Int = ...) (com.example) Unit",
      "FoobarFour(optionalArg: Int = ...) (com.example) Unit",
      "FoobarFive(requiredArg: () -> Unit, ...)",
      "FoobarSix(...)",
    )

    // Given:
    myFixture.loadNewFile(
      "src/com/example/Test.kt",
      // language=kotlin
      """
      package com.example

      import androidx.compose.runtime.Composable

      @Composable
      fun HomeScreen() {
        Foobar${caret}
      }
      """.trimIndent()
    )

    // When:
    myFixture.completeBasic()

    // Then:
    // Order doesn't matter here, since we're just validating that the elements are displayed with the correct signature text.
    assertThat(myFixture.renderedLookupElements).containsExactlyElementsIn(expectedLookupItems)
  }

  /**
   * Regression test for b/209060418. Autocomplete should not treat required composable method specially if it's not the final argument (ie,
   * there are optional arguments specified after it.
   */
  @Test
  fun testInsertHandlerWithRequiredComposableBeforeOptionalArgs() {
    myFixture.addFileToProject(
      "src/com/example/ObjectWithComposables.kt",
      // language=kotlin
      """
      package com.example

      import androidx.compose.runtime.Composable

      // "Foobar" is a unique prefix that no other lookup elements will match.

      @Composable
      fun FoobarOne(requiredArg: @Composable () -> Unit, optionalArg: Int = 0) {}
      """.trimIndent()
    )

    // Given:
    myFixture.loadNewFile(
      "src/com/example/Test.kt",
      // language=kotlin
      """
      package com.example

      import androidx.compose.runtime.Composable

      @Composable
      fun HomeScreen() {
        Foobar${caret}
      }
      """.trimIndent()
    )

    // When:
    myFixture.completeBasic()

    // Then:
    myFixture.checkResult(
      // language=kotlin
      """
      package com.example

      import androidx.compose.runtime.Composable

      @Composable
      fun HomeScreen() {
        FoobarOne(requiredArg = { /*TODO*/ })
      }
      """.trimIndent()
      , true)
  }

  /**
   * Regression test for b/182564317. Autocomplete should not treat varargs as required.
   */
  @Test
  fun testInsertHandlerWithVarArgs() {
    myFixture.addFileToProject(
      "src/com/example/ObjectWithComposables.kt",
      // language=kotlin
      """
      package com.example

      import androidx.compose.runtime.Composable

      // "Foobar" is a unique prefix that no other lookup elements will match.

      @Composable
      fun FoobarOne(vararg inputs: Any?, children: @Composable () -> Unit) {}
      """.trimIndent()
    )

    // Given:
    myFixture.loadNewFile(
      "src/com/example/Test.kt",
      // language=kotlin
      """
      package com.example

      import androidx.compose.runtime.Composable

      @Composable
      fun HomeScreen() {
        Foobar${caret}
      }
      """.trimIndent()
    )

    // When:
    myFixture.completeBasic()

    // Then:
    myFixture.checkResult(
      // language=kotlin
      """
      package com.example

      import androidx.compose.runtime.Composable

      @Composable
      fun HomeScreen() {
        FoobarOne {

        }
      }
      """.trimIndent()
      , true)
  }

  /**
   * Regression test for b/182564317. Autocomplete should not treat varargs as required.
   */
  @Test
  fun testInsertHandlerWithVarArgsLambda() {
    myFixture.addFileToProject(
      "src/com/example/ObjectWithComposables.kt",
      // language=kotlin
      """
      package com.example

      import androidx.compose.runtime.Composable

      // "Foobar" is a unique prefix that no other lookup elements will match.

      @Composable
      fun FoobarOne(vararg children: @Composable () -> Unit) {}
      """.trimIndent()
    )

    // Given:
    myFixture.loadNewFile(
      "src/com/example/Test.kt",
      // language=kotlin
      """
      package com.example

      import androidx.compose.runtime.Composable

      @Composable
      fun HomeScreen() {
        Foobar${caret}
      }
      """.trimIndent()
    )

    // When:
    myFixture.completeBasic()

    // Then:
    myFixture.checkResult(
      // language=kotlin
      """
      package com.example

      import androidx.compose.runtime.Composable

      @Composable
      fun HomeScreen() {
        FoobarOne()
      }
      """.trimIndent()
      , true)
  }

  @Test
  fun composeMaterialIconLookupElement_resourcePathFromFqName() {
    assertThat("androidx.compose.material.icons.filled.AccountBox".resourcePathFromFqName())
      .isEqualTo("images/material/icons/materialicons/account_box/baseline_account_box_24.xml")
    assertThat("androidx.compose.material.icons.rounded.AllInbox".resourcePathFromFqName())
      .isEqualTo("images/material/icons/materialiconsround/all_inbox/round_all_inbox_24.xml")
    assertThat("androidx.compose.material.icons.sharp.Check".resourcePathFromFqName())
      .isEqualTo("images/material/icons/materialiconssharp/check/sharp_check_24.xml")
    assertThat("androidx.compose.material.icons.twotone.10k".resourcePathFromFqName())
      .isEqualTo("images/material/icons/materialiconstwotone/10k/twotone_10k_24.xml")
    assertThat("androidx.compose.material.icons.outlined.Adb".resourcePathFromFqName())
      .isEqualTo("images/material/icons/materialiconsoutlined/adb/outline_adb_24.xml")

    assertThat("androidx.compose.material.icons.unknown.Adb".resourcePathFromFqName()).isNull()
    assertThat("androidx.compose.material.icons.filled.extrapackage.Adb".resourcePathFromFqName()).isNull()
  }

  @Test
  fun composeMaterialIconLookupElement_appliesTo() {
    myFixture.addFileToProject(
      "src/androidx/compose/ut/graphics/vector/ImageVector.kt",
      // language=kotlin
      """
      package androidx.compose.ui.graphics.vector

      class ImageVector
      """.trimIndent()
    )

    myFixture.addFileToProject(
      "src/androidx/compose/material/icons/Icons.kt",
      // language=kotlin
      """
      package androidx.compose.material.icons

      object Icons {
        object Filled
      }
      """.trimIndent()
    )

    myFixture.loadNewFile(
      "src/androidx/compose/material/icons/filled/AccountBox.kt",
      // language=kotlin
      """
      package androidx.compose.material.icons.filled

      import androidx.compose.ui.graphics.vector.ImageVector

      val androidx.compose.material.icons.Icons.Filled.Accoun<caret>tBox: ImageVector
        get() = ImageVector()

      """.trimIndent()
    )

    val accountBox = runReadAction { myFixture.elementAtCaret }
    assertThat(accountBox).isInstanceOf(KtProperty::class.java)

    val mockLookupElement: LookupElement = mock()
    whenever(mockLookupElement.psiElement).thenReturn(accountBox)

    assertThat(runReadAction { ComposeMaterialIconLookupElement.appliesTo(mockLookupElement) }).isTrue()
  }

  @Test
  fun composeMaterialIconLookupElement_appliesToUnknownPackage() {
    myFixture.addFileToProject(
      "src/androidx/compose/ut/graphics/vector/ImageVector.kt",
      // language=kotlin
      """
      package androidx.compose.ui.graphics.vector

      class ImageVector
      """.trimIndent()
    )

    myFixture.addFileToProject(
      "src/androidx/compose/material/icons/Icons.kt",
      // language=kotlin
      """
      package androidx.compose.material.icons

      object Icons {
        object Unknown
      }
      """.trimIndent()
    )

    myFixture.loadNewFile(
      "src/androidx/compose/material/icons/unknown/AccountBox.kt",
      // language=kotlin
      """
      package androidx.compose.material.icons.unknown

      import androidx.compose.ui.graphics.vector.ImageVector

      val androidx.compose.material.icons.Icons.Unknown.Accoun<caret>tBox: ImageVector
        get() = ImageVector()

      """.trimIndent()
    )

    val accountBox = runReadAction { myFixture.elementAtCaret }
    assertThat(accountBox).isInstanceOf(KtProperty::class.java)

    val mockLookupElement: LookupElement = mock()
    whenever(mockLookupElement.psiElement).thenReturn(accountBox)

    assertThat(runReadAction { ComposeMaterialIconLookupElement.appliesTo(mockLookupElement) }).isFalse()
  }

  @Test
  fun composeMaterialIconLookupElement_getIcon() {
    assertThat(
      ComposeMaterialIconLookupElement.getIcon("images/material/icons/materialicons/account_box/baseline_account_box_24.xml"))
      .isNotNull()
    assertThat(
      ComposeMaterialIconLookupElement.getIcon("images/material/icons/materialicons/account_box/unknown.xml"))
      .isNull()
  }

  private val CodeInsightTestFixture.renderedLookupElements: Collection<String>
    get() {
      return runReadAction {
        lookupElements.orEmpty().map { lookupElement ->
          val presentation = LookupElementPresentation()
          lookupElement.renderElement(presentation)
          buildString {
            append(presentation.itemText)
            append(presentation.tailText)
            if (!presentation.typeText.isNullOrEmpty()) {
              append(" ")
              append(presentation.typeText)
            }
          }
        }
      }
    }
}
