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
package com.android.tools.idea.gradle.model.impl

import com.android.tools.idea.gradle.model.IdeAndroidLibraryDependency
import com.android.tools.idea.gradle.model.IdeDependencies
import com.android.tools.idea.gradle.model.IdeJavaLibraryDependency
import com.android.tools.idea.gradle.model.IdeLibraryModelResolver
import com.android.tools.idea.gradle.model.IdeModuleDependency
import com.android.tools.idea.gradle.model.IdeAndroidLibraryDependencyCore
import com.android.tools.idea.gradle.model.IdeDependenciesCore
import com.android.tools.idea.gradle.model.IdeJavaLibraryDependencyCore
import com.android.tools.idea.gradle.model.IdeModuleDependencyCore
import java.io.File
import java.io.Serializable

data class IdeDependenciesCoreImpl(
  override val androidLibraries: Collection<IdeAndroidLibraryDependencyCore>,
  override val javaLibraries: Collection<IdeJavaLibraryDependencyCore>,
  override val moduleDependencies: Collection<IdeModuleDependencyCore>,
  override val runtimeOnlyClasses: Collection<File>
) : IdeDependenciesCore, Serializable

data class IdeDependenciesImpl(
  private val dependencyCores: IdeDependenciesCore,
  private val resolver: IdeLibraryModelResolver
) : IdeDependencies {
  override val androidLibraries: Collection<IdeAndroidLibraryDependency> =
    dependencyCores.androidLibraries.map(resolver::resolveAndroidLibrary)
  override val javaLibraries: Collection<IdeJavaLibraryDependency> =
    dependencyCores.javaLibraries.map(resolver::resolveJavaLibrary)
  override val moduleDependencies: Collection<IdeModuleDependency> = dependencyCores.moduleDependencies.map(resolver::resolveModule)
  override val runtimeOnlyClasses: Collection<File> = dependencyCores.runtimeOnlyClasses
}

class ThrowingIdeDependencies : IdeDependenciesCore, Serializable {
  override val androidLibraries: Nothing get() = throw NotImplementedError()
  override val javaLibraries: Nothing get() = throw NotImplementedError()
  override val moduleDependencies: Nothing get() = throw NotImplementedError()
  override val runtimeOnlyClasses: Nothing get() = throw NotImplementedError()
}
