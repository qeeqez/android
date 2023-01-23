/*
 * Copyright (C) 2022 The Android Open Source Project
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
package com.android.tools.idea.gradle.project.sync.memory

import com.android.SdkConstants
import com.android.testutils.TestUtils
import com.android.tools.idea.flags.StudioFlags
import com.android.tools.idea.gradle.util.GradleProperties
import com.android.tools.idea.testing.AndroidGradleProjectRule
import com.android.tools.perflogger.Benchmark
import com.android.tools.perflogger.Metric
import com.android.tools.perflogger.Metric.MetricSample
import com.intellij.testFramework.EdtRule
import com.intellij.testFramework.RunsInEdt
import org.gradle.tooling.internal.consumer.DefaultGradleConnector
import org.jetbrains.android.AndroidTestBase
import org.jetbrains.plugins.gradle.settings.DistributionType
import org.jetbrains.plugins.gradle.settings.GradleProjectSettings
import org.jetbrains.plugins.gradle.settings.GradleSettings
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Instant
import kotlin.io.path.createDirectory
import kotlin.system.measureTimeMillis

@RunsInEdt
abstract class AbstractGradleSyncMemoryUsageTestCase {
  companion object {
    val BENCHMARK = Benchmark.Builder("Retained heap size")
      .setProject("Android Studio Sync Test")
      .build()
  }

  abstract val relativePath: String
  abstract val projectName: String

  val projectRule = AndroidGradleProjectRule()
  @get:Rule val ruleChain = org.junit.rules.RuleChain.outerRule(projectRule).around(EdtRule())!!

  private val eclipseMatHelper = EclipseMatHelper()
  private lateinit var snapshotDirectory: String
  private val keepSnapshots = System.getProperty("keep_snapshots").toBoolean()

  @Before
  open fun setUp() {
    val projectSettings = GradleProjectSettings()
    projectSettings.distributionType = DistributionType.DEFAULT_WRAPPED
    GradleSettings.getInstance(projectRule.project).linkedProjectsSettings = listOf(projectSettings)
    projectRule.fixture.testDataPath = AndroidTestBase.getModulePath("sync-memory-tests") + File.separator + "testData"
    snapshotDirectory = File(System.getenv("TEST_TMPDIR"), "snapshots").also {
      it.toPath().createDirectory()
    }.absolutePath
    StudioFlags.GRADLE_HPROF_OUTPUT_DIRECTORY.override(snapshotDirectory)
  }

  @After
  open fun tearDown() {
    val tmpDir = Paths.get(System.getProperty("java.io.tmpdir"))
    val testOutputDir = TestUtils.getTestOutputDir()
    tmpDir
      .resolve(".gradle/daemon").toFile()
      .walk()
      .filter { it.name.endsWith("out.log") }
      .forEach {
        Files.move(it.toPath(), testOutputDir.resolve(it.name))
      }
    StudioFlags.GRADLE_HPROF_OUTPUT_DIRECTORY.clearOverride()
    File(snapshotDirectory).delete()
  }

  @Test
  open fun testSyncMemory() {
    reduceMaxMemory()
    projectRule.loadProject(relativePath)
    // Free up some memory by closing the Gradle Daemon
    DefaultGradleConnector.close()

    val metricBeforeSync = Metric("${projectName}_Before_Sync")
    val metricBeforeSyncSoft = Metric("${projectName}_Before_Sync_Soft")
    val metricBeforeSyncWeak = Metric("${projectName}_Before_Sync_Weak")
    val metricBeforeSyncTotal = Metric("${projectName}_Before_Sync_Total")
    val metricAfterSync = Metric("${projectName}_After_Sync")
    val metricAfterSyncSoft = Metric("${projectName}_After_Sync_Soft")
    val metricAfterSyncWeak = Metric("${projectName}_After_Sync_Weak")
    val metricAfterSyncTotal = Metric("${projectName}_After_Sync_Total")
    val currentTime = Instant.now().toEpochMilli()
    for (hprofPath in File(snapshotDirectory).walk().filter { !it.isDirectory && it.name.endsWith(".hprof")}.asIterable()) {
      val elapsedTime = measureTimeMillis {
        val metrics = eclipseMatHelper.getHeapUsageMetrics(hprofPath.absolutePath)
        println("Size of ${hprofPath.name}: $metrics")
        if (hprofPath.name.contains("before_sync")) {
          metricBeforeSync.addSamples(BENCHMARK, MetricSample(currentTime, metrics.excludeSoftAndWeak()))
          metricBeforeSyncTotal.addSamples(BENCHMARK, MetricSample(currentTime, metrics.totalUsage))
          metricBeforeSyncSoft.addSamples(BENCHMARK, MetricSample(currentTime, metrics.softlyReferenced))
          metricBeforeSyncWeak.addSamples(BENCHMARK, MetricSample(currentTime, metrics.weaklyReferenced))
        }
        if (hprofPath.name.contains("after_sync")) {
          metricAfterSync.addSamples(BENCHMARK, MetricSample(currentTime, metrics.excludeSoftAndWeak()))
          metricAfterSyncTotal.addSamples(BENCHMARK, MetricSample(currentTime, metrics.totalUsage))
          metricAfterSyncSoft.addSamples(BENCHMARK, MetricSample(currentTime, metrics.softlyReferenced))
          metricAfterSyncWeak.addSamples(BENCHMARK, MetricSample(currentTime, metrics.weaklyReferenced))
        }
      }
      println("Analysis took $elapsedTime MS.")
      if (keepSnapshots) {
        val testOutputDir = TestUtils.getTestOutputDir()
        Files.move(hprofPath.toPath(), testOutputDir.resolve(hprofPath.name))
      }
    }
    metricBeforeSync.commit()
    metricBeforeSyncTotal.commit()
    metricBeforeSyncSoft.commit()
    metricBeforeSyncWeak.commit()

    metricAfterSync.commit()
    metricAfterSyncTotal.commit()
    metricAfterSyncSoft.commit()
    metricAfterSyncWeak.commit()
  }

  private fun reduceMaxMemory() {
    GradleProperties(File(projectRule.resolveTestDataPath(relativePath), SdkConstants.FN_GRADLE_PROPERTIES)).apply {
      setJvmArgs(jvmArgs.orEmpty().replace("-Xmx60g", "-Xmx8g"))
      save()
    }
  }
}