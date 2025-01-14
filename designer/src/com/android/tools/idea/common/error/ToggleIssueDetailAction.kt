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
package com.android.tools.idea.common.error

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.actionSystem.ToggleAction

class ToggleIssueDetailAction : ToggleAction() {

  override fun update(e: AnActionEvent) {
    e.presentation.text = "Show Issue Detail"
    e.presentation.isVisible = true
    val issuePanel =
      e.project?.let { IssuePanelService.getInstance(it).getSelectedSharedIssuePanel() }
    if (issuePanel == null) {
      e.presentation.isEnabled = false
      return
    }
    val node = e.dataContext.getData(PlatformDataKeys.SELECTED_ITEM) as? IssueNode
    e.presentation.isEnabled = node != null
    super.update(e)
  }

  override fun isSelected(e: AnActionEvent): Boolean {
    val issuePanel =
      e.project?.let { IssuePanelService.getInstance(it).getSelectedSharedIssuePanel() }
    return issuePanel?.sidePanelVisible ?: false
  }

  override fun setSelected(e: AnActionEvent, state: Boolean) {
    val issuePanel =
      e.project?.let { IssuePanelService.getInstance(it).getSelectedSharedIssuePanel() } ?: return
    issuePanel.sidePanelVisible = state
  }
}
