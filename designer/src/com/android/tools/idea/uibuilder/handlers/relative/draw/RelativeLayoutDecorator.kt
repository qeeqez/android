/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.tools.idea.uibuilder.handlers.relative.draw

import com.android.SdkConstants.*
import com.android.internal.R.attr.path
import com.android.tools.idea.common.model.NlComponent
import com.android.tools.idea.common.scene.SceneComponent
import com.android.tools.idea.common.scene.SceneContext
import com.android.tools.idea.common.scene.decorator.SceneDecorator
import com.android.tools.idea.common.scene.draw.DisplayList
import com.android.tools.idea.common.scene.draw.DrawCommand
import com.android.tools.idea.uibuilder.graphics.NlDrawingStyle
import com.android.tools.idea.uibuilder.handlers.constraint.ConstraintUtilities
import com.android.tools.idea.uibuilder.handlers.constraint.draw.DrawConnection
import com.android.tools.idea.uibuilder.handlers.constraint.draw.DrawConnectionUtils
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.geom.Path2D

/**
 * The decorator for RelativeLayout
 * TODO: Add visibility options of RelativeLayout to tool bar
 */
class RelativeLayoutDecorator : SceneDecorator() {

  override fun buildListChildren(list: DisplayList, time: Long, sceneContext: SceneContext, component: SceneComponent) {
    val rect = Rectangle()
    component.fillRect(rect)
    val unClip = list.addClip(sceneContext, rect)

    val idMap = component.children.filter({ it.id != null }).associateBy { it.id }
    val connectionSet = mutableSetOf<Connection>()
    component.children.forEach { child ->
      val authNlComponent = child.authoritativeNlComponent

      if (authNlComponent.getLiveAndroidAttribute(ATTR_LAYOUT_CENTER_IN_PARENT) == VALUE_TRUE) {
        buildCenterInParentDecoration(list, sceneContext, time, component, child)
        child.buildDisplayList(time, list, sceneContext)
        return@forEach
      }

      // build vertical decorations
      if (authNlComponent.getLiveAndroidAttribute(ATTR_LAYOUT_CENTER_VERTICAL) == VALUE_TRUE) {
        buildCenterVerticalDecoration(list, sceneContext, time, component, child)
      }
      else {
        val attrValue = authNlComponent.getLiveAndroidAttribute(ATTR_LAYOUT_ALIGN_BASELINE)
        if (attrValue != null) {
          buildBaselineMarginDecoration(connectionSet, child, idMap)
        }
        else {
          buildTopMarginDecoration(connectionSet, component, child, idMap)
          buildBottomMarginDecoration(connectionSet, component, child, idMap)
        }
      }

      // build horizontal decorations
      if (authNlComponent.getLiveAndroidAttribute(ATTR_LAYOUT_CENTER_HORIZONTAL) == VALUE_TRUE) {
        buildCenterHorizontalDecoration(list, sceneContext, time, component, child)
      }
      else {
        buildLeftMarginDecoration(connectionSet, component, child, idMap)
        buildRightMarginDecoration(connectionSet, component, child, idMap)
      }
      child.buildDisplayList(time, list, sceneContext)
    }

    connectionSet.forEach { it.addDrawCommand(list, time, sceneContext, component) }

    list.add(unClip)
  }

  /**
   * Helper function to run lambda function when the parent aligning attribute exists and is enabled
   */
  private fun whenAlignParent(attr: String?, job: () -> Unit) {
    if (attr == VALUE_TRUE) {
      job()
    }
  }

  /**
   * Helper function to run lambda function when the (widget) aligning attribute exists and is legal
   */
  private fun whenAlignWidget(attr: String?, job: (String) -> Unit) {
    val sourceId = NlComponent.extractId(attr)
    if (sourceId != null) {
      job(sourceId)
    }
  }

  /**
   * Used to build the aligning decoration of left-hand side in Layout Editor.
   * When there are multiple attributes, the priority of rendering is alignParent > align(widget) > toRight(of Widget).
   */
  private fun buildLeftMarginDecoration(connectionSet: MutableSet<Connection>, parent: SceneComponent, child: SceneComponent,
                                        idMap: Map<String, SceneComponent>) {
    whenAlignParent(child.retrieveAlignParentLeftAttribute()) {
      connectionSet.add(Connection(child, EdgeSide.LEFT, parent, EdgeSide.LEFT))
      return@whenAlignParent
    }

    whenAlignWidget(child.retrieveAlignLeftAttribute()) {
      val source = idMap[it]
      if (source != null) {
        connectionSet.add(Connection(child, EdgeSide.LEFT, source, EdgeSide.LEFT))
        return@whenAlignWidget
      }
    }

    whenAlignWidget(child.retrieveToRightAttribute()) {
      val source = idMap[it]
      if (source != null) {
        connectionSet.add(Connection(child, EdgeSide.LEFT, source, EdgeSide.RIGHT))
        return@whenAlignWidget
      }
    }
  }

  /**
   * Used to build the aligning decoration of top side in Layout Editor.
   * When there are multiple attributes, the priority of rendering is alignParent > align(widget) > below(the Widget).
   */
  private fun buildTopMarginDecoration(connectionSet: MutableSet<Connection>, parent: SceneComponent, child: SceneComponent,
                                       idMap: Map<String, SceneComponent>) {
    whenAlignParent(child.retrieveAlignParentTopAttribute()) {
      connectionSet.add(Connection(child, EdgeSide.TOP, parent, EdgeSide.TOP))
      return@whenAlignParent
    }

    whenAlignWidget(child.retrieveAlignTopAttribute()) {
      val source = idMap[it]
      if (source != null) {
        connectionSet.add(Connection(child, EdgeSide.TOP, source, EdgeSide.TOP))
        return@whenAlignWidget
      }
    }

    whenAlignWidget(child.retrieveBelowAttribute()) {
      val source = idMap[it]
      if (source != null) {
        connectionSet.add(Connection(child, EdgeSide.TOP, source, EdgeSide.BOTTOM))
        return@whenAlignWidget
      }
    }
  }

  /**
   * Used to build the aligning decoration of right-hand side in Layout Editor.
   * When there are multiple attributes, the priority of rendering is alignParent > align(widget) > toLeft(of Widget).
   */
  private fun buildRightMarginDecoration(connectionSet: MutableSet<Connection>, parent: SceneComponent, child: SceneComponent,
                                         idMap: Map<String, SceneComponent>) {
    whenAlignParent(child.retrieveAlignParentRightAttribute()) {
      connectionSet.add(Connection(child, EdgeSide.RIGHT, parent, EdgeSide.RIGHT))
      return@whenAlignParent
    }

    whenAlignWidget(child.retrieveAlignRightAttribute()) {
      val source = idMap[it]
      if (source != null) {
        connectionSet.add(Connection(child, EdgeSide.RIGHT, source, EdgeSide.RIGHT))
        return@whenAlignWidget
      }
    }

    whenAlignWidget(child.retrieveToLeftAttribute()) {
      val source = idMap[it]
      if (source != null) {
        connectionSet.add(Connection(child, EdgeSide.RIGHT, source, EdgeSide.LEFT))
        return@whenAlignWidget
      }
    }
  }

  /**
   * Used to build the aligning decoration of bottom side in Layout Editor.
   * When there are multiple attributes, the priority of rendering is alignParent > align(widget) > above(the Widget).
   */
  private fun buildBottomMarginDecoration(connectionSet: MutableSet<Connection>, parent: SceneComponent, child: SceneComponent,
                                          idMap: Map<String, SceneComponent>) {
    whenAlignParent(child.retrieveAlignParentBottomAttribute()) {
      connectionSet.add(Connection(child, EdgeSide.BOTTOM, parent, EdgeSide.BOTTOM))
      return@whenAlignParent
    }

    whenAlignWidget(child.retrieveAlignBottomAttribute()) {
      val source = idMap[it]
      if (source != null) {
        connectionSet.add(Connection(child, EdgeSide.BOTTOM, source, EdgeSide.BOTTOM))
        return@whenAlignWidget
      }
    }

    whenAlignWidget(child.retrieveAboveAttribute()) {
      val source = idMap[it]
      if (source != null) {
        connectionSet.add(Connection(child, EdgeSide.BOTTOM, source, EdgeSide.TOP))
        return@whenAlignWidget
      }
    }
  }

  /**
   * Used to build the aligning decoration of baseline in Layout Editor.
   */
  private fun buildBaselineMarginDecoration(connectionSet: MutableSet<Connection>, child: SceneComponent,
                                            idMap: Map<String, SceneComponent>) {
    whenAlignWidget(child.authoritativeNlComponent.getLiveAndroidAttribute(ATTR_LAYOUT_ALIGN_BASELINE)) {
      val source = idMap[it]
      if (source != null) {
        val connection = Connection(child, EdgeSide.BASELINE, source, EdgeSide.BASELINE)
        connectionSet.add(connection)
      }
    }
  }

  private fun buildCenterHorizontalDecoration(list: DisplayList, sceneContext: SceneContext, time: Long,
                                              parent: SceneComponent, child: SceneComponent) {
    list.add(HorizontalZigZagLineCommand(sceneContext, parent.getLeft(time), child.getLeft(time), child.getDrawCenterY(time)))
    list.add(HorizontalZigZagLineCommand(sceneContext, child.getRight(time), parent.getRight(time), child.getDrawCenterY(time)))
  }

  private fun buildCenterVerticalDecoration(list: DisplayList, sceneContext: SceneContext, time: Long,
                                            parent: SceneComponent, child: SceneComponent) {
    list.add(VerticalZigZagLineCommand(sceneContext, child.getDrawCenterX(time), parent.getTop(time), child.getTop(time)))
    list.add(VerticalZigZagLineCommand(sceneContext, child.getDrawCenterX(time), child.getBottom(time), parent.getBottom(time)))
  }

  private fun buildCenterInParentDecoration(list: DisplayList, sceneContext: SceneContext, time: Long,
                                            parent: SceneComponent, child: SceneComponent) {
    list.add(HorizontalZigZagLineCommand(sceneContext, parent.getLeft(time), child.getLeft(time), child.getDrawCenterY(time)))
    list.add(HorizontalZigZagLineCommand(sceneContext, child.getRight(time), parent.getRight(time), child.getDrawCenterY(time)))
    list.add(VerticalZigZagLineCommand(sceneContext, child.getDrawCenterX(time), parent.getTop(time), child.getTop(time)))
    list.add(VerticalZigZagLineCommand(sceneContext, child.getDrawCenterX(time), child.getBottom(time), parent.getBottom(time)))
  }
}

// Helper functions to get the coordinates of SceneComponent
private fun SceneComponent.getLeft(time: Long) = getDrawX(time)
private fun SceneComponent.getTop(time: Long) = getDrawY(time)
private fun SceneComponent.getRight(time: Long) = getDrawX(time) + getDrawWidth(time)
private fun SceneComponent.getBottom(time: Long) = getDrawY(time) + getDrawHeight(time)
private fun SceneComponent.getDrawCenterX(time: Long) = getDrawX(time) + getDrawWidth(time) / 2
private fun SceneComponent.getDrawCenterY(time: Long) = getDrawY(time) + getDrawHeight(time) / 2

// Helper functions to retrieve the aligning attributes. These functions also handle the rtl case. TODO: refactor to utility class
private fun NlComponent.getLiveAndroidAttribute(androidAttribute: String) = getLiveAttribute(ANDROID_URI, androidAttribute)

private fun SceneComponent.retrieveAlignParentLeftAttribute(): String? =
    authoritativeNlComponent.getLiveAndroidAttribute(if (scene.isInRTL) ATTR_LAYOUT_ALIGN_PARENT_END else ATTR_LAYOUT_ALIGN_PARENT_START) ?:
        authoritativeNlComponent.getLiveAndroidAttribute(ATTR_LAYOUT_ALIGN_PARENT_LEFT)

private fun SceneComponent.retrieveAlignParentTopAttribute(): String? =
    authoritativeNlComponent.getLiveAndroidAttribute(ATTR_LAYOUT_ALIGN_PARENT_TOP)

private fun SceneComponent.retrieveAlignParentRightAttribute(): String? =
    authoritativeNlComponent.getLiveAndroidAttribute(if (scene.isInRTL) ATTR_LAYOUT_ALIGN_PARENT_START else ATTR_LAYOUT_ALIGN_PARENT_END) ?:
        authoritativeNlComponent.getLiveAndroidAttribute(ATTR_LAYOUT_ALIGN_PARENT_RIGHT)

private fun SceneComponent.retrieveAlignParentBottomAttribute(): String? =
    authoritativeNlComponent.getLiveAndroidAttribute(ATTR_LAYOUT_ALIGN_PARENT_BOTTOM)

private fun SceneComponent.retrieveAlignLeftAttribute(): String? =
    authoritativeNlComponent.getLiveAndroidAttribute(if (scene.isInRTL) ATTR_LAYOUT_ALIGN_END else ATTR_LAYOUT_ALIGN_START) ?:
        authoritativeNlComponent.getLiveAndroidAttribute(ATTR_LAYOUT_ALIGN_LEFT)

private fun SceneComponent.retrieveAlignTopAttribute(): String? = authoritativeNlComponent.getLiveAndroidAttribute(ATTR_LAYOUT_ALIGN_TOP)

private fun SceneComponent.retrieveAlignRightAttribute(): String? =
    authoritativeNlComponent.getLiveAndroidAttribute(if (scene.isInRTL) ATTR_LAYOUT_ALIGN_START else ATTR_LAYOUT_ALIGN_END) ?:
        authoritativeNlComponent.getLiveAndroidAttribute(ATTR_LAYOUT_ALIGN_RIGHT)

private fun SceneComponent.retrieveAlignBottomAttribute(): String? =
    authoritativeNlComponent.getLiveAndroidAttribute(ATTR_LAYOUT_ALIGN_BOTTOM)

private fun SceneComponent.retrieveToLeftAttribute(): String? =
    authoritativeNlComponent.getLiveAndroidAttribute(if (scene.isInRTL) ATTR_LAYOUT_TO_END_OF else ATTR_LAYOUT_TO_START_OF) ?:
        authoritativeNlComponent.getLiveAndroidAttribute(ATTR_LAYOUT_TO_LEFT_OF)

private fun SceneComponent.retrieveBelowAttribute(): String? = authoritativeNlComponent.getLiveAndroidAttribute(ATTR_LAYOUT_BELOW)

private fun SceneComponent.retrieveToRightAttribute(): String? =
    authoritativeNlComponent.getLiveAndroidAttribute(if (scene.isInRTL) ATTR_LAYOUT_TO_START_OF else ATTR_LAYOUT_TO_END_OF) ?:
        authoritativeNlComponent.getLiveAndroidAttribute(ATTR_LAYOUT_TO_RIGHT_OF)

private fun SceneComponent.retrieveAboveAttribute(): String? = authoritativeNlComponent.getLiveAndroidAttribute(ATTR_LAYOUT_ABOVE)

private fun SceneComponent.retrieveLeftMarginAttribute(): String? =
    authoritativeNlComponent.getLiveAndroidAttribute(ATTR_LAYOUT_MARGIN) ?:
        authoritativeNlComponent.getLiveAndroidAttribute(if (scene.isInRTL) ATTR_LAYOUT_MARGIN_END else ATTR_LAYOUT_MARGIN_START) ?:
        authoritativeNlComponent.getLiveAndroidAttribute(ATTR_LAYOUT_MARGIN_LEFT)

private fun SceneComponent.retrieveTopMarginAttribute(): String? =
    authoritativeNlComponent.getLiveAndroidAttribute(ATTR_LAYOUT_MARGIN) ?:
        authoritativeNlComponent.getLiveAndroidAttribute(ATTR_LAYOUT_MARGIN_TOP)

private fun SceneComponent.retrieveRightMarginAttribute(): String? =
    authoritativeNlComponent.getLiveAndroidAttribute(ATTR_LAYOUT_MARGIN) ?:
        authoritativeNlComponent.getLiveAndroidAttribute(if (scene.isInRTL) ATTR_LAYOUT_MARGIN_START else ATTR_LAYOUT_MARGIN_END) ?:
        authoritativeNlComponent.getLiveAndroidAttribute(ATTR_LAYOUT_MARGIN_RIGHT)

private fun SceneComponent.retrieveBottomMarginAttribute(): String? =
    authoritativeNlComponent.getLiveAndroidAttribute(ATTR_LAYOUT_MARGIN) ?:
        authoritativeNlComponent.getLiveAndroidAttribute(ATTR_LAYOUT_MARGIN_BOTTOM)

private enum class EdgeSide { LEFT, TOP, RIGHT, BOTTOM, BASELINE }

/**
 * Helper class to record and generate the connection between Widgets
 * @param component the widget aligns to [source]
 * @param componentSide the aligning side of [component]
 * @param source the widget is aligned by [component]
 * @param sourceSide the side of [source] which is aligned by [component]
 */
private class Connection(val component: SceneComponent, val componentSide: EdgeSide, val source: SceneComponent, val sourceSide: EdgeSide) {
  fun addDrawCommand(list: DisplayList, time: Long, sceneContext: SceneContext, parent: SceneComponent) {
    when (sourceSide) {
      EdgeSide.LEFT, EdgeSide.RIGHT -> {
        val arrowX1 = if (componentSide == EdgeSide.LEFT) component.getLeft(time) else component.getRight(time)
        val arrowX2 = if (sourceSide == EdgeSide.LEFT) source.getLeft(time) else source.getRight(time)
        val arrowY = component.getDrawCenterY(time)

        val margin =
            if (componentSide == EdgeSide.LEFT) component.retrieveLeftMarginAttribute()
            else component.retrieveRightMarginAttribute()
        val isReference = margin?.startsWith("@") ?: false
        val marginDp = ConstraintUtilities.getDpValue(component.authoritativeNlComponent, margin)
        val displayedMarginText = if (marginDp != 0) "$marginDp" else ""

        list.add(DrawHorizontalArrowCommand(sceneContext, arrowX1, arrowX2, arrowY, isReference, displayedMarginText))

        if (source != parent) {
          // Draw the extented dash line from edge of source
          val lineY1 = minOf(source.getTop(time), component.getTop(time))
          val lineY2 = maxOf(source.getBottom(time), component.getBottom(time))
          list.add(DrawVerticalDashedLineCommand(sceneContext, arrowX2, lineY1, lineY2))
        }
      }
      EdgeSide.TOP, EdgeSide.BOTTOM -> {
        val arrowX = component.getDrawCenterX(time)
        val arrowY1 = if (componentSide == EdgeSide.TOP) component.getTop(time) else component.getBottom(time)
        val arrowY2 = if (sourceSide == EdgeSide.TOP) source.getTop(time) else source.getBottom(time)

        val margin =
            if (componentSide == EdgeSide.TOP) component.retrieveTopMarginAttribute()
            else component.retrieveBottomMarginAttribute()
        val isReference = margin?.startsWith("@") ?: false
        val marginDp = ConstraintUtilities.getDpValue(component.authoritativeNlComponent, margin)
        val displayedMarginText = if (marginDp != 0) "$marginDp" else ""

        list.add(DrawVerticalArrowCommand(sceneContext, arrowX, arrowY1, arrowY2, isReference, displayedMarginText))

        if (source != parent) {
          // Draw the extended dash line from edge of source
          val lineX1 = minOf(source.getLeft(time), component.getLeft(time))
          val lineX2 = maxOf(source.getRight(time), component.getRight(time))
          list.add(DrawHorizontalDashedLineCommand(sceneContext, lineX1, lineX2, arrowY2))
        }
      }
      EdgeSide.BASELINE -> {
        val lineY = source.getDrawY(time) + source.baseline
        val lineX1 = minOf(source.getLeft(time), component.getLeft(time))
        val lineX2 = maxOf(source.getRight(time), component.getRight(time))
        list.add(DrawHorizontalDashedLineCommand(sceneContext, lineX1, lineX2, lineY))
      }
    }
  }
}

// Helper functions to avoid repeating SceneContext.getSwingX(x.toFloat()) and SceneContext.getSwingY(y.toFloat())
private fun SceneContext.getSwingX(x: Int) = getSwingX(x.toFloat())
private fun SceneContext.getSwingY(y: Int) = getSwingY(y.toFloat())

private abstract class ZigZagLineCommand : DrawCommand {
  protected val path = Path2D.Float()

  override fun paint(g: Graphics2D, sceneContext: SceneContext) {
    val originalColor = g.color
    g.color = sceneContext.colorSet.constraints
    g.draw(path)
    g.color = originalColor
  }

  override fun getLevel(): Int = DrawCommand.CONNECTION_LEVEL

  override fun serialize(): String = "${javaClass.name} - $path"
}

private class VerticalZigZagLineCommand(context: SceneContext, x: Int, y1: Int, y2: Int) : ZigZagLineCommand() {
  init {
    val swingX = context.getSwingX(x.toFloat())
    val swingY1 = context.getSwingY(y1.toFloat())
    val swingY2 = context.getSwingY(y2.toFloat())
    DrawConnectionUtils.drawVerticalZigZagLine(path, swingX, swingY1, swingY2)
  }
}

private class HorizontalZigZagLineCommand(context: SceneContext, x1: Int, x2: Int, y: Int) : ZigZagLineCommand() {
  init {
    val swingX1 = context.getSwingX(x1.toFloat())
    val swingX2 = context.getSwingX(x2.toFloat())
    val swingY = context.getSwingY(y.toFloat())
    DrawConnectionUtils.drawHorizontalZigZagLine(path, swingX1, swingX2, swingY)
  }
}

// TODO: refactr vertical and horizontal arrow drawing commands.
private class DrawVerticalArrowCommand(sceneContext: SceneContext, x: Int, y1: Int, y2: Int,
                                       val isReference: Boolean, val text: String) : DrawCommand {
  private val swingX = sceneContext.getSwingX(x.toFloat())
  private val swingY1 = sceneContext.getSwingY(y1.toFloat())
  private val swingY2 = sceneContext.getSwingY(y2.toFloat())

  private val paintImpl: (Graphics2D, SceneContext) -> Unit

  init {
    if (y1 == y2) {
      paintImpl = { _, _ -> Unit }
    }
    else {
      val arrowX = IntArray(3)
      val arrowY = IntArray(3)

      val direction = if (y1 < y2) DrawConnection.DIR_TOP else DrawConnection.DIR_BOTTOM
      DrawConnectionUtils.getArrow(direction, swingX, swingY2, arrowX, arrowY)

      paintImpl = { g: Graphics2D, context: SceneContext ->
        val originalColor = g.color
        g.color = context.colorSet.constraints
        DrawConnectionUtils.drawVerticalMargin(g, text, isReference, swingX, swingY1, swingY2)
        g.fillPolygon(arrowX, arrowY, 3)
        g.color= originalColor
      }
    }
  }

  override fun paint(g: Graphics2D, sceneContext: SceneContext) = paintImpl(g, sceneContext)

  override fun getLevel(): Int = DrawCommand.CONNECTION_LEVEL

  override fun serialize(): String = "${javaClass.name} - ($swingX, $swingY1, $swingY2)"
}

private class DrawHorizontalArrowCommand(sceneContext: SceneContext, x1: Int, x2: Int, y: Int,
                                         val isReference: Boolean, val text: String) : DrawCommand {
  private val swingX1 = sceneContext.getSwingX(x1.toFloat())
  private val swingX2 = sceneContext.getSwingX(x2.toFloat())
  private val swingY = sceneContext.getSwingY(y.toFloat())

  private val paintImpl: (Graphics2D, SceneContext) -> Unit

  init {
    if (x1 == x2) {
      paintImpl = { _, _ ->Unit }
    }
    else {
      val arrowX = IntArray(3)
      val arrowY = IntArray(3)

      val direction = if (x1 < x2) DrawConnection.DIR_LEFT else DrawConnection.DIR_RIGHT
      DrawConnectionUtils.getArrow(direction, swingX2, swingY, arrowX, arrowY)

      paintImpl = { g: Graphics2D, context: SceneContext ->
        val originalColor = g.color
        g.color = context.colorSet.constraints
        DrawConnectionUtils.drawHorizontalMargin(g, text, isReference, swingX1, swingX2, swingY)
        g.fillPolygon(arrowX, arrowY, 3)
        g.color = originalColor
      }
    }
  }

  override fun paint(g: Graphics2D, sceneContext: SceneContext) = paintImpl(g, sceneContext)

  override fun getLevel(): Int = DrawCommand.CONNECTION_LEVEL

  override fun serialize(): String = "${javaClass.name} - ($swingX1, $swingY, $swingY)"
}

private open class DrawDashedLineCommand(val swingX1: Int, val swingY1: Int, val swingX2: Int, val swingY2: Int) : DrawCommand {
  override fun paint(g: Graphics2D, sceneContext: SceneContext) {
    val originalStroke = g.stroke
    val originalColor = g.color

    g.color = sceneContext.colorSet.constraints
    g.stroke = NlDrawingStyle.PATTERN_STROKE
    g.drawLine(swingX1, swingY1, swingX2, swingY2)

    g.stroke = originalStroke
    g.color = originalColor
  }

  override fun getLevel(): Int = DrawCommand.CONNECTION_LEVEL

  override fun serialize(): String = "${javaClass.name}: ($swingX1, $swingY1) - ($swingX2, $swingY2)"
}

private class DrawVerticalDashedLineCommand(sceneContext: SceneContext, x: Int, y1: Int, y2: Int)
  : DrawDashedLineCommand(sceneContext.getSwingX(x), sceneContext.getSwingY(y1), sceneContext.getSwingX(x), sceneContext.getSwingY(y2))

private class DrawHorizontalDashedLineCommand(sceneContext: SceneContext, x1: Int, x2: Int, y: Int)
  : DrawDashedLineCommand(sceneContext.getSwingX(x1), sceneContext.getSwingY(y), sceneContext.getSwingX(x2), sceneContext.getSwingY(y))
