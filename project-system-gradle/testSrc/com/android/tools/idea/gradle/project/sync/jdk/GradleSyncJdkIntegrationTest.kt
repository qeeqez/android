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
package com.android.tools.idea.gradle.project.sync.jdk

import com.android.tools.idea.gradle.project.sync.constants.*
import com.android.tools.idea.gradle.project.sync.snapshots.JdkIntegrationTest
import com.android.tools.idea.gradle.project.sync.utils.JdkTableUtils.Jdk
import com.android.tools.idea.sdk.IdeSdks.JDK_LOCATION_ENV_VARIABLE_NAME
import com.android.tools.idea.testing.AndroidProjectRule
import com.android.tools.idea.testing.EdtAndroidProjectRule
import com.android.tools.idea.testing.onEdt
import com.google.common.truth.Expect
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemJdkException
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemJdkUtil.JAVA_HOME
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemJdkUtil.USE_JAVA_HOME
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemJdkUtil.USE_PROJECT_JDK
import com.intellij.testFramework.RunsInEdt
import org.jetbrains.plugins.gradle.util.USE_GRADLE_JAVA_HOME
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@RunsInEdt
@Suppress("UnstableApiUsage")
class GradleSyncJdkIntegrationTest {

  @get:Rule
  val projectRule: EdtAndroidProjectRule = AndroidProjectRule.withAndroidModels().onEdt()

  @get:Rule
  val expect: Expect = Expect.createAndEnableStackTrace()

  @get:Rule
  val temporaryFolder = TemporaryFolder()

  private val jdkIntegrationTest = JdkIntegrationTest(projectRule, temporaryFolder, expect)

  @Test(expected = ExternalSystemJdkException::class)
  fun `Given invalid gradlePropertiesUserHomeJdkPath When import project Then throw exception`() =
    jdkIntegrationTest.run {
      configEnvironment(
        gradlePropertiesUserHomeJdkPath = "/invalid/jdk/path",
      )
      sync()
    }

  @Test(expected = ExternalSystemJdkException::class)
  fun `Given invalid gradlePropertiesProjectJdkPath When import project Then throw exception`() =
    jdkIntegrationTest.run {
      configEnvironment(
        gradlePropertiesProjectJdkPath = "/invalid/jdk/path",
      )
      sync()
    }

  @Test(expected = ExternalSystemJdkException::class)
  fun `Given invalid STUDIO_GRADLE_JDK env variable When import project Then throw exception`() =
    jdkIntegrationTest.run {
      configEnvironment(
        environmentVariables = mapOf(JDK_LOCATION_ENV_VARIABLE_NAME to "/invalid/jdk/path")
      )
      sync()
    }

  @Test
  fun `Given USE_JAVA_HOME gradleJdk macro When import project Then sync used JAVA_HOME`() =
    jdkIntegrationTest.run {
      configEnvironment(
        ideaGradleJdk = USE_JAVA_HOME,
        ideaProjectJdk = JDK_17,
        jdkTable = listOf(Jdk(JDK_11, JDK_11_PATH), Jdk(JDK_17, JDK_17_PATH)),
        environmentVariables = mapOf(JAVA_HOME to JDK_17_PATH)
      )
      syncWithAssertion(
        expectedGradleJdkName = USE_JAVA_HOME,
        expectedProjectJdkName = JDK_17,
        expectedJdkPath = JDK_17_PATH
      )
    }

  @Test
  fun `Given USE_PROJECT_JDK gradleJdk macro When import project Then sync used PROJECT_JDK`() =
    jdkIntegrationTest.run {
      configEnvironment(
        ideaGradleJdk = USE_PROJECT_JDK,
        ideaProjectJdk = JDK_17,
        jdkTable = Jdk(JDK_17, JDK_17_PATH)
      )
      syncWithAssertion(
        expectedGradleJdkName = USE_PROJECT_JDK,
        expectedProjectJdkName = JDK_17,
        expectedJdkPath = JDK_17_PATH
      )
    }

  @Test(expected = ExternalSystemJdkException::class)
  fun `Given USE_GRADLE_JAVA_HOME gradleJdk macro without jdkPath defined When import project Then throw exception`() =
    jdkIntegrationTest.run {
      configEnvironment(
        ideaGradleJdk = USE_GRADLE_JAVA_HOME,
        jdkTable = Jdk(JDK_17, JDK_17_PATH)
      )
      sync()
    }

  @Test(expected = ExternalSystemJdkException::class)
  fun `Given project without any configuration When import project Then throw exception`() =
    jdkIntegrationTest.run {
      sync()
    }

  @Test(expected = ExternalSystemJdkException::class)
  fun `Given project with gradleJdk not present in jdkTable When import project Then throw exception`() =
    jdkIntegrationTest.run {
      configEnvironment(
        ideaGradleJdk = "invalid",
        jdkTable = Jdk(JDK_17, JDK_17_PATH)
      )
      sync()
    }

  @Test(expected = ExternalSystemJdkException::class)
  fun `Given project with gradleJdk present in jdkTable but invalid path When import project Then throw exception`() =
    jdkIntegrationTest.run {
      configEnvironment(
        ideaGradleJdk = JDK_17,
        ideaProjectJdk = "any",
        jdkTable = Jdk(JDK_17, JDK_INVALID_PATH)
      )
      sync()
    }
}