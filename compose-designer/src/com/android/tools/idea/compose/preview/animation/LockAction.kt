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
package com.android.tools.idea.compose.preview.animation

import com.android.tools.idea.compose.preview.animation.timeline.ElementState
import com.android.tools.idea.compose.preview.message
import com.google.wireless.android.sdk.stats.ComposeAnimationToolingEvent
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.ui.ToggleActionButton
import icons.StudioIcons
import java.util.function.Supplier


class LockAction(private val previewState: AnimationPreviewState,
                 val state: ElementState,
                 val tracker: ComposeAnimationEventTracker) : ToggleAction(
  Supplier { message("animation.inspector.action.lock") }, StudioIcons.Compose.Toolbar.FREEZE_ANIMATION) {

  override fun setSelected(e: AnActionEvent, locked: Boolean) {
    state.locked = locked
    if (locked) {
      tracker(ComposeAnimationToolingEvent.ComposeAnimationToolingEventType.LOCK_ANIMATION)
      e.presentation.text = message("animation.inspector.action.unlock")
    }
    else {
      tracker(ComposeAnimationToolingEvent.ComposeAnimationToolingEventType.UNLOCK_ANIMATION)
      e.presentation.text = message("animation.inspector.action.lock")
    }
  }

  override fun isSelected(e: AnActionEvent): Boolean {
    return state.locked
  }

  override fun update(e: AnActionEvent) {
    super.update(e)
    if (e.presentation.isEnabled != previewState.isCoordinationAvailable()) {
      e.presentation.isEnabled = previewState.isCoordinationAvailable()
      e.presentation.text = if (previewState.isCoordinationAvailable()) message("animation.inspector.action.lock")
      else message("animation.inspector.coordination.unavailable.lock.animation")
    }
  }
}