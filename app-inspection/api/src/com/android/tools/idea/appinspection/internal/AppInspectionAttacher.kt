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
package com.android.tools.idea.appinspection.internal

import com.android.tools.idea.appinspection.api.ProcessDescriptor
import com.android.tools.idea.transport.TransportClient
import com.android.tools.profiler.proto.Common
import com.android.tools.profiler.proto.Transport
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

private const val MAX_RETRY_COUNT = 60

typealias AttachCallback = (Common.Stream, Common.Process) -> Unit

// TODO(b/143628758): This Discovery must be called only behind the flag SQLITE_APP_INSPECTOR_ENABLED
class AppInspectionAttacher(private val executor: ScheduledExecutorService, private val client: TransportClient) {
  /**
   * Gets all active transport connections and returns a map of streams to their processes.
   *
   * This function should be called inside an executor because it contains a synchronous rpc call.
   */
  private fun queryProcesses(): Map<Common.Stream, List<Common.Process>> {
    // Get all streams of all types.
    val request = Transport.GetEventGroupsRequest.newBuilder()
      .setStreamId(-1)  // DataStoreService.DATASTORE_RESERVED_STREAM_ID
      .setKind(Common.Event.Kind.STREAM)
      .build()
    val response = client.transportStub.getEventGroups(request)
    val streams = response.groupsList.filterNotEnded().mapNotNull { group ->
      group.lastEventOrNull { e -> e.hasStream() && e.stream.hasStreamConnected() }
    }.map { connectedEvent ->
      connectedEvent.stream.streamConnected.stream
    }.filter { stream ->
      stream.type == Common.Stream.Type.DEVICE
    }

    return streams.associateWith { stream ->
      val processRequest = Transport.GetEventGroupsRequest.newBuilder()
        .setStreamId(stream.streamId)
        .setKind(Common.Event.Kind.PROCESS)
        .build()
      val processResponse = client.transportStub.getEventGroups(processRequest)
      val processList = processResponse.groupsList.filterNotEnded().mapNotNull { processGroup ->
        processGroup.lastEventOrNull { e -> e.hasProcess() && e.process.hasProcessStarted() }
      }.map { aliveEvent ->
        aliveEvent.process.processStarted.process
      }
      processList
    }
  }

  /**
   * Attempt to connect to the specified [processDescriptor].
   *
   * The method called will retry itself up to MAX_RETRY_COUNT times.
   *
   * TODO(b/145303836): wrap in future or find some way to let caller know about errors.
   */
  fun attach(processDescriptor: ProcessDescriptor, callback: AttachCallback) {
    executor.execute { attachWithRetry(processDescriptor, callback, 0) }
  }

  private fun attachWithRetry(processDescriptor: ProcessDescriptor, callback: AttachCallback, timesAttempted: Int) {
    val processesMap = queryProcesses()
    for ((stream, processes) in processesMap) {
      if (processDescriptor.matchesDevice(stream.device)) {
        for (process in processes) {
          if (process.name == processDescriptor.applicationId) {
            callback(stream, process)
            return
          }
        }
      }
    }
    if (timesAttempted < MAX_RETRY_COUNT) {
      executor.schedule({ attachWithRetry(processDescriptor, callback, timesAttempted + 1) }, 1, TimeUnit.SECONDS)
    }
  }
}

/**
 * Helper method to return the last even in an EventGroup that matches the input condition.
 */
private fun Transport.EventGroup.lastEventOrNull(predicate: (Common.Event) -> Boolean): Common.Event? {
  return eventsList.lastOrNull { predicate(it) }
}

private fun List<Transport.EventGroup>.filterNotEnded(): List<Transport.EventGroup> {
  return filterNot { group -> group.getEvents(group.eventsCount - 1).isEnded }
}