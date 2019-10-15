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
package com.android.tools.idea.emulator

import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.LayoutManager
import javax.swing.JPanel
import kotlin.math.max

class EmulatorLayoutManager(private val myEmulatorPanel: JPanel) : LayoutManager {
  override fun addLayoutComponent(name: String, comp: Component) {}

  override fun removeLayoutComponent(comp: Component) {}

  override fun preferredLayoutSize(parent: Container): Dimension {
    val parentWidth = parent.width
    val parentHeight = parent.height
    val padding = 8
    val preferredSize = myEmulatorPanel.preferredSize
    if (preferredSize.height == 0 || parentHeight == 0) {
      return minimumLayoutSize(parent)
    }
    val aspectRatio = preferredSize.width / preferredSize.height.toDouble()
    val newAspectRatio = parentWidth / parentHeight.toDouble()
    val newWidth: Int
    val newHeight: Int
    if (newAspectRatio > aspectRatio) {
      // Wider than necessary; use the same height and compute the width from the desired aspect ratio.
      newHeight = parentHeight
      newWidth = (parentHeight * aspectRatio).toInt()
    }
    else {
      // Taller than necessary; use the same width and compute the height from the desired aspect ratio
      newWidth = parentWidth
      newHeight = (parentWidth / aspectRatio).toInt()
    }
    return Dimension(max(0, newWidth - padding), max(0, newHeight - padding))
  }

  override fun minimumLayoutSize(parent: Container): Dimension {
    return Dimension(10, 10)
  }

  override fun layoutContainer(parent: Container) {
    val parentWidth = parent.width
    val parentHeight = parent.height
    val p = preferredLayoutSize(parent)
    myEmulatorPanel.size = p
    myEmulatorPanel.setLocation((parentWidth - p.width) / 2, (parentHeight - p.height) / 2)
  }
}
