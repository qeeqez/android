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
package com.android.build.attribution.analyzers

import com.android.build.attribution.data.GradlePluginsData
import com.android.build.attribution.data.PluginData
import com.android.build.attribution.data.StudioProvidedInfo
import com.android.ide.common.attribution.AndroidGradlePluginAttributionData
import com.android.ide.common.repository.GradleCoordinate
import com.android.ide.common.repository.GradleVersion

/** Minimal AGP version that supports configuration caching. */
private val minAGPVersion = GradleVersion.parse("4.2.0-alpha16")

class ConfigurationCachingCompatibilityAnalyzer : BaseAnalyzer<ConfigurationCachingCompatibilityProjectResult>(),
    BuildAttributionReportAnalyzer,
    KnownPluginsDataAnalyzer,
    PostBuildProcessAnalyzer {

  private val buildscriptClasspath = mutableListOf<GradleCoordinate>()
  private var appliedPlugins: Map<String, List<PluginData>> = emptyMap()
  private var knownPlugins: List<GradlePluginsData.PluginInfo> = emptyList()
  private var currentAgpVersion: GradleVersion? = null
  private var configurationCachingGradlePropertiesFlagState: String? = null
  private var configurationCacheInBuildState: Boolean? = null

  override fun cleanupTempState() {
    buildscriptClasspath.clear()
    appliedPlugins = emptyMap()
    knownPlugins = emptyList()
    currentAgpVersion = null
    configurationCachingGradlePropertiesFlagState = null
    configurationCacheInBuildState = null
  }

  override fun receiveBuildAttributionReport(androidGradlePluginAttributionData: AndroidGradlePluginAttributionData) {
    buildscriptClasspath.addAll(
      androidGradlePluginAttributionData.buildscriptDependenciesInfo.mapNotNull { GradleCoordinate.parseCoordinateString(it) }
    )
    configurationCacheInBuildState = androidGradlePluginAttributionData.buildInfo?.configurationCacheIsOn
    currentAgpVersion = androidGradlePluginAttributionData.buildInfo?.agpVersion?.let { GradleVersion.tryParse(it) }
  }

  override fun receiveKnownPluginsData(data: GradlePluginsData) {
    knownPlugins = data.pluginsInfo
  }

  override fun runPostBuildAnalysis(analyzersResult: BuildEventsAnalysisResult, studioProvidedInfo: StudioProvidedInfo) {
    appliedPlugins = analyzersResult.getAppliedPlugins()
    if (currentAgpVersion == null) currentAgpVersion = studioProvidedInfo.agpVersion
    configurationCachingGradlePropertiesFlagState = studioProvidedInfo.configurationCachingGradlePropertyState
    ensureResultCalculated()
  }

  override fun calculateResult(): ConfigurationCachingCompatibilityProjectResult {
    return compute(appliedPlugins.values.flatten())
  }

  private fun compute(appliedPlugins: List<PluginData>): ConfigurationCachingCompatibilityProjectResult {
    if (configurationCacheInBuildState == true) return ConfigurationCachingTurnedOn
    if (buildscriptClasspath.isEmpty()) {
      // Possible that we are using an old AGP. Need to check the known version from sync.
      currentAgpVersion?.let {
        if (it < minAGPVersion) return AGPUpdateRequired(it)
      }
    }

    val pluginsByPluginInfo: Map<GradlePluginsData.PluginInfo?, List<PluginData>> = appliedPlugins
        .filter { it.pluginType == PluginData.PluginType.BINARY_PLUGIN }
        .toSet()
        .groupBy { appliedPlugin -> knownPlugins.find { it.isThisPlugin(appliedPlugin) } }

    val incompatiblePluginWarnings = mutableListOf<IncompatiblePluginWarning>()
    val upgradePluginWarnings = mutableListOf<IncompatiblePluginWarning>()
    pluginsByPluginInfo.forEach { (pluginInfo, plugins) ->
      if (pluginInfo?.pluginArtifact == null) return@forEach
      val detectedVersion = buildscriptClasspath.find { it.isSameCoordinate(pluginInfo.pluginArtifact) }?.version
      if (detectedVersion != null) {
        when {
          pluginInfo.configurationCachingCompatibleFrom == null -> incompatiblePluginWarnings.addAll(
            plugins.map { IncompatiblePluginWarning(it, detectedVersion, pluginInfo) }
          )
          detectedVersion < pluginInfo.configurationCachingCompatibleFrom -> upgradePluginWarnings.addAll(
            plugins.map { IncompatiblePluginWarning(it, detectedVersion, pluginInfo) }
          )
        }
      }
    }
    return if (incompatiblePluginWarnings.isEmpty() && upgradePluginWarnings.isEmpty()) {
      NoIncompatiblePlugins(pluginsByPluginInfo[null]?.filterOutInternalPlugins() ?: emptyList())
    }
    else {
      IncompatiblePluginsDetected(incompatiblePluginWarnings, upgradePluginWarnings)
    }
  }

  private fun GradleCoordinate.isSameCoordinate(dependencyCoordinates: GradlePluginsData.DependencyCoordinates) =
    dependencyCoordinates.group == groupId && dependencyCoordinates.name == artifactId

  private fun List<PluginData>.filterOutInternalPlugins() = filter {
    // ignore Gradle, AGP and Kotlin plugins, buildscript
    !it.isAndroidPlugin() && !it.isGradlePlugin() && !it.isKotlinPlugin() && it.pluginType != PluginData.PluginType.SCRIPT
  }
}

sealed class ConfigurationCachingCompatibilityProjectResult : AnalyzerResult

data class AGPUpdateRequired(
  val currentVersion: GradleVersion
) : ConfigurationCachingCompatibilityProjectResult() {
  val recommendedVersion = GradleVersion.parse("4.2")
  val dependencyCoordinates = GradlePluginsData.DependencyCoordinates("com.android.tools.build", "gradle")
}

/**
 * Analyzer result returned when all recognised plugins are compatible with configuration caching.
 * There still might be problems in unknown plugins or buildscript and buildSrc plugins.
 */
data class NoIncompatiblePlugins(
  val unrecognizedPlugins: List<PluginData>
) : ConfigurationCachingCompatibilityProjectResult()

/**
 * Analyzer result returned when there are incompatible plugins detected.
 * [incompatiblePluginWarnings] contain the list of warnings for plugins known to be incompatible.
 * [upgradePluginWarnings] contain the list of warnings for plugins that need upgrade to higher version.
 */
data class IncompatiblePluginsDetected(
  val incompatiblePluginWarnings: List<IncompatiblePluginWarning>,
  val upgradePluginWarnings: List<IncompatiblePluginWarning>
) : ConfigurationCachingCompatibilityProjectResult()

data class IncompatiblePluginWarning(
  val plugin: PluginData,
  val currentVersion: GradleVersion,
  val pluginInfo: GradlePluginsData.PluginInfo,
) {
  val requiredVersion: GradleVersion?
    get() = pluginInfo.configurationCachingCompatibleFrom
}

/** Analyzer result when configuration caching is turned on in gradle.properties. */
object ConfigurationCachingTurnedOn : ConfigurationCachingCompatibilityProjectResult()
