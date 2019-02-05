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
package com.android.tools.idea.naveditor.property.inspector

import com.android.SdkConstants.CLASS_PARCELABLE
import com.android.tools.idea.common.SyncNlModel
import com.android.tools.idea.naveditor.NavModelBuilderUtil
import com.android.tools.idea.naveditor.NavModelBuilderUtil.navigation
import com.android.tools.idea.naveditor.NavTestCase
import com.android.tools.idea.naveditor.analytics.TestNavUsageTracker
import com.android.tools.idea.testing.IdeComponents
import com.google.wireless.android.sdk.stats.NavEditorEvent
import com.google.wireless.android.sdk.stats.NavPropertyInfo
import com.intellij.ide.util.TreeClassChooser
import com.intellij.ide.util.TreeClassChooserFactory
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiManager
import com.intellij.psi.util.ClassUtil
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.*

class AddArgumentDialogTest : NavTestCase() {
  fun testValidation() {
    val model = model("nav.xml") {
      navigation {
        fragment("fragment1")
      }
    }
    AddArgumentDialog(null, model.find("fragment1")!!).runAndClose { dialog ->
      assertNotNull(dialog.doValidate())

      dialog.name = "myArgument"
      assertNull(dialog.doValidate())

      dialog.type = "boolean"
      assertNull(dialog.doValidate())

      dialog.type = "long"
      dialog.defaultValue = "1234"
      assertNull(dialog.doValidate())
      dialog.defaultValue = "abcdL"
      assertNotNull(dialog.doValidate())
      dialog.defaultValue = "1234L"
      assertNull(dialog.doValidate())

      dialog.type = "reference"
      dialog.defaultValue = "1234"
      assertNotNull(dialog.doValidate())
      dialog.defaultValue = "@id/bad_id"
      assertNotNull(dialog.doValidate())
      dialog.defaultValue = "@id/progressBar"
      assertNull(dialog.doValidate())
      dialog.defaultValue = "@layout/activity_main"
      assertNull(dialog.doValidate())
    }
  }

  fun testInitWithExisting() {
    val model = model("nav.xml") {
      navigation {
        fragment("fragment1") {
          argument("myArgument", type = "integer", value = "1234")
          argument("myArgument2", type = "custom.Parcelable", nullable = true)
          argument("myArgument3")
        }
      }
    }
    val fragment1 = model.find("fragment1")!!
    AddArgumentDialog(fragment1.getChild(0), fragment1).runAndClose { dialog ->
      assertEquals("myArgument", dialog.name)
      assertEquals("integer", dialog.type)
      assertFalse(dialog.isNullable)
      assertEquals("1234", dialog.defaultValue)
    }

    AddArgumentDialog(fragment1.getChild(1), fragment1).runAndClose { dialog ->
      assertEquals("myArgument2", dialog.name)
      assertEquals("custom.Parcelable", dialog.type)
      assertTrue(dialog.isNullable)
      assertTrue(dialog.defaultValue.isNullOrEmpty())
    }

    AddArgumentDialog(fragment1.getChild(2), fragment1).runAndClose { dialog ->
      assertEquals("myArgument3", dialog.name)
      assertNull(dialog.type)
      assertFalse(dialog.isNullable)
      assertTrue(dialog.defaultValue.isNullOrEmpty())
    }
  }

  fun testDefaultValueEditor() {
    val model = model("nav.xml") {
      NavModelBuilderUtil.navigation {
        fragment("fragment1") {
          argument("myArgument", type = "custom.Parcelable")
        }
      }
    }
    val fragment1 = model.find("fragment1")!!
    AddArgumentDialog(fragment1.children[0], fragment1).runAndClose { dialog ->

      assertTrue(dialog.dialogUI.myDefaultValueComboBox.isVisible)
      assertFalse(dialog.dialogUI.myDefaultValueTextField.isVisible)

      for (t in listOf("integer", "long", "string", "reference", null)) {
        dialog.type = t
        assertFalse(dialog.dialogUI.myDefaultValueComboBox.isVisible)
        assertTrue(dialog.dialogUI.myDefaultValueTextField.isVisible)
      }
      dialog.type = "boolean"
      assertTrue(dialog.dialogUI.myDefaultValueComboBox.isVisible)
      assertFalse(dialog.dialogUI.myDefaultValueTextField.isVisible)
    }
  }

  fun testNullable() {
    val model = model("nav.xml") {
      NavModelBuilderUtil.navigation {
        fragment("fragment1") {
          argument("myArgument", type = "custom.Parcelable")
        }
      }
    }
    val fragment1 = model.find("fragment1")!!

    AddArgumentDialog(fragment1.children[0], fragment1).runAndClose { dialog ->
      assertTrue(dialog.dialogUI.myNullableCheckBox.isEnabled)
      dialog.type = "string"
      assertTrue(dialog.dialogUI.myNullableCheckBox.isEnabled)

      for (t in listOf("integer", "long", "boolean", "reference", null)) {
        dialog.type = t
        assertFalse(dialog.dialogUI.myNullableCheckBox.isEnabled)
      }
    }
  }

  fun testParcelable() {
    val model = model("nav.xml") {
      NavModelBuilderUtil.navigation {
        fragment("fragment1")
      }
    }

    val relativePath = "src/mytest/navtest/Containing.java"
    val fileText = """
      package mytest.navtest;

      import android.os.Parcelable;

      public abstract class Containing implements Parcelable {
          public abstract static class Inner implements Parcelable {
             public abstract static class InnerInner implements Parcelable {
             }
          }
      }
      """.trimIndent()
    myFixture.addFileToProject(relativePath, fileText)

    val parcelable = ClassUtil.findPsiClass(PsiManager.getInstance(project), CLASS_PARCELABLE)
    val classChooserFactory = mock(TreeClassChooserFactory::class.java)
    val classChooser = mock(TreeClassChooser::class.java)
    `when`(classChooserFactory.createInheritanceClassChooser(any(), any(), eq(parcelable), isNull())).thenReturn(classChooser)
    IdeComponents(project).replaceProjectService(TreeClassChooserFactory::class.java, classChooserFactory)

    testParcelable(model, classChooser, "mytest.navtest.Containing")
    testParcelable(model, classChooser, "mytest.navtest.Containing\$Inner")
    testParcelable(model, classChooser, "mytest.navtest.Containing\$Inner\$InnerInner")
  }

  private fun testParcelable(model: SyncNlModel, classChooser: TreeClassChooser, jvmName: String) {
    val fragment1 = model.find("fragment1")!!
    val psiClass = ClassUtil.findPsiClass(PsiManager.getInstance(project), jvmName)

    `when`(classChooser.selected).thenReturn(psiClass)

    AddArgumentDialog(null, fragment1).runAndClose { dialog ->
      dialog.type = "foo"
      assertEquals(jvmName, dialog.type)
    }
  }

  fun testCancelCustomParcelable() {
    val model = model("nav.xml") {
      NavModelBuilderUtil.navigation {
        fragment("fragment1")
      }
    }
    val parcelable = ClassUtil.findPsiClass(PsiManager.getInstance(project), CLASS_PARCELABLE)
    val classChooserFactory = mock(TreeClassChooserFactory::class.java)
    val classChooser = mock(TreeClassChooser::class.java)
    `when`(classChooserFactory.createInheritanceClassChooser(any(), any(), eq(parcelable), isNull())).thenReturn(classChooser)
    `when`(classChooser.selected).thenReturn(null)
    IdeComponents(project).replaceProjectService(TreeClassChooserFactory::class.java, classChooserFactory)

    val fragment1 = model.find("fragment1")!!

    AddArgumentDialog(null, fragment1).runAndClose { dialog ->
      dialog.type = "foo"
      assertEquals(null, dialog.type)
    }

    AddArgumentDialog(null, fragment1).runAndClose { dialog ->
      dialog.type = "integer"
      dialog.type = "foo"
      assertEquals("integer", dialog.type)
    }
  }

  fun testPropertyChangeMetrics() {
    val model = model("nav.xml") {
      navigation("root") {
        fragment("f1")
      }
    }

    val f1 = model.find("f1")!!

    AddArgumentDialog(null, f1).runAndClose { dialog ->
      dialog.name = "myArgument"
      dialog.type = "long"
      dialog.defaultValue = "1234"

      TestNavUsageTracker.create(model).use { tracker ->
        dialog.save()
        verify(tracker).logEvent(NavEditorEvent.newBuilder()
                                   .setType(NavEditorEvent.NavEditorEventType.CHANGE_PROPERTY)
                                   .setPropertyInfo(NavPropertyInfo.newBuilder()
                                                      .setWasEmpty(true)
                                                      .setProperty(NavPropertyInfo.Property.NAME)
                                                      .setContainingTag(NavPropertyInfo.TagType.ARGUMENT_TAG))
                                   .setSource(NavEditorEvent.Source.PROPERTY_INSPECTOR).build())
        verify(tracker).logEvent(NavEditorEvent.newBuilder()
                                   .setType(NavEditorEvent.NavEditorEventType.CHANGE_PROPERTY)
                                   .setPropertyInfo(NavPropertyInfo.newBuilder()
                                                      .setWasEmpty(true)
                                                      .setProperty(NavPropertyInfo.Property.ARG_TYPE)
                                                      .setContainingTag(NavPropertyInfo.TagType.ARGUMENT_TAG))
                                   .setSource(NavEditorEvent.Source.PROPERTY_INSPECTOR).build())
        verify(tracker).logEvent(NavEditorEvent.newBuilder()
                                   .setType(NavEditorEvent.NavEditorEventType.CHANGE_PROPERTY)
                                   .setPropertyInfo(NavPropertyInfo.newBuilder()
                                                      .setWasEmpty(true)
                                                      .setProperty(NavPropertyInfo.Property.DEFAULT_VALUE)
                                                      .setContainingTag(NavPropertyInfo.TagType.ARGUMENT_TAG))
                                   .setSource(NavEditorEvent.Source.PROPERTY_INSPECTOR).build())
      }
    }
  }
}

private fun <T> any(): T = ArgumentMatchers.any() as T
private fun <T> eq(arg: T): T = ArgumentMatchers.eq(arg) as T
