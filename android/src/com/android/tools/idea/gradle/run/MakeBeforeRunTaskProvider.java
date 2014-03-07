/*
 * Copyright (C) 2013 The Android Open Source Project
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
package com.android.tools.idea.gradle.run;

import com.android.tools.idea.gradle.invoker.GradleExecutionResult;
import com.android.tools.idea.gradle.invoker.GradleInvoker;
import com.android.tools.idea.gradle.util.GradleBuilds.TestCompileType;
import com.android.tools.idea.gradle.util.Projects;
import com.android.tools.idea.startup.AndroidStudioSpecificInitializer;
import com.intellij.compiler.options.CompileStepBeforeRun;
import com.intellij.execution.BeforeRunTaskProvider;
import com.intellij.execution.configurations.ModuleBasedConfiguration;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.util.concurrency.Semaphore;
import icons.AndroidIcons;
import org.jetbrains.android.run.AndroidRunConfigurationBase;
import org.jetbrains.android.util.AndroidCommonUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Provides the "Gradle-aware Make" task for Run Configurations, which
 * <ul>
 *   <li>is only available in Android Studio</li>
 *   <li>delegates to the regular "Make" if the project is not an Android Gradle project</li>
 *   <li>otherwise, invokes Gradle directly, to build the project</li>
 * </ul>
 */
public class MakeBeforeRunTaskProvider extends BeforeRunTaskProvider<MakeBeforeRunTask> {
  @NotNull public static final Key<MakeBeforeRunTask> ID = Key.create("Android.Gradle.BeforeRunTask");

  private static final Logger LOG = Logger.getInstance(MakeBeforeRunTask.class);
  private static final String TASK_NAME = "Gradle-aware Make";

  @NotNull private final Project myProject;

  public MakeBeforeRunTaskProvider(@NotNull Project project) {
    myProject = project;
  }

  @Override
  public Key<MakeBeforeRunTask> getId() {
    return ID;
  }

  @Nullable
  @Override
  public Icon getIcon() {
    return AndroidIcons.Android;
  }

  @Nullable
  @Override
  public Icon getTaskIcon(MakeBeforeRunTask task) {
    return AndroidIcons.Android;
  }

  @Override
  public String getName() {
    return TASK_NAME;
  }

  @Override
  public String getDescription(MakeBeforeRunTask task) {
    return TASK_NAME;
  }

  @Override
  public boolean isConfigurable() {
    return false;
  }

  @Nullable
  @Override
  public MakeBeforeRunTask createTask(RunConfiguration runConfiguration) {
    // "Gradle-aware Make" is only available in Android Studio.
    if (AndroidStudioSpecificInitializer.isAndroidStudio() && runConfiguration instanceof AndroidRunConfigurationBase) {
      return new MakeBeforeRunTask();
    } else {
      return null;
    }
  }

  @Override
  public boolean configureTask(RunConfiguration runConfiguration, MakeBeforeRunTask task) {
    return false;
  }

  @Override
  public boolean canExecuteTask(RunConfiguration configuration, MakeBeforeRunTask task) {
    return true;
  }

  @Override
  public boolean executeTask(final DataContext context,
                             final RunConfiguration configuration,
                             ExecutionEnvironment env,
                             MakeBeforeRunTask task) {
    if (!Projects.isGradleProject(myProject) || !Projects.isDirectGradleInvocationEnabled(myProject)) {
      CompileStepBeforeRun regularMake = new CompileStepBeforeRun(myProject);
      return regularMake.executeTask(context, configuration, env, new CompileStepBeforeRun.MakeBeforeRunTask());
    }

    final AtomicBoolean success = new AtomicBoolean();
    try {
      final Semaphore done = new Semaphore();
      done.down();
      final GradleInvoker.AfterExecutionTask afterTask = new GradleInvoker.AfterExecutionTask() {
        @Override
        public void execute(@NotNull GradleExecutionResult result) {
          success.set(result.getErrorCount() == 0);
          done.up();
        }
      };

      if (myProject.isDisposed()) {
        done.up();
      }
      else {
        // To ensure that the "Run Configuration" waits for the Gradle tasks to be executed, we use SwingUtilities.invokeAndWait. I tried
        // using Application.invokeAndWait but it never worked. IDEA also uses SwingUtilities in this scenario (see CompileStepBeforeRun.)
        SwingUtilities.invokeAndWait(new Runnable() {
          @Override
          public void run() {
            Module[] modules;
            if (configuration instanceof ModuleBasedConfiguration) {
              // ModuleBasedConfiguration includes Android and JUnit run configurations.
              modules = ((ModuleBasedConfiguration)configuration).getModules();
            }
            else {
              modules = Projects.getModulesToBuildFromSelection(myProject, context);
            }
            GradleInvoker gradleInvoker = GradleInvoker.getInstance(myProject);
            TestCompileType testCompileType = getTestCompileType(configuration);
            gradleInvoker.make(modules, testCompileType, afterTask);
          }
        });
        done.waitFor();
      }
    }
    catch (Throwable t) {
      LOG.info("Unable to launch '" + TASK_NAME + "' task", t);
      return false;
    }
    return success.get();
  }

  @NotNull
  private static TestCompileType getTestCompileType(@Nullable RunConfiguration runConfiguration) {
    if (runConfiguration != null) {
      String id = runConfiguration.getType().getId();
      if (AndroidCommonUtils.isTestConfiguration(id)) {
        return TestCompileType.JAVA_TESTS;
      }
      if (AndroidCommonUtils.isInstrumentationTestConfiguration(id)) {
        return TestCompileType.ANDROID_TESTS;
      }
    }
    return TestCompileType.NONE;
  }
}
