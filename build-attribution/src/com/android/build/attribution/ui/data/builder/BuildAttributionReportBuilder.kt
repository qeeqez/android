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
package com.android.build.attribution.ui.data.builder

import com.android.build.attribution.analyzers.BuildEventsAnalysisResult
import com.android.build.attribution.data.PluginBuildData
import com.android.build.attribution.data.TaskData
import com.android.build.attribution.ui.data.BuildAttributionReportUiData
import com.android.build.attribution.ui.data.BuildSummary
import com.android.build.attribution.ui.data.ConfigurationUiData
import com.android.build.attribution.ui.data.CriticalPathPluginTasksUiData
import com.android.build.attribution.ui.data.CriticalPathPluginUiData
import com.android.build.attribution.ui.data.CriticalPathPluginsUiData
import com.android.build.attribution.ui.data.CriticalPathTasksUiData
import com.android.build.attribution.ui.data.IssueLevel
import com.android.build.attribution.ui.data.TimeWithPercentage


/**
 * A Builder class for a data structure holding the data gathered by Gradle build analyzers.
 * The data structure of the report is described in UiDataModel.kt
 */
class BuildAttributionReportBuilder(
  val buildAnalysisResult: BuildEventsAnalysisResult,
  val buildFinishedTimestamp: Long
) {

  private val issueUiDataContainer: TaskIssueUiDataContainer = TaskIssueUiDataContainer(buildAnalysisResult)
  private val taskUiDataContainer: TaskUiDataContainer = TaskUiDataContainer(buildAnalysisResult, issueUiDataContainer)

  fun build(): BuildAttributionReportUiData {
    issueUiDataContainer.populate(taskUiDataContainer)
    val pluginConfigurationTimeReport = ConfigurationTimesUiDataBuilder(buildAnalysisResult).build()
    val buildSummary = createBuildSummary(pluginConfigurationTimeReport)
    return object : BuildAttributionReportUiData {
      override val buildSummary: BuildSummary = buildSummary
      override val criticalPathTasks = createCriticalPathTasks(buildSummary.criticalPathDuration)
      override val criticalPathPlugins = createCriticalPathPlugins(buildSummary.criticalPathDuration)
      override val issues = issueUiDataContainer.allIssueGroups()
      override val configurationTime = pluginConfigurationTimeReport
      override val annotationProcessors = AnnotationProcessorsReportBuilder(buildAnalysisResult).build()
    }
  }

  private fun createBuildSummary(pluginConfigurationTimeReport: ConfigurationUiData) = object : BuildSummary {
    override val buildFinishedTimestamp = this@BuildAttributionReportBuilder.buildFinishedTimestamp
    override val totalBuildDuration = TimeWithPercentage(buildAnalysisResult.getTotalBuildTimeMs(), buildAnalysisResult.getTotalBuildTimeMs())
    override val criticalPathDuration = TimeWithPercentage(buildAnalysisResult.getCriticalPathDurationMs(), buildAnalysisResult.getTotalBuildTimeMs())
    override val configurationDuration = pluginConfigurationTimeReport.totalConfigurationTime
  }

  private fun createCriticalPathTasks(criticalPathDuration: TimeWithPercentage) = object : CriticalPathTasksUiData {
    override val criticalPathDuration = criticalPathDuration
    override val miscStepsTime = criticalPathDuration.supplement()
    override val tasks = buildAnalysisResult.getCriticalPathTasks()
      .map { taskUiDataContainer.getByTaskData(it) }
      .sortedByDescending { it.executionTime }
    override val warningCount = tasks.flatMap { it.issues }.count { it.type.level == IssueLevel.WARNING }
    override val infoCount = tasks.flatMap { it.issues }.count { it.type.level == IssueLevel.INFO }
  }

  private fun createCriticalPathPlugins(criticalPathDuration: TimeWithPercentage): CriticalPathPluginsUiData {
    val taskByPlugin = buildAnalysisResult.getCriticalPathTasks().groupBy { it.originPlugin }
    return object : CriticalPathPluginsUiData {
      override val criticalPathDuration = criticalPathDuration
      override val miscStepsTime = criticalPathDuration.supplement()
      override val plugins = buildAnalysisResult.getCriticalPathPlugins()
        .map {
          createCriticalPathPluginUiData(taskByPlugin[it.plugin].orEmpty(), it)
        }
        .sortedByDescending { it.criticalPathDuration }
      override val warningCount = plugins.sumBy { it.warningCount }
      override val infoCount = plugins.sumBy { it.infoCount }
    }
  }

  private fun createCriticalPathPluginUiData(
    criticalPathTasks: List<TaskData>,
    pluginCriticalPathBuildData: PluginBuildData
  ) = object : CriticalPathPluginUiData {
    override val name = pluginCriticalPathBuildData.plugin.displayName
    override val criticalPathDuration = TimeWithPercentage(pluginCriticalPathBuildData.buildDuration, buildAnalysisResult.getTotalBuildTimeMs())
    override val criticalPathTasks = createPluginTasksCriticalPath(criticalPathTasks, criticalPathDuration)
    override val issues = issueUiDataContainer.pluginIssueGroups(pluginCriticalPathBuildData.plugin)
    override val warningCount = issues.sumBy { it.warningCount }
    override val infoCount = issues.sumBy { it.infoCount }
  }

  private fun createPluginTasksCriticalPath(criticalPathTasks: List<TaskData>, criticalPathDuration: TimeWithPercentage) =
    object : CriticalPathPluginTasksUiData {
      override val criticalPathDuration = criticalPathDuration
      override val tasks = criticalPathTasks.map { taskUiDataContainer.getByTaskData(it) }
      override val warningCount = tasks.flatMap { it.issues }.count { it.type.level == IssueLevel.WARNING }
      override val infoCount = tasks.flatMap { it.issues }.count { it.type.level == IssueLevel.INFO }
    }
}