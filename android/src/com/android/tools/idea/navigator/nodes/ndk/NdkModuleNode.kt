/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.tools.idea.navigator.nodes.ndk

import com.android.builder.model.NativeArtifact
import com.android.tools.idea.flags.StudioFlags
import com.android.tools.idea.gradle.project.facet.ndk.NdkFacet
import com.android.tools.idea.gradle.project.model.NdkModuleModel
import com.android.tools.idea.gradle.util.GradleUtil.getModuleIcon
import com.android.tools.idea.navigator.AndroidProjectViewPane
import com.android.tools.idea.navigator.nodes.AndroidViewModuleNode
import com.android.tools.idea.navigator.nodes.ndk.includes.utils.LexicalIncludePaths
import com.android.tools.idea.navigator.nodes.ndk.includes.view.NativeIncludes
import com.android.tools.idea.ndk.ModuleVariantAbi
import com.android.tools.idea.ndk.NativeWorkspaceService
import com.android.tools.idea.util.toIoFile
import com.google.common.collect.HashMultimap
import com.intellij.ide.projectView.PresentationData
import com.intellij.ide.projectView.ViewSettings
import com.intellij.ide.util.treeView.AbstractTreeNode
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Queryable
import com.intellij.openapi.util.text.StringUtil.trimEnd
import com.intellij.openapi.util.text.StringUtil.trimStart
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import java.util.ArrayList

class NdkModuleNode(
  project: Project,
  value: Module,
  projectViewPane: AndroidProjectViewPane,
  settings: ViewSettings) : AndroidViewModuleNode(project, value, projectViewPane, settings) {

  override fun getModuleChildren(): Collection<AbstractTreeNode<*>> {
    val module = value ?: return emptyList()

    val facet = NdkFacet.getInstance(module)
    if (facet?.ndkModuleModel == null) {
      return emptyList()
    }

    assert(myProject != null)
    return getNativeSourceNodes(myProject, facet.ndkModuleModel!!, settings)
  }

  override fun getSortKey(): Comparable<*>? {
    val module = value ?: return null
    return module.name
  }

  override fun getTypeSortKey(): Comparable<*>? {
    return sortKey
  }

  override fun toTestString(printInfo: Queryable.PrintInfo?): String? {
    return String.format("%1\$s (Native-Android-Gradle)", super.toTestString(printInfo))
  }

  override fun update(presentation: PresentationData) {
    super.update(presentation)
    val module = value
    if (module != null) {
      presentation.setIcon(getModuleIcon(module))
    }
  }
}

fun getNativeSourceNodes(project: Project,
                         ndkModel: NdkModuleModel,
                         settings: ViewSettings): Collection<AbstractTreeNode<*>> {
  val module = ModuleManager.getInstance(project).findModuleByName(ndkModel.moduleName)!!
  if (StudioFlags.USE_CONTENT_ROOTS_FOR_NATIVE_PROJECT_VIEW.get()) {
    return getContentRootBasedNativeNodes(module, settings)
  }
  else {
    val ndkFacet = NdkFacet.getInstance(module) ?: return emptyList()
    return getLibraryBasedNativeNodes(ndkFacet, ndkModel, project, settings)
  }
}

/**
 * Gets nodes for project view and Android view populated by native targets (aka, native libraries, for example, declared by add_library in
 * CMakeLists.txt).
 */
private fun getLibraryBasedNativeNodes(ndkFacet: NdkFacet,
                                       ndkModel: NdkModuleModel,
                                       project: Project,
                                       settings: ViewSettings): Collection<AbstractTreeNode<*>> {
  val variant = ndkModel.getNdkVariant(ndkFacet.selectedVariantAbi) ?: return emptyList()
  val nativeLibraries = HashMultimap.create<NativeLibraryKey, NativeArtifact>()
  for (artifact in variant.artifacts) {
    val file = artifact.outputFile
    var nativeLibraryName: String
    val nativeLibraryType: NativeLibraryType
    if (file == null) {
      nativeLibraryName = artifact.targetName
      nativeLibraryType = NativeLibraryType.OBJECT_LIBRARY
    }
    else {
      val name = file.name
      when {
        name.endsWith(".so") -> {
          nativeLibraryName = trimEnd(name, ".so")
          nativeLibraryType = NativeLibraryType.SHARED_LIBRARY
        }
        name.endsWith(".a") -> {
          nativeLibraryName = trimEnd(name, ".a")
          nativeLibraryType = NativeLibraryType.STATIC_LIBRARY
        }
        else -> {
          nativeLibraryName = name
          nativeLibraryType = NativeLibraryType.OTHER
        }
      }
      nativeLibraryName = trimStart(nativeLibraryName, "lib")
    }
    nativeLibraries.put(NativeLibraryKey(nativeLibraryName, nativeLibraryType), artifact)
  }
  val children = ArrayList<AbstractTreeNode<*>>()
  for (key in nativeLibraries.keySet()) {
    val nativeLibraryType = key.type.displayText
    val nativeLibraryName = key.name
    val node = NdkLibraryEnhancedHeadersNode(project, nativeLibraryName, nativeLibraryType, nativeLibraries.get(key),
                                             NativeIncludes({ ndkModel.findSettings(it) },
                                                            nativeLibraries.get(key)), settings
    )
    children.add(node)
  }
  return if (children.size == 1) {
    children[0].children
  }
  else children
}

fun containedInIncludeFolders(project: Project, ndkModuleModel: NdkModuleModel, file: VirtualFile): Boolean {
  if (!LexicalIncludePaths.hasHeaderExtension(file.name)) {
    // Skip directly if the file does not look like a header.
    return false
  }
  val module = ModuleManager.getInstance(project).findModuleByName(ndkModuleModel.moduleName)!!
  val ndkFacet = NdkFacet.getInstance(module)!!
  val selectedVariantAbi = ndkFacet.selectedVariantAbi ?: return false
  val variant = selectedVariantAbi.variant
  val abi = selectedVariantAbi.abi
  val nativeWorkspaceService = NativeWorkspaceService.getInstance(project)
  val nativeHeaderDirs = nativeWorkspaceService.getNativeHeaderDirs(ModuleVariantAbi(ndkModuleModel.moduleName, variant, abi))
  return nativeHeaderDirs.any { VfsUtil.isAncestor(it.dir, file.toIoFile(), false) }
}
