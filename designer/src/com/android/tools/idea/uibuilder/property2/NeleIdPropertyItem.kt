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
package com.android.tools.idea.uibuilder.property2

import com.android.SdkConstants.ANDROID_URI
import com.android.SdkConstants.ATTR_ID
import com.android.SdkConstants.NEW_ID_PREFIX
import com.android.tools.adtui.model.stdui.EDITOR_NO_ERROR
import com.android.tools.adtui.model.stdui.EditingErrorCategory
import com.android.tools.idea.AndroidPsiUtils
import com.android.tools.idea.common.model.NlComponent
import com.android.tools.idea.flags.StudioFlags
import com.android.tools.idea.uibuilder.property2.support.NeleIdRenameProcessor
import com.android.tools.lint.detector.api.stripIdPrefix
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.refactoring.actions.RenameElementAction
import com.intellij.util.text.nullize
import org.jetbrains.android.dom.attrs.AttributeDefinition
import org.jetbrains.android.refactoring.renaming.NEW_NAME_RESOURCE

open class NeleIdPropertyItem(
  model: NelePropertiesModel,
  definition: AttributeDefinition?,
  componentName: String,
  components: List<NlComponent>,
  optionalValue1: Any? = null,
  optionalValue2: Any? = null
) : NelePropertyItem(ANDROID_URI, ATTR_ID, NelePropertyType.ID, definition, componentName, "", model,
                     listOf(components.first()), optionalValue1, optionalValue2) {

  // TODO(b/120919869): The snapshot value in NlComponent may be stale.
  // The snapshot stored in an NlComponent can get stale when something else
  // modifies the XML attributes directly i.e. not through an NlComponent.
  // The RenameProcessor used here is one example.
  // Workaround for now: read the attribute directly from the XmlAttribute.
  @Suppress("DEPRECATION")
  override val rawValue: String?
    get() = readIdFromPsi()

  override var value: String?
    get() = stripIdPrefix(super.value)
    set(value) {
      val undoManager = UndoManager.getInstance(project)
      if (undoManager.isUndoInProgress || undoManager.isRedoInProgress) {
        // b/134522901: Avoid updating the property during undo/redo
        return
      }
      val oldId = stripIdPrefix(super.value)
      val newId = stripIdPrefix(value)
      val newValue = toValue(newId)
      val tag = model.getPropertyTag(this)
      val attribute = if (tag != null && tag.isValid) tag.getAttribute(ATTR_ID, ANDROID_URI) else null
      val xmlValue = attribute?.valueElement

      if (!renameRefactoring(xmlValue, oldId, newId, newValue)) {
        super.value = newValue
      }
    }

  // The base implementation does not generate a meaningful tip for the value. Remove it here.
  override val tooltipForValue: String
    get() = ""

  private fun readIdFromPsi(): String? {
    val tag = firstTag ?: return null
    return if (AndroidPsiUtils.isValid(tag)) AndroidPsiUtils.getAttributeSafely(tag, ANDROID_URI, ATTR_ID) else null
  }

  private fun toValue(id: String): String? {
    return if (id.isNotEmpty() && !id.startsWith("@")) NEW_ID_PREFIX + id else id.nullize()
  }

  override fun getCompletionValues(): List<String> {
    return emptyList()
  }

  override fun validate(text: String?): Pair<EditingErrorCategory, String> {
    return lintValidation() ?: EDITOR_NO_ERROR
  }

  /**
   * Refactor the id value.
   *
   * @return true if the rename refactoring made the requred changes, false if the value must be set
   */
  protected open fun renameRefactoring(value: XmlAttributeValue?, oldId: String, newId: String, newValue: String?): Boolean {
    if (oldId.isEmpty() || newId.isEmpty() || newValue == null || value == null || !value.isValid) {
      return false
    }

    if (StudioFlags.RESOLVE_USING_REPOS.get()) {
      val action = RenameElementAction()
      val simpleContext = SimpleDataContext.getSimpleContext(
        mapOf(
          NEW_NAME_RESOURCE.name to newId,
          CommonDataKeys.PSI_FILE.name to value.containingFile,
          CommonDataKeys.PSI_ELEMENT.name to value,
          CommonDataKeys.PROJECT.name to value.project),
        null
      )
      ActionUtil.invokeAction(action, simpleContext, ActionPlaces.UNKNOWN, null, null)
    } else {
      // Exact replace only
      val project = model.facet.module.project
      val processor = NeleIdRenameProcessor(project, value, newValue)
      processor.run()
    }

    // The RenameProcessor will change the value of the ID here (may happen later if previewing first).
    return true
  }
}
