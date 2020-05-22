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
package com.android.tools.idea.nav.safeargs.kotlin

import com.android.testutils.MockitoKt.mock
import com.android.tools.idea.nav.safeargs.SafeArgsMode
import com.android.tools.idea.nav.safeargs.SafeArgsRule
import com.android.tools.idea.nav.safeargs.project.SafeArgSyntheticPackageProvider
import com.android.tools.idea.nav.safeargs.project.SafeArgsKtPackageProviderExtension
import com.android.tools.idea.res.ResourceRepositoryManager
import com.google.common.truth.Truth.assertThat
import com.intellij.testFramework.RunsInEdt
import org.jetbrains.kotlin.idea.caches.project.productionSourceInfo
import org.jetbrains.kotlin.idea.caches.project.toDescriptor
import org.jetbrains.kotlin.incremental.components.LookupTracker
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.renderer.DescriptorRenderer
import org.jetbrains.kotlin.resolve.BindingTrace
import org.jetbrains.kotlin.resolve.MemberComparator
import org.jetbrains.kotlin.resolve.jvm.extensions.PackageFragmentProviderExtension
import org.jetbrains.kotlin.storage.LockBasedStorageManager
import org.junit.Rule
import org.junit.Test

@RunsInEdt
class SafeArgKotlinPackageDescriptorTest {
  @get:Rule
  val safeArgsRule = SafeArgsRule(SafeArgsMode.KOTLIN)

  /**
   * Check contributed args and directions class descriptors for single module case
   */
  @Test
  fun checkContributorsOfPackage() {
    safeArgsRule.fixture.addFileToProject(
      "res/navigation/main.xml",
      //language=XML
      """
        <?xml version="1.0" encoding="utf-8"?>
        <navigation xmlns:android="http://schemas.android.com/apk/res/android"
            xmlns:app="http://schemas.android.com/apk/res-auto" android:id="@+id/main"
            app:startDestination="@id/fragment1">

          <fragment
              android:id="@+id/fragment1"
              android:name="test.safeargs.Fragment1"
              android:label="Fragment1">
            <argument
                android:name="arg1"
                app:argType="string" />
            <action
                android:id="@+id/action_Fragment1_to_Fragment2"
                app:destination="@id/fragment2" />
          </fragment>
          <fragment
              android:id="@+id/fragment2"
              android:name="test.safeargs.Fragment2"
              android:label="Fragment2" >
            <argument
                android:name="arg2"
                app:argType="integer[]" />
            <action
              android:id="@+id/action_Fragment2_to_main"
              app:destination="@id/main" />
          </fragment>  
          <action
              android:id="@+id/action_main_to_fragment1"
              app:destination="@id/fragment1" />                      
        </navigation>
      """.trimIndent())

    // Initialize repository after creating resources, needed for codegen to work
    ResourceRepositoryManager.getInstance(safeArgsRule.androidFacet).moduleResources

    val safeArgProviderExtension = PackageFragmentProviderExtension.getInstances(safeArgsRule.project).first {
      it is SafeArgsKtPackageProviderExtension
    }

    val traceMock: BindingTrace = mock()
    val moduleSourceInfo = safeArgsRule.module.productionSourceInfo()
    val moduleDescriptor = safeArgsRule.module.toDescriptor()

    val fragmentProvider = safeArgProviderExtension.getPackageFragmentProvider(
      project = safeArgsRule.project,
      module = moduleDescriptor!!,
      storageManager = LockBasedStorageManager.NO_LOCKS,
      trace = traceMock,
      moduleInfo = moduleSourceInfo,
      lookupTracker = LookupTracker.DO_NOTHING
    ) as SafeArgSyntheticPackageProvider

    val renderer = DescriptorRenderer.COMPACT_WITH_MODIFIERS

    val classDescriptors = fragmentProvider.getPackageFragments(FqName("test.safeargs"))
      .first()
      .getMemberScope()
      .getContributedDescriptors()
      .sortedWith(MemberComparator.INSTANCE)
      .map { renderer.render(it) }

    assertThat(classDescriptors).containsExactly(
      // unresolved type is due to the missing library module dependency in test setup
      "public final class Fragment1Args : [ERROR : androidx.navigation.NavArgs]",
      "public final class Fragment1Directions",
      "public final class Fragment2Args : [ERROR : androidx.navigation.NavArgs]",
      "public final class Fragment2Directions",
      "public final class MainDirections"
    )
  }
}