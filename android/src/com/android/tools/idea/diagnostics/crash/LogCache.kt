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
package com.android.tools.idea.diagnostics.crash

import org.apache.log4j.Level
import org.apache.log4j.Logger

class LogCache {
  private val buffers = HashMap<String, LogBuffer>()

  fun getLogBufferFor(name: String, maxLines: Int): LogBuffer {
    synchronized(buffers) {
      return buffers.getOrPut(name) { LogBuffer(maxLines) }
    }
  }

  fun getLogFor(name: String): String {
    synchronized(buffers) {
      return buffers[name]?.getLog().orEmpty()
    }
  }

  fun getLogAndClearFor(name: String): String {
    synchronized(buffers) {
      return buffers[name]?.getLogAndClear().orEmpty()
    }
  }

}