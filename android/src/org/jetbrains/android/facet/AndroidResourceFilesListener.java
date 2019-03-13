/*
 * Copyright 2000-2010 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jetbrains.android.facet;

import static org.jetbrains.android.util.AndroidUtils.findSourceRoot;

import com.android.SdkConstants;
import com.android.resources.ResourceFolderType;
import com.android.tools.idea.lang.aidl.AidlFileType;
import com.android.tools.idea.lang.rs.AndroidRenderscriptFileType;
import com.google.common.collect.Iterables;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileTypes.FileNameMatcher;
import com.intellij.openapi.fileTypes.StdFileTypes;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.util.containers.MultiMap;
import com.intellij.util.ui.update.MergingUpdateQueue;
import com.intellij.util.ui.update.Update;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jetbrains.android.compiler.AndroidAutogeneratorMode;
import org.jetbrains.android.compiler.AndroidCompileUtil;
import org.jetbrains.android.compiler.ModuleSourceAutogenerating;
import org.jetbrains.android.dom.manifest.Manifest;
import org.jetbrains.android.resourceManagers.ModuleResourceManagers;
import org.jetbrains.android.util.AndroidUtils;
import org.jetbrains.annotations.NotNull;

public class AndroidResourceFilesListener implements Disposable, BulkFileListener {
  private static final Key<String> CACHED_PACKAGE_KEY = Key.create("ANDROID_RESOURCE_LISTENER_CACHED_PACKAGE");

  private static final List<FileNameMatcher> RENDERSCRIPT_MATCHERS = Arrays.asList(AndroidRenderscriptFileType.fileNameMatchers());

  private final MergingUpdateQueue myQueue;
  private final Project myProject;

  public AndroidResourceFilesListener(@NotNull Project project) {
    myProject = project;
    myQueue = new MergingUpdateQueue("AndroidResourcesCompilationQueue", 300, true, null, this, null, false);
    ApplicationManager.getApplication().getMessageBus().connect(this).subscribe(VirtualFileManager.VFS_CHANGES, this);
  }

  @Override
  public void after(@NotNull List<? extends VFileEvent> events) {
    Set<VirtualFile> filesToProcess = getFilesToProcess(events);

    if (!filesToProcess.isEmpty()) {
      myQueue.queue(new MyUpdate(filesToProcess));
    }
  }

  @NotNull
  private static Set<VirtualFile> getFilesToProcess(@NotNull List<? extends VFileEvent> events) {
    Set<VirtualFile> result = new HashSet<>();

    for (VFileEvent event : events) {
      VirtualFile file = event.getFile();

      if (file != null && shouldScheduleUpdate(file)) {
        result.add(file);
      }
    }
    return result;
  }

  private static boolean shouldScheduleUpdate(@NotNull VirtualFile file) {
    // This method is called frequently so we try to avoid as much as possible I/O access. VirtualFile#getFileType will try to read
    // from the file the first time is called so we try to avoid it as much as possible. Instead we will just try to infer the type
    // based on the extension.
    // We care about the following files:
    // - XML resource files
    // - AIDL files
    // - Renderscript files
    // - AndroidManifest.xml

    String extension = file.getExtension();
    if (StringUtil.isEmpty(extension)) {
      return false;
    }

    if (StdFileTypes.XML.getDefaultExtension().equals(extension)) {
      VirtualFile parent = file.getParent();

      if (parent != null && parent.isDirectory()) {
        ResourceFolderType resType = ResourceFolderType.getFolderType(parent.getName());
        return ResourceFolderType.VALUES == resType;
      }
    }

    String fileName = file.getName();

    if (AidlFileType.DEFAULT_ASSOCIATED_EXTENSION.equals(extension) || SdkConstants.FN_ANDROID_MANIFEST_XML.equals(fileName)) {
      return true;
    }

    if (Iterables.any(RENDERSCRIPT_MATCHERS, (matcher) -> matcher != null && matcher.accept(fileName))) {
      return true;
    }

    return false;
  }

  @Override
  public void dispose() {
  }

  private class MyUpdate extends Update {
    private final Set<VirtualFile> myFiles;

    MyUpdate(@NotNull Set<VirtualFile> files) {
      super(files);
      myFiles = files;
    }

    @Override
    public void run() {
      if (ApplicationManager.getApplication().isUnitTestMode()) {
        return;
      }

      MultiMap<Module, AndroidAutogeneratorMode> map =
          ApplicationManager.getApplication().runReadAction(
              (Computable<MultiMap<Module, AndroidAutogeneratorMode>>)() -> computeCompilersToRunAndInvalidateLocalAttributesMap());

      if (map.isEmpty()) {
        return;
      }

      for (Map.Entry<Module, Collection<AndroidAutogeneratorMode>> entry : map.entrySet()) {
        Module module = entry.getKey();
        AndroidFacet facet = AndroidFacet.getInstance(module);
        if (facet == null) {
          continue;
        }

        if (!ModuleSourceAutogenerating.requiresAutoSourceGeneration(facet)) {
          continue;
        }

        ModuleSourceAutogenerating sourceAutogenerator = ModuleSourceAutogenerating.getInstance(facet);
        assert sourceAutogenerator != null;

        for (AndroidAutogeneratorMode mode : entry.getValue()) {
          sourceAutogenerator.scheduleSourceRegenerating(mode);
        }
      }
    }

    @NotNull
    private MultiMap<Module, AndroidAutogeneratorMode> computeCompilersToRunAndInvalidateLocalAttributesMap() {
      if (myProject.isDisposed()) {
        return MultiMap.emptyInstance();
      }
      MultiMap<Module, AndroidAutogeneratorMode> result = MultiMap.create();
      Set<Module> modulesToInvalidateAttributeDefs = new HashSet<>();

      for (VirtualFile file : myFiles) {
        Module module = ModuleUtilCore.findModuleForFile(file, myProject);

        if (module == null || module.isDisposed()) {
          continue;
        }
        AndroidFacet facet = AndroidFacet.getInstance(module);

        if (facet == null) {
          continue;
        }
        VirtualFile parent = file.getParent();
        VirtualFile gp = parent != null ? parent.getParent() : null;

        List<VirtualFile> resourceDirs = ResourceFolderManager.getInstance(facet).getFolders();
        for (VirtualFile resourceDir : resourceDirs) {
          if (gp != null &&
              Comparing.equal(gp, resourceDir) &&
              ResourceFolderType.VALUES == ResourceFolderType.getFolderType(parent.getName())) {
            modulesToInvalidateAttributeDefs.add(module);
          }
          List<AndroidAutogeneratorMode> modes = computeCompilersToRunAndInvalidateLocalAttributesMap(facet, file);

          if (!modes.isEmpty()) {
            result.putValues(module, modes);
          }
        }
      }
      invalidateAttributeDefinitions(modulesToInvalidateAttributeDefs);
      return result;
    }

    @NotNull
    private List<AndroidAutogeneratorMode> computeCompilersToRunAndInvalidateLocalAttributesMap(AndroidFacet facet, VirtualFile file) {
      VirtualFile parent = file.getParent();

      if (parent == null) {
        return Collections.emptyList();
      }
      Module module = facet.getModule();
      VirtualFile manifestFile = AndroidRootUtil.getManifestFile(facet);
      List<AndroidAutogeneratorMode> modes = new ArrayList<>();

      if (Comparing.equal(manifestFile, file)) {
        Manifest manifest = facet.getManifest();
        String aPackage = manifest != null ? manifest.getPackage().getValue() : null;
        String cachedPackage = facet.getUserData(CACHED_PACKAGE_KEY);

        if (cachedPackage != null && !cachedPackage.equals(aPackage)) {
          String aptGenDirPath = AndroidRootUtil.getAptGenSourceRootPath(facet);
          AndroidCompileUtil.removeDuplicatingClasses(module, cachedPackage, AndroidUtils.R_CLASS_NAME, null, aptGenDirPath);
        }
        facet.putUserData(CACHED_PACKAGE_KEY, aPackage);
        modes.add(AndroidAutogeneratorMode.AAPT);
        modes.add(AndroidAutogeneratorMode.BUILDCONFIG);
      }
      else if (file.getFileType() == AidlFileType.INSTANCE) {
        VirtualFile sourceRoot = findSourceRoot(module, file);
        if (sourceRoot != null && !Comparing.equal(AndroidRootUtil.getAidlGenDir(facet), sourceRoot)) {
          modes.add(AndroidAutogeneratorMode.AIDL);
        }
      }
      else if (file.getFileType() == AndroidRenderscriptFileType.INSTANCE) {
        VirtualFile sourceRoot = findSourceRoot(module, file);
        if (sourceRoot != null && !Comparing.equal(AndroidRootUtil.getRenderscriptGenDir(facet), sourceRoot)) {
          modes.add(AndroidAutogeneratorMode.RENDERSCRIPT);
        }
      }
      return modes;
    }

    private void invalidateAttributeDefinitions(@NotNull Collection<Module> modules) {
      for (Module module : AndroidUtils.getSetWithBackwardDependencies(modules)) {
        AndroidFacet facet = AndroidFacet.getInstance(module);

        if (facet != null) {
          ModuleResourceManagers.getInstance(facet).getLocalResourceManager().invalidateAttributeDefinitions();
        }
      }
    }

    @Override
    public boolean canEat(Update update) {
      return update instanceof MyUpdate && myFiles.containsAll(((MyUpdate)update).myFiles);
    }
  }
}
