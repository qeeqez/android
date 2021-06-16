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
package com.android.tools.profilers.cpu.analysis

import com.android.tools.adtui.PaginatedTableView
import com.android.tools.profilers.StudioProfilersView
import java.awt.BorderLayout

class CpuAnalysisFramesTab(profilersView: StudioProfilersView,
                           model: CpuAnalysisFramesTabModel
) : CpuAnalysisTab<CpuAnalysisFramesTabModel>(profilersView, model) {
  init {
    layout = BorderLayout()
    // TODO(b/186899372): support multi-layer apps.
    val tableView = PaginatedTableView(model.layerToTableModel.values.first(), PAGE_SIZE_VALUES)
    tableView.table.apply {
      showVerticalLines = true
      showHorizontalLines = true
      emptyText.text = "No frames in the selected range"
      columnModel.getColumn(FrameEventTableColumn.FRAME_NUMBER.ordinal).cellRenderer = CustomBorderTableCellRenderer()
      columnModel.getColumn(FrameEventTableColumn.TOTAL_TIME.ordinal).cellRenderer = DurationRenderer()
      columnModel.getColumn(FrameEventTableColumn.APP.ordinal).cellRenderer = DurationRenderer()
      columnModel.getColumn(FrameEventTableColumn.GPU.ordinal).cellRenderer = DurationRenderer()
      columnModel.getColumn(FrameEventTableColumn.COMPOSITION.ordinal).cellRenderer = DurationRenderer()
    }
    add(tableView.component)
  }

  companion object {
    val PAGE_SIZE_VALUES = arrayOf(10, 25, 50, 100)
  }
}