/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.tools.idea.npw.template

import com.android.tools.idea.flags.StudioFlags
import com.android.tools.idea.npw.FormFactor
import com.android.tools.idea.npw.model.NewAndroidModuleModel
import com.android.tools.idea.npw.model.RenderTemplateModel
import com.android.tools.idea.wizard.template.Template
import com.google.common.annotations.VisibleForTesting
import com.intellij.openapi.vfs.VirtualFile

/**
 * Generate the wrapper wizard step that shows list of [templates] options.
 * For now templates are limited to ones that are supported by [fragmentGalleryStepMessageKeys], with
 * [FormFactor.MOBILE].
 */
class ChooseCustomFragmentTemplatesStep (
  moduleModel: NewAndroidModuleModel,
  renderModel: RenderTemplateModel,
  targetDirectory: VirtualFile,
  templates: List<Template>
) : ChooseGalleryItemStep(
  moduleModel, renderModel, FormFactor.MOBILE, targetDirectory,
  messageKeys = customSetupWizardMessageKeys,
  emptyItemLabel = ""
) {

  override val templateRenderers: List<TemplateRenderer>
  init {
    val newTemplateRenderers = sequence {
      if (StudioFlags.NPW_NEW_ACTIVITY_TEMPLATES.get()) {
        yieldAll(templates.map(::NewTemplateRenderer))
      }
    }

    templateRenderers = newTemplateRenderers.toList()
  }
}

internal val customSetupWizardMessageKeys = WizardGalleryItemsStepMessageKeys(
  "android.wizard.welcome.dialog.title",
  "android.wizard.config.component.title",
  "android.wizard.fragment.not.found",
  "android.wizard.fragment.invalid.min.sdk",
  "android.wizard.fragment.invalid.min.build",
  "android.wizard.fragment.invalid.androidx",
  "android.wizard.fragment.invalid.needs.kotlin"
)
