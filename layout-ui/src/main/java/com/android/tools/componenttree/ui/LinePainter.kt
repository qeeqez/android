/*
 * Copyright (C) 2021 The Android Open Source Project
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
package com.android.tools.componenttree.ui

import com.intellij.ui.JBColor
import com.intellij.ui.scale.JBUIScale
import com.intellij.ui.tree.ui.Control
import com.intellij.ui.treeStructure.Tree
import java.awt.Component
import java.awt.Graphics
import javax.swing.tree.TreeModel

val LINES: Control.Painter = LinePainter(Control.Painter.DEFAULT)
val COMPACT_LINES: Control.Painter = LinePainter(Control.Painter.COMPACT)

class LinePainter(val basePainter: Control.Painter) : Control.Painter by basePainter {

  override fun paint(
    c: Component,
    g: Graphics,
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    control: Control,
    depth: Int,
    leaf: Boolean,
    expanded: Boolean,
    selected: Boolean
  ) {
    val tree = c as Tree
    val path = tree.getClosestPathForLocation(x + width / 2, y + height / 2) ?: return
    basePainter.paint(c, g, x, y, width, height, control, depth, leaf, expanded, selected)
    g.color = JBColor.GRAY

    val model = tree.model
    if ((depth == 0 || depth == 1 && !tree.isRootVisible) && !tree.showsRootHandles) {
      return
    }

    var node = path.lastPathComponent
    var parent = path.parentPath
    var lastNode = getLastOfMultipleChildren(model, parent.lastPathComponent)

    // Horizontal line:
    val indent = getControlOffset(control, 2, false) - getControlOffset(control, 1, false)
    val spaceForControlLine = indent - control.width / 2 - JBUIScale.scale(4)
    if (depth > 1 && lastNode != null && spaceForControlLine > JBUIScale.scale(4)) {
      val lineY = y + height / 2
      val leftX = x + getControlOffset(control, depth - 1, false) + control.width / 2
      val rightX = x + (if (leaf) getRendererOffset(control, depth, true) else getControlOffset(control, depth, false)) - JBUIScale.scale(4)
      if (leftX < rightX) {
        g.drawLine(leftX, lineY, rightX, lineY)
      }
    }

    // Vertical lines:
    var directChild = true
    var lineDepth = depth - 1
    while (parent != null && lineDepth > 0) {
      if (lastNode != null && (node !== lastNode || directChild)) {
        val xMid = x + getControlOffset(control, lineDepth, false) + control.width / 2
        val bottom = if (node === lastNode) y + height / 2 else y + height
        g.drawLine(xMid, y, xMid, bottom)
      }
      node = parent.lastPathComponent
      parent = parent.parentPath
      lastNode = getLastOfMultipleChildren(model, parent.lastPathComponent)
      directChild = false
      lineDepth--
    }
  }

  private fun getLastOfMultipleChildren(model: TreeModel, node: Any): Any? {
    val count = model.getChildCount(node)
    return if (count > 1) model.getChild(node, count - 1) else null
  }
}
