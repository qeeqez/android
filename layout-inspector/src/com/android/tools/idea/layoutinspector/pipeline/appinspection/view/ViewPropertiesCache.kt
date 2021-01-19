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
package com.android.tools.idea.layoutinspector.pipeline.appinspection.view

import com.android.tools.idea.concurrency.AndroidDispatchers
import com.android.tools.idea.layoutinspector.model.InspectorModel
import com.android.tools.idea.layoutinspector.model.ViewNode
import kotlinx.coroutines.withContext
import layoutinspector.view.inspection.LayoutInspectorViewProtocol.GetPropertiesResponse

/**
 * Cache of view properties, to avoid expensive refetches when possible.
 */
class ViewPropertiesCache(
  private val client: ViewLayoutInspectorClient,
  private val model: InspectorModel
) {

  /**
   * If true, allow fetching data from the device if we don't have it in our local cache.
   *
   * We provide this lever because sometimes the inspector is in snapshot mode, and we don't want
   * to pull data from the device that might be newer than what we see in our snapshot.
   */
  var allowFetching = false

  // Specifically, this is a Map<RootId, Map<ViewId, Data>>()
  // Occasionally, roots are discarded, so we can drop whole branches of cached data in that case.
  private val cache = mutableMapOf<Long, MutableMap<Long, ViewPropertiesData>>()

  /**
   * Remove all nested data for views that are children to [rootId].
   */
  fun clearFor(rootId: Long) {
    cache.remove(rootId)
  }

  /**
   * Remove all nested data for views that are not children under the passed in list of IDs.
   *
   * This is a useful method to call when an old root window is removed.
   */
  fun retain(rootIdsToKeep: Iterable<Long>) {
    cache.keys.removeAll { rootId -> !rootIdsToKeep.contains(rootId) }
  }

  /**
   * Request [ViewPropertiesData] cached against the passed in [view].
   *
   * This may initiate a fetch to device if the data is not locally cached already.
   *
   * This may also ultimately return null if the viewId is invalid (e.g. stale, and no longer found
   * inside the model).
   */
  suspend fun getDataFor(view: ViewNode): ViewPropertiesData? {
    val root = model.rootFor(view) ?: return null // Unrooted nodes are not supported
    val cached = cache[root.drawId]?.get(view.drawId)
      if (cached != null) {
        return cached
      }

    // Don't update the cache if we're not actively communicating with the inspector. Otherwise,
    // we might override values with those that don't match our last snapshot.
    if (!allowFetching) return null

    return withContext(AndroidDispatchers.workerThread) {
      updateCache(root.drawId, client.fetchProperties(view.drawId))
    }
  }

  private fun updateCache(rootId: Long, properties: GetPropertiesResponse): ViewPropertiesData? {
    if (properties.viewId == 0L) return null

    val data = ViewPropertiesDataGenerator(properties, model).generate()
    val innerMap = cache.computeIfAbsent(rootId) { mutableMapOf() }
    innerMap[properties.viewId] = data
    return data
  }
}
