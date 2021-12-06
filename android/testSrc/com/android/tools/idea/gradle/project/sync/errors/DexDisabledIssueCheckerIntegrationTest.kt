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
package com.android.tools.idea.gradle.project.sync.errors

import com.android.tools.idea.gradle.dsl.api.ProjectBuildModel
import com.android.tools.idea.gradle.project.build.invoker.GradleBuildInvoker
import com.android.tools.idea.testing.AndroidGradleTestCase
import com.android.tools.idea.testing.IdeComponents
import com.android.tools.idea.testing.findAppModule
import com.google.common.truth.Truth.assertThat
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.externalSystem.issue.BuildIssueException
import com.intellij.openapi.externalSystem.service.notification.ExternalSystemNotificationManager
import com.intellij.pom.java.LanguageLevel
import org.jetbrains.plugins.gradle.issue.GradleIssueChecker.Companion.getKnownIssuesCheckList
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.any
import org.mockito.Mockito.spy
import org.mockito.Mockito.verify

class DexDisabledIssueCheckerIntegrationTest: AndroidGradleTestCase() {
  @Test
  fun testDexDisabledIssueCheckerIsKnown() {
    assertThat(getKnownIssuesCheckList().filterIsInstance(DexDisabledIssueChecker::class.java)).isNotEmpty()
  }

  @Test
  fun testDependencyWithLevel8CausesBuildIssue() {
    loadSimpleApplication()
    val project = project
    val appModule = project.findAppModule()
    val projectModel = ProjectBuildModel.get(project)
    val buildModel = projectModel.getModuleBuildModel(appModule)
    buildModel!!.android().compileOptions().sourceCompatibility().setLanguageLevel(LanguageLevel.JDK_1_7)
    buildModel.android().compileOptions().targetCompatibility().setLanguageLevel(LanguageLevel.JDK_1_7)
    // Add a dependency that requires level 8
    buildModel.dependencies().addArtifact("implementation", "com.google.guava:guava:28.2-jre")
    ApplicationManager.getApplication().invokeAndWait {
      WriteCommandAction.runWriteCommandAction(project) {
        projectModel.applyChanges()
      }
    }
    val spyNotificationManager = spy(ExternalSystemNotificationManager.getInstance(project))
    IdeComponents(project).replaceProjectService(ExternalSystemNotificationManager::class.java, spyNotificationManager)
    // Confirm that there is a build error
    val result = invokeGradle(project) {
      obj: GradleBuildInvoker -> obj.rebuild()
    }
    assertThat(result).isNotNull()
    assertThat(result.invocations.firstOrNull()?.buildError).isNotNull()
    // and that error generates the expected BuildIssue
    val throwableCaptor = ArgumentCaptor.forClass(Throwable::class.java)
    verify(spyNotificationManager).createNotification(any(), throwableCaptor.capture(), any(), eq(project), any())
    val generatedExceptions = throwableCaptor.allValues
    assertThat(generatedExceptions).hasSize(1)
    assertThat(generatedExceptions[0]).isInstanceOf(BuildIssueException::class.java)
    val buildIssue = (generatedExceptions[0] as BuildIssueException).buildIssue
    assertThat(buildIssue).isInstanceOf(DexDisabledIssue::class.java)
  }
}