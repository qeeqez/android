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
package com.android.tools.idea.compose.preview.renderer

import com.android.ide.common.rendering.api.ViewInfo
import com.android.tools.idea.compose.preview.ComposeAdapterLightVirtualFile
import com.android.tools.idea.compose.preview.ComposePreviewElement
import com.android.tools.idea.compose.preview.ComposePreviewElementInstance
import com.android.tools.idea.compose.preview.applyTo
import com.android.tools.idea.rendering.createRenderTaskFuture
import com.android.tools.rendering.RenderResult
import com.android.tools.rendering.RenderTask
import com.google.common.annotations.VisibleForTesting
import com.intellij.util.concurrency.AppExecutorUtil
import org.jetbrains.android.facet.AndroidFacet
import java.awt.image.BufferedImage
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/**
 * Returns a [CompletableFuture] that creates a [RenderTask] for a single
 * [ComposePreviewElementInstance]. It is the responsibility of a client of this function to dispose
 * the resulting [RenderTask] when no loner needed.
 */
@VisibleForTesting
fun createRenderTaskFuture(
  facet: AndroidFacet,
  previewElement: ComposePreviewElementInstance,
  privateClassLoader: Boolean = false,
  classesToPreload: Collection<String> = emptyList(),
  customViewInfoParser: ((Any) -> List<ViewInfo>)? = null,
) =
  createRenderTaskFuture(
    facet,
    ComposeAdapterLightVirtualFile(
      "singlePreviewElement.xml",
      previewElement.toPreviewXml().buildString()
    ) {
      previewElement.previewElementDefinitionPsi?.virtualFile
    },
    privateClassLoader,
    classesToPreload,
    customViewInfoParser,
    previewElement::applyTo
  )

/**
 * Renders a single [ComposePreviewElement] and returns a [CompletableFuture] containing the result
 * or null if the preview could not be rendered. This method will render the element asynchronously
 * and will return immediately.
 */
@VisibleForTesting
fun renderPreviewElementForResult(
  facet: AndroidFacet,
  previewElement: ComposePreviewElementInstance,
  privateClassLoader: Boolean = false,
  customViewInfoParser: ((Any) -> List<ViewInfo>)? = null,
  executor: Executor = AppExecutorUtil.getAppExecutorService()
): CompletableFuture<RenderResult?> {
  val renderTaskFuture =
    createRenderTaskFuture(
      facet,
      previewElement,
      privateClassLoader,
      emptyList(),
      customViewInfoParser
    )

  val renderResultFuture =
    CompletableFuture.supplyAsync({ renderTaskFuture.get() }, executor)
      .thenCompose { it?.render() ?: CompletableFuture.completedFuture(null as RenderResult?) }
      .thenApply {
        if (
          it != null &&
            it.renderResult.isSuccess &&
            it.logger.brokenClasses.isEmpty() &&
            !it.logger.hasErrors()
        )
          it
        else null
      }

  renderResultFuture.handle { _, _ -> renderTaskFuture.get().dispose() }

  return renderResultFuture
}

/**
 * Renders a single [ComposePreviewElement] and returns a [CompletableFuture] containing the result
 * or null if the preview could not be rendered. This method will render the element asynchronously
 * and will return immediately.
 */
fun renderPreviewElement(
  facet: AndroidFacet,
  previewElement: ComposePreviewElementInstance
): CompletableFuture<BufferedImage?> {
  return renderPreviewElementForResult(facet, previewElement).thenApply { it?.renderedImage?.copy }
}
