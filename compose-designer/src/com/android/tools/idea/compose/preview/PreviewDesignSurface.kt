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

import com.android.tools.idea.common.model.NopSelectionModel
import com.android.tools.idea.common.surface.DesignSurface
import com.android.tools.idea.common.surface.InteractionHandler
import com.android.tools.idea.compose.preview.actions.PreviewSurfaceActionManager
import com.android.tools.idea.compose.preview.scene.COMPOSE_SCREEN_VIEW_PROVIDER
import com.android.tools.idea.compose.preview.scene.ComposeSceneComponentProvider
import com.android.tools.idea.compose.preview.scene.ComposeSceneUpdateListener
import com.android.tools.idea.flags.StudioFlags
import com.android.tools.idea.uibuilder.actions.SurfaceLayoutManagerOption
import com.android.tools.idea.uibuilder.graphics.NlConstants
import com.android.tools.idea.uibuilder.scene.LayoutlibSceneManager
import com.android.tools.idea.uibuilder.scene.RealTimeSessionClock
import com.android.tools.idea.uibuilder.surface.NavigationHandler
import com.android.tools.idea.uibuilder.surface.NlDesignSurface
import com.android.tools.idea.uibuilder.surface.NlSupportedActions
import com.android.tools.idea.uibuilder.surface.layout.GridSurfaceLayoutManager
import com.android.tools.idea.uibuilder.surface.layout.GroupedGridSurfaceLayoutManager
import com.android.tools.idea.uibuilder.surface.layout.PositionableContent
import com.android.tools.idea.uibuilder.surface.layout.SingleDirectionLayoutManager
import com.android.tools.idea.uibuilder.surface.layout.VerticalOnlyLayoutManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.DataProvider
import com.intellij.openapi.project.Project

/** List of available layouts for the Compose Preview Surface. */
internal val PREVIEW_LAYOUT_MANAGER_OPTIONS =
  listOfNotNull(
    SurfaceLayoutManagerOption(
      message("vertical.layout"),
      VerticalOnlyLayoutManager(
        NlConstants.DEFAULT_SCREEN_OFFSET_X,
        NlConstants.DEFAULT_SCREEN_OFFSET_Y,
        NlConstants.SCREEN_DELTA,
        NlConstants.SCREEN_DELTA,
        SingleDirectionLayoutManager.Alignment.CENTER
      )
    ),
    SurfaceLayoutManagerOption(
      message("grid.layout"),
      GridSurfaceLayoutManager(
        NlConstants.DEFAULT_SCREEN_OFFSET_X,
        NlConstants.DEFAULT_SCREEN_OFFSET_Y,
        NlConstants.SCREEN_DELTA,
        NlConstants.SCREEN_DELTA
      ),
      DesignSurface.SceneViewAlignment.LEFT
    ),
    if (StudioFlags.COMPOSE_NEW_PREVIEW_LAYOUT.get())
      SurfaceLayoutManagerOption(
        "Group Grid Layout (By Group name)",
        GroupedGridSurfaceLayoutManager(30, 5) { contents: Collection<PositionableContent> ->
          val groups = mutableMapOf<String?, MutableList<PositionableContent>>()
          for (content in contents) {
            groups.getOrPut(content.groupId) { mutableListOf() }.add(content)
          }
          // Put the previews which doesn't have group first.
          // TODO(b/245363234)?: Consider to sort the group by name?
          val nulls = groups.remove(null)
          if (nulls != null) listOf(nulls) + groups.values.toList() else groups.values.toList()
        },
        DesignSurface.SceneViewAlignment.LEFT
      )
    else null,
  )

/** Default layout manager selected in the preview. */
internal val DEFAULT_PREVIEW_LAYOUT_MANAGER = PREVIEW_LAYOUT_MANAGER_OPTIONS.first().layoutManager

private val COMPOSE_SUPPORTED_ACTIONS =
  setOf(NlSupportedActions.SWITCH_DESIGN_MODE, NlSupportedActions.TOGGLE_ISSUE_PANEL)

/**
 * Creates a [NlDesignSurface.Builder] with a common setup for the design surfaces in Compose
 * preview.
 */
private fun createPreviewDesignSurfaceBuilder(
  project: Project,
  navigationHandler: NavigationHandler,
  delegateInteractionHandler: InteractionHandler,
  dataProvider: DataProvider,
  parentDisposable: Disposable,
  sceneComponentProvider: ComposeSceneComponentProvider
): NlDesignSurface.Builder =
  NlDesignSurface.builder(project, parentDisposable)
    .setNavigationHandler(navigationHandler)
    .setActionManagerProvider { surface -> PreviewSurfaceActionManager(surface) }
    .setInteractionHandlerProvider { delegateInteractionHandler }
    .setActionHandler { surface -> PreviewSurfaceActionHandler(surface) }
    .setSceneManagerProvider { surface, model ->
      // Compose Preview manages its own render and refresh logic, and then it should avoid
      // some automatic renderings triggered in LayoutLibSceneManager
      LayoutlibSceneManager(
          model,
          surface,
          sceneComponentProvider,
          ComposeSceneUpdateListener(),
        ) { RealTimeSessionClock() }
        .also {
          it.setListenResourceChange(false) // don't re-render on resource changes
          it.setUpdateAndRenderWhenActivated(false) // don't re-render on activation
        }
    }
    .setDelegateDataProvider(dataProvider)
    .setSelectionModel(NopSelectionModel)
    .setZoomControlsPolicy(DesignSurface.ZoomControlsPolicy.HIDDEN)
    .setSupportedActions(COMPOSE_SUPPORTED_ACTIONS)
    .setShouldRenderErrorsPanel(true)
    .setScreenViewProvider(COMPOSE_SCREEN_VIEW_PROVIDER, false)
    .setMaxFitIntoZoomLevel(2.0) // Set fit into limit to 200%

/** Creates a [NlDesignSurface.Builder] for the main design surface in the Compose preview. */
internal fun createMainDesignSurfaceBuilder(
  project: Project,
  navigationHandler: NavigationHandler,
  delegateInteractionHandler: InteractionHandler,
  dataProvider: DataProvider,
  parentDisposable: Disposable,
  sceneComponentProvider: ComposeSceneComponentProvider
) =
  createPreviewDesignSurfaceBuilder(
      project,
      navigationHandler,
      delegateInteractionHandler,
      dataProvider, // Will be overridden by the preview provider
      parentDisposable,
      sceneComponentProvider
    )
    .setLayoutManager(DEFAULT_PREVIEW_LAYOUT_MANAGER)

/** Creates a [NlDesignSurface.Builder] for the pinned design surface in the Compose preview. */
internal fun createPinnedDesignSurfaceBuilder(
  project: Project,
  navigationHandler: NavigationHandler,
  delegateInteractionHandler: InteractionHandler,
  dataProvider: DataProvider,
  parentDisposable: Disposable,
  sceneComponentProvider: ComposeSceneComponentProvider
) =
  createPreviewDesignSurfaceBuilder(
      project,
      navigationHandler,
      delegateInteractionHandler,
      dataProvider,
      parentDisposable,
      sceneComponentProvider
    )
    .setLayoutManager(
      GridSurfaceLayoutManager(
        NlConstants.DEFAULT_SCREEN_OFFSET_X,
        NlConstants.DEFAULT_SCREEN_OFFSET_Y,
        NlConstants.SCREEN_DELTA,
        NlConstants.SCREEN_DELTA
      )
    )
