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

#pragma once

#include <cstdint>
#include <string>

#include "geom.h"

namespace screensharing {

// Native code analogue of the android.view.DisplayInfo class.
struct DisplayInfo {
  DisplayInfo();
  DisplayInfo(
      int32_t logical_width, int32_t logical_height, int32_t logical_density_dpi, int32_t rotation, int32_t layer_stack, int32_t flags,
      int32_t state);

  // Returns the display dimensions in the canonical orientation.
  Size NaturalSize() const {
    return logical_size.Rotated(-rotation);
  }

  bool IsOn() const {
    return state == STATE_ON || state == STATE_VR;
  }

  std::string ToDebugString() const;

  Size logical_size;
  int32_t logical_density_dpi;
  int32_t rotation;
  int32_t layer_stack;
  int32_t flags;
  int32_t state;

  // From frameworks/base/core/java/android/view/Display.java
  static constexpr int32_t FLAG_ROUND = 1 << 4;
  enum State { STATE_UNKNOWN = 0, STATE_OFF = 1, STATE_ON = 2, STATE_DOZE = 3, STATE_DOZE_SUSPEND = 4, STATE_VR = 5, STATE_ON_SUSPEND = 6 };
};

}  // namespace screensharing