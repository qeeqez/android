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
package com.android.build.attribution.analyzers

import com.android.build.attribution.BuildAnalyzerStorageManager
import com.android.build.attribution.getSuccessfulResult
import com.android.tools.idea.gradle.project.sync.snapshots.AndroidCoreTestProject
import com.android.tools.idea.gradle.project.sync.snapshots.TestProjectDefinition.Companion.prepareTestProject
import com.android.tools.idea.testing.AndroidProjectRule
import com.android.tools.idea.testing.EdtAndroidProjectRule
import com.android.tools.idea.testing.onEdt
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test

class AppliedPluginsTest {

  @get:Rule
  val projectRule: EdtAndroidProjectRule = AndroidProjectRule.withAndroidModels().onEdt()

  @Test
  fun testAppliedPlugins() {
    val preparedProject = projectRule.prepareTestProject(AndroidCoreTestProject.BUILD_ANALYZER_CHECK_ANALYZERS)

    preparedProject.runTest {
      invokeTasks(":app:preBuild")

    val buildAnalyzerStorageManager = project.getService(BuildAnalyzerStorageManager::class.java)
    val results = buildAnalyzerStorageManager.getSuccessfulResult()

      assertThat(results.getAppliedPlugins()).hasSize(3)
      val appliedPluginsForAppProject = results.getAppliedPlugins()[":app"]!!.map { it.displayNames().first() }
      assertThat(appliedPluginsForAppProject).containsAllIn(
        listOf(
          "AlwaysRunTasksPlugin",
          "AlwaysRunningBuildSrcPlugin",
          "com.android.application",
          "org.gradle.api.plugins.JavaBasePlugin",
        )
      )
    }
  }
}