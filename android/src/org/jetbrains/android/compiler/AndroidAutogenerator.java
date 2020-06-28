package org.jetbrains.android.compiler;


import com.android.SdkConstants;
import com.android.sdklib.IAndroidTarget;
import com.android.sdklib.internal.build.BuildConfigGenerator;
import com.android.tools.idea.lang.rs.AndroidRenderscriptFileType;
import com.android.tools.idea.lang.aidl.AidlFileType;
import com.android.tools.idea.model.AndroidModel;
import com.intellij.compiler.impl.CompilerUtil;
import com.intellij.compiler.impl.ModuleCompileScope;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.compiler.CompileContext;
import com.intellij.openapi.compiler.CompilerMessageCategory;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.*;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.*;
import com.intellij.psi.search.FilenameIndex;
import org.jetbrains.android.compiler.tools.AndroidIdl;
import org.jetbrains.android.compiler.tools.AndroidRenderscript;
import org.jetbrains.android.dom.manifest.Manifest;
import org.jetbrains.android.facet.AndroidFacet;
import org.jetbrains.android.facet.AndroidRootUtil;
import org.jetbrains.android.sdk.AndroidPlatform;
import org.jetbrains.android.util.AndroidBundle;
import org.jetbrains.android.util.AndroidBuildCommonUtils;
import org.jetbrains.android.util.AndroidUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

@SuppressWarnings("deprecation")
public class AndroidAutogenerator {
  private static final Logger LOG = Logger.getInstance("#org.jetbrains.android.compiler.AndroidAutogenerator");

  private AndroidAutogenerator() {
  }

  private static boolean toRun(@NotNull AndroidAutogeneratorMode mode,
                               @NotNull AndroidFacet facet,
                               @Nullable ModuleSourceAutogenerating autogenerating,
                               boolean force) {
    if (autogenerating == null || !supportsAutogeneration(facet)) {
      return false;
    }
    if (!force && !facet.getProperties().ENABLE_SOURCES_AUTOGENERATION) {
      return false;
    }
    switch (mode) {
      case AIDL:
      case RENDERSCRIPT:
      case BUILDCONFIG:
        return true;
      default:
        LOG.error("Unknown autogenerator mode " + mode);
        return false;
    }
  }

  public static boolean supportsAutogeneration(@NotNull AndroidFacet facet) {
    // This is a cheap way to figure out that a module has the Android-Gradle facet.
    // Don't generate anything if a module has an Android-Gradle facet.
    return !AndroidModel.isRequired(facet);
  }

  public static void run(@NotNull AndroidAutogeneratorMode mode,
                         @NotNull AndroidFacet facet,
                         @NotNull CompileContext context,
                         boolean force) {
    ModuleSourceAutogenerating autogenerating = ModuleSourceAutogenerating.getInstance(facet);
    if (!toRun(mode, facet, autogenerating, force)) {
      return;
    }
    final Set<String> obsoleteFiles = new HashSet<String>(autogenerating.getAutogeneratedFiles(mode));

    switch (mode) {
      case AIDL:
        runAidl(facet, autogenerating, context);
        break;
      case RENDERSCRIPT:
        runRenderscript(facet, autogenerating, context);
        break;
      case BUILDCONFIG:
        runBuildConfigGenerator(facet, autogenerating, context);
        break;
      default:
        LOG.error("Unknown mode" + mode);
    }
    obsoleteFiles.removeAll(autogenerating.getAutogeneratedFiles(mode));

    for (String path : obsoleteFiles) {
      final File file = new File(path);

      if (file.isFile()) {
        FileUtil.delete(file);
        CompilerUtil.refreshIOFile(file);
      }
    }
  }

  private static void runBuildConfigGenerator(@NotNull AndroidFacet facet,
                                              @NotNull ModuleSourceAutogenerating autogenerating,
                                              @NotNull CompileContext context) {
    final Module module = facet.getModule();

    final BuildconfigAutogenerationItem item = ApplicationManager.getApplication().runReadAction(
      new Computable<BuildconfigAutogenerationItem>() {
        @SuppressWarnings("deprecation")
        @Nullable
        @Override
        public BuildconfigAutogenerationItem compute() {
          if (module.isDisposed() || module.getProject().isDisposed()) {
            return null;
          }

          final String sourceRootPath = AndroidRootUtil.getBuildconfigGenSourceRootPath(facet);
          if (sourceRootPath == null) {
            return null;
          }

          final VirtualFile manifestFile = AndroidRootUtil.getManifestFileForCompiler(facet);
          if (manifestFile == null) {
            context.addMessage(CompilerMessageCategory.ERROR,
                               AndroidBundle.message("android.compilation.error.manifest.not.found", module.getName()), null, -1, -1);
            return null;
          }

          final Manifest manifest = AndroidUtils.loadDomElement(module, manifestFile, Manifest.class);
          if (manifest == null) {
            context.addMessage(CompilerMessageCategory.ERROR, "Cannot parse file", manifestFile.getUrl(), -1, -1);
            return null;
          }

          String packageName = manifest.getPackage().getValue();
          if (packageName != null) {
            packageName = packageName.trim();
          }

          if (packageName == null || packageName.length() <= 0) {
            context.addMessage(CompilerMessageCategory.ERROR, AndroidBundle.message("package.not.found.error"), manifestFile.getUrl(),
                               -1, -1);
            return null;
          }

          if (!AndroidUtils.isValidAndroidPackageName(packageName)) {
            context.addMessage(CompilerMessageCategory.ERROR, AndroidBundle.message("not.valid.package.name.error", packageName),
                               manifestFile.getUrl(), -1, -1);
            return null;
          }
          return new BuildconfigAutogenerationItem(packageName, FileUtil.toSystemDependentName(sourceRootPath));
        }
      });

    if (item == null) {
      return;
    }

    try {
      // hack for IDEA-100046: we need to avoid reporting "condition is always 'true'
      // from data flow inspection, so use non-constant value here
      generateStubClass(item.myPackage, new File(item.mySourceRootOsPath), "BuildConfig",
                        "  public final static boolean DEBUG = Boolean.parseBoolean(null);\n");

      final VirtualFile genSourceRoot = LocalFileSystem.getInstance().findFileByPath(item.mySourceRootOsPath);
      if (genSourceRoot != null) {
        genSourceRoot.refresh(false, true);
      }
      autogenerating.clearAutogeneratedFiles(AndroidAutogeneratorMode.BUILDCONFIG);

      final VirtualFile genFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(
        item.mySourceRootOsPath + '/' + item.myPackage.replace('.', '/') + '/' + BuildConfigGenerator.BUILD_CONFIG_NAME);

      if (genFile != null && genFile.exists()) {
        autogenerating.markFileAutogenerated(AndroidAutogeneratorMode.BUILDCONFIG, genFile);
      }
    }
    catch (final IOException e) {
      LOG.info(e);
      ApplicationManager.getApplication().runReadAction(new Runnable() {
        @Override
        public void run() {
          if (module.getProject().isDisposed()) return;
          context.addMessage(CompilerMessageCategory.ERROR, "I/O error: " + e.getMessage(), null, -1, -1);
        }
      });
    }
  }

  private static void generateStubClass(String aPackage, File outputDir, String className, String content) throws IOException {
    final File packageDir = new File(outputDir.getPath() + '/' + aPackage.replace('.', '/'));
    if (!packageDir.exists() && !packageDir.mkdirs()) {
      throw new IOException("Cannot create directory " + FileUtil.toSystemDependentName(packageDir.getPath()));
    }
    final BufferedWriter writer = new BufferedWriter(new FileWriter(new File(packageDir, className + ".java")));
    try {
      writer.write(
        AndroidBuildCommonUtils.AUTOGENERATED_JAVA_FILE_HEADER +
        "\n\npackage " + aPackage + ";\n\n" +
        "/* This stub is only used by the IDE. It is NOT the " + className + " class actually packed into the APK */\n" +
        "public final class " + className + " {\n" +
        content +
        "}"
      );
    }
    finally {
      writer.close();
    }
  }

  private static void patchAndMarkGeneratedFile(@NotNull AndroidFacet facet,
                                                @NotNull ModuleSourceAutogenerating autogenerating,
                                                @NotNull AndroidAutogeneratorMode mode,
                                                @NotNull VirtualFile vFile) throws IOException {
    final File file = new File(vFile.getPath());
    final String fileText = FileUtil.loadFile(file);
    FileUtil.writeToFile(file, AndroidBuildCommonUtils.AUTOGENERATED_JAVA_FILE_HEADER + "\n\n" + fileText);
    autogenerating.markFileAutogenerated(mode, vFile);
  }

  private static void removeAllFilesWithSameName(@NotNull final Module module, @NotNull final File file, @NotNull String directoryPath) {
    final VirtualFile vFile = LocalFileSystem.getInstance().findFileByIoFile(file);
    final VirtualFile genDir = LocalFileSystem.getInstance().findFileByPath(directoryPath);

    if (vFile == null || genDir == null) {
      return;
    }
    final Collection<VirtualFile> files =
      DumbService.getInstance(module.getProject()).runReadActionInSmartMode(new Computable<Collection<VirtualFile>>() {
        @Nullable
        @Override
        public Collection<VirtualFile> compute() {
          if (module.isDisposed() || module.getProject().isDisposed()) {
            return null;
          }
          return FilenameIndex.getVirtualFilesByName(module.getProject(), file.getName(), module.getModuleScope(false));
        }
      });
    if (files == null) {
      return;
    }

    final List<VirtualFile> filesToDelete = new ArrayList<VirtualFile>();

    for (final VirtualFile f : files) {
      if (!Objects.equals(f, vFile) && VfsUtilCore.isAncestor(genDir, f, true)) {
        filesToDelete.add(f);
      }
    }
    if (filesToDelete.isEmpty()) {
      return;
    }

    ApplicationManager.getApplication().invokeLater(new Runnable() {
      @Override
      public void run() {
        ApplicationManager.getApplication().runWriteAction(new Runnable() {
          @Override
          public void run() {
            for (VirtualFile f : filesToDelete) {
              if (f.isValid() && f.exists()) {
                try {
                  f.delete(module.getProject());
                }
                catch (IOException e) {
                  LOG.debug(e);
                }
              }
            }
          }
        });
      }
    });
  }

  private static void runAidl(@NotNull AndroidFacet facet,
                              @NotNull ModuleSourceAutogenerating autogenerating,
                              @NotNull CompileContext context) {
    final Module module = facet.getModule();
    final ModuleCompileScope moduleCompileScope = new ModuleCompileScope(module, false);
    final VirtualFile[] files = moduleCompileScope.getFiles(AidlFileType.INSTANCE, true);
    final List<IdlAutogenerationItem> items = new ArrayList<IdlAutogenerationItem>();

    for (final VirtualFile file : files) {
      final IdlAutogenerationItem item = ApplicationManager.getApplication().runReadAction(new Computable<IdlAutogenerationItem>() {
        @Nullable
        @Override
        public IdlAutogenerationItem compute() {
          if (module.isDisposed() || module.getProject().isDisposed()) {
            return null;
          }

          final AndroidPlatform androidPlatform = AndroidPlatform.getInstance(facet.getModule());
          final IAndroidTarget target = androidPlatform == null ? null : androidPlatform.getTarget();
          if (target == null) {
            context.addMessage(CompilerMessageCategory.ERROR,
                               AndroidBundle.message("android.compilation.error.specify.platform", module.getName()), null, -1, -1);
            return null;
          }

          final String packageName = AndroidUtils.computePackageName(module, file);
          if (packageName == null) {
            context.addMessage(CompilerMessageCategory.ERROR, "Cannot compute package for file", file.getUrl(), -1, -1);
            return null;
          }

          final String sourceRootPath = AndroidRootUtil.getAidlGenSourceRootPath(facet);
          if (sourceRootPath == null) {
            context.addMessage(CompilerMessageCategory.ERROR,
                               AndroidBundle.message("android.compilation.error.apt.gen.not.specified", module.getName()), null, -1, -1);
            return null;
          }

          final VirtualFile[] sourceRoots = getSourceRootsForModuleAndDependencies(module, false);
          final String[] sourceRootOsPaths = AndroidCompileUtil.toOsPaths(sourceRoots);

          final String outFileOsPath = FileUtil.toSystemDependentName(
            sourceRootPath + '/' + packageName.replace('.', '/') + '/' + file.getNameWithoutExtension() + ".java");

          return new IdlAutogenerationItem(file, target, outFileOsPath, sourceRootOsPaths, sourceRootPath, packageName);
        }
      });

      if (item != null) {
        items.add(item);
      }
    }

    final Set<VirtualFile> filesToCheck = new HashSet<VirtualFile>();

    for (IdlAutogenerationItem item : items) {
      if (new File(FileUtil.toSystemDependentName(item.myFile.getPath())).exists()) {
        filesToCheck.add(item.myFile);
      }
    }

    if (!ensureFilesWritable(module.getProject(), filesToCheck)) {
      return;
    }

    autogenerating.clearAutogeneratedFiles(AndroidAutogeneratorMode.AIDL);

    for (IdlAutogenerationItem item : items) {
      final VirtualFile file = item.myFile;
      final String fileOsPath = FileUtil.toSystemDependentName(file.getPath());

      try {
        final Map<CompilerMessageCategory, List<String>> messages = AndroidCompileUtil.toCompilerMessageCategoryKeys(
          AndroidIdl.execute(item.myTarget, fileOsPath, item.myOutFileOsPath, item.mySourceRootOsPaths));

        ApplicationManager.getApplication().runReadAction(new Runnable() {
          @Override
          public void run() {
            if (module.getProject().isDisposed()) return;

            for (CompilerMessageCategory category : messages.keySet()) {
              List<String> messageList = messages.get(category);
              for (String message : messageList) {
                context.addMessage(category, message, file.getUrl(), -1, -1);
              }
            }
          }
        });

        removeDuplicateClasses(module, item.myPackage, new File(item.myOutFileOsPath), item.myOutDirOsPath);

        final VirtualFile genDir = LocalFileSystem.getInstance().findFileByPath(item.myOutDirOsPath);
        if (genDir != null) {
          genDir.refresh(false, true);
        }

        final VirtualFile outFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(item.myOutFileOsPath);
        if (outFile != null && outFile.exists()) {
          patchAndMarkGeneratedFile(facet, autogenerating, AndroidAutogeneratorMode.AIDL, outFile);
        }
      }
      catch (final IOException e) {
        LOG.info(e);
        ApplicationManager.getApplication().runReadAction(new Runnable() {
          @Override
          public void run() {
            if (module.getProject().isDisposed()) return;
            context.addMessage(CompilerMessageCategory.ERROR, e.getMessage(), file.getUrl(), -1, -1);
          }
        });
      }
    }
  }

  private static void runRenderscript(@NotNull AndroidFacet facet,
                                      @NotNull ModuleSourceAutogenerating autogenerating,
                                      @NotNull CompileContext context) {
    final Module module = facet.getModule();

    final ModuleCompileScope moduleCompileScope = new ModuleCompileScope(module, false);
    final VirtualFile[] files = moduleCompileScope.getFiles(AndroidRenderscriptFileType.INSTANCE, true);

    autogenerating.clearAutogeneratedFiles(AndroidAutogeneratorMode.RENDERSCRIPT);

    for (final VirtualFile file : files) {
      final RenderscriptAutogenerationItem item =
        ApplicationManager.getApplication().runReadAction(new Computable<RenderscriptAutogenerationItem>() {
          @SuppressWarnings("deprecation")
          @Nullable
          @Override
          public RenderscriptAutogenerationItem compute() {
            final AndroidPlatform platform = AndroidPlatform.getInstance(facet.getModule());
            if (platform == null) {
              context.addMessage(CompilerMessageCategory.ERROR,
                                 AndroidBundle.message("android.compilation.error.specify.platform", module.getName()), null, -1, -1);
              return null;
            }

            final IAndroidTarget target = platform.getTarget();
            final String sdkLocation = platform.getSdkData().getPath();

            final String packageName = AndroidUtils.computePackageName(module, file);
            if (packageName == null) {
              context.addMessage(CompilerMessageCategory.ERROR, "Cannot compute package for file", file.getUrl(), -1, -1);
              return null;
            }

            final String resourceDirPath = AndroidRootUtil.getResourceDirPath(facet);
            assert resourceDirPath != null;

            final String sourceRootPath = AndroidRootUtil.getRenderscriptGenSourceRootPath(facet);
            if (sourceRootPath == null) {
              return null;
            }

            final String rawDirPath = resourceDirPath + '/' + SdkConstants.FD_RES_RAW;

            return new RenderscriptAutogenerationItem(sdkLocation, target, sourceRootPath, rawDirPath);
          }
        });

      if (item == null) {
        continue;
      }

      File tempOutDir = null;

      try {
        tempOutDir = FileUtil.createTempDirectory("android_renderscript_autogeneration", "tmp");
        final VirtualFile vTempOutDir = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(tempOutDir);

        final String depFolderPath =
          vTempOutDir != null ? getDependencyFolder(context.getProject(), file, vTempOutDir) : null;

        final Map<CompilerMessageCategory, List<String>> messages = AndroidCompileUtil.toCompilerMessageCategoryKeys(
          AndroidRenderscript
            .execute(item.mySdkLocation, item.myTarget, file.getPath(), tempOutDir.getPath(), depFolderPath,
                     item.myRawDirPath));

        if (messages.get(CompilerMessageCategory.ERROR).isEmpty()) {
          final List<File> newFiles = new ArrayList<File>();
          AndroidBuildCommonUtils.moveAllFiles(tempOutDir, new File(item.myGenDirPath), newFiles);

          for (File newFile : newFiles) {
            final VirtualFile newVFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(newFile);

            if (newVFile != null) {
              patchAndMarkGeneratedFile(facet, autogenerating, AndroidAutogeneratorMode.RENDERSCRIPT, newVFile);
            }
          }

          final File bcFile = new File(item.myRawDirPath, FileUtil.getNameWithoutExtension(file.getName()) + ".bc");
          final VirtualFile vBcFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(bcFile);

          if (vBcFile != null) {
            autogenerating.markFileAutogenerated(AndroidAutogeneratorMode.RENDERSCRIPT, vBcFile);
          }
        }

        ApplicationManager.getApplication().runReadAction(new Runnable() {
          @Override
          public void run() {
            if (module.getProject().isDisposed()) {
              return;
            }

            for (final CompilerMessageCategory category : messages.keySet()) {
              final List<String> messageList = messages.get(category);
              for (final String message : messageList) {
                context.addMessage(category, message, file.getUrl(), -1, -1);
              }
            }
          }
        });

        final VirtualFile genDir = LocalFileSystem.getInstance().findFileByPath(item.myGenDirPath);
        if (genDir != null) {
          genDir.refresh(false, true);
        }
      }
      catch (final IOException e) {
        LOG.info(e);
        ApplicationManager.getApplication().runReadAction(new Runnable() {
          @Override
          public void run() {
            if (module.getProject().isDisposed()) return;
            context.addMessage(CompilerMessageCategory.ERROR, e.getMessage(), file.getUrl(), -1, -1);
          }
        });
      }
      finally {
        if (tempOutDir != null) {
          FileUtil.delete(tempOutDir);
        }
      }
    }
  }

  private static boolean ensureFilesWritable(@NotNull final Project project, @NotNull final Collection<VirtualFile> filesToCheck) {
    if (filesToCheck.isEmpty()) {
      return true;
    }
    final boolean[] run = {false};

    ApplicationManager.getApplication().invokeAndWait(new Runnable() {
      @Override
      public void run() {
        run[0] = !project.isDisposed() &&
                 ReadonlyStatusHandler.ensureFilesWritable(project, filesToCheck.toArray(VirtualFile.EMPTY_ARRAY));
      }
    });

    return run[0];
  }

  private static void removeDuplicateClasses(@NotNull final Module module,
                                             @NotNull final String aPackage,
                                             @NotNull final File generatedFile,
                                             @NotNull final String sourceRootPath) {
    if (generatedFile.exists()) {
      ApplicationManager.getApplication().runReadAction(new Runnable() {
        @Override
        public void run() {
          if (module.getProject().isDisposed() || module.isDisposed()) {
            return;
          }
          String className = FileUtil.getNameWithoutExtension(generatedFile);
          AndroidCompileUtil.removeDuplicatingClasses(module, aPackage, className, generatedFile, sourceRootPath);
        }
      });
    }
  }

  private static void fillSourceRoots(@NotNull Module module,
                                      @NotNull Set<Module> visited,
                                      @NotNull Set<VirtualFile> result,
                                      boolean includingTests) {
    visited.add(module);
    final AndroidFacet facet = AndroidFacet.getInstance(module);
    VirtualFile resDir = facet != null ? AndroidRootUtil.getResourceDir(facet) : null;
    ModuleRootManager manager = ModuleRootManager.getInstance(module);
    for (VirtualFile sourceRoot : manager.getSourceRoots(includingTests)) {
      if (!Objects.equals(resDir, sourceRoot)) {
        result.add(sourceRoot);
      }
    }
    for (OrderEntry entry : manager.getOrderEntries()) {
      if (entry instanceof ModuleOrderEntry) {
        ModuleOrderEntry moduleOrderEntry = (ModuleOrderEntry)entry;
        DependencyScope scope = moduleOrderEntry.getScope();
        if (scope == DependencyScope.COMPILE) {
          Module depModule = moduleOrderEntry.getModule();
          if (depModule != null && !visited.contains(depModule)) {
            fillSourceRoots(depModule, visited, result, false);
          }
        }
      }
    }
  }

  @NotNull
  public static VirtualFile[] getSourceRootsForModuleAndDependencies(@NotNull Module module, boolean includingTests) {
    Set<VirtualFile> result = new HashSet<VirtualFile>();
    fillSourceRoots(module, new HashSet<Module>(), result, includingTests);
    return VfsUtil.toVirtualFileArray(result);
  }

  @Nullable
  static String getDependencyFolder(@NotNull final Project project,
                                    @NotNull final VirtualFile sourceFile,
                                    @NotNull final VirtualFile genFolder) {
    final ProjectFileIndex index = ProjectRootManager.getInstance(project).getFileIndex();

    final VirtualFile sourceRoot = index.getSourceRootForFile(sourceFile);
    if (sourceRoot == null) {
      return null;
    }

    final VirtualFile parent = sourceFile.getParent();
    if (Objects.equals(parent, sourceRoot)) {
      return genFolder.getPath();
    }

    final String relativePath = VfsUtilCore.getRelativePath(sourceFile.getParent(), sourceRoot, '/');
    assert relativePath != null;
    return genFolder.getPath() + '/' + relativePath;
  }

  private static class AptAutogenerationItem {
    final String myPackage;
    final String myOutputDirOsPath;
    final Map<String, String> myGenFileRelPath2package;

    private AptAutogenerationItem(@NotNull String aPackage,
                                  @NotNull String outputDirOsPath,
                                  @NotNull Map<String, String> genFileRelPath2package) {
      myPackage = aPackage;
      myOutputDirOsPath = outputDirOsPath;
      myGenFileRelPath2package = genFileRelPath2package;
    }
  }

  private static class IdlAutogenerationItem {
    final VirtualFile myFile;
    final IAndroidTarget myTarget;
    final String myOutFileOsPath;
    final String[] mySourceRootOsPaths;
    final String myOutDirOsPath;
    final String myPackage;

    private IdlAutogenerationItem(@NotNull VirtualFile file,
                                  @NotNull IAndroidTarget target,
                                  @NotNull String outFileOsPath,
                                  @NotNull String[] sourceRootOsPaths,
                                  @NotNull String outDirOsPath,
                                  @NotNull String aPackage) {
      myFile = file;
      myTarget = target;
      myOutFileOsPath = outFileOsPath;
      mySourceRootOsPaths = sourceRootOsPaths;
      myOutDirOsPath = outDirOsPath;
      myPackage = aPackage;
    }
  }

  private static class RenderscriptAutogenerationItem {
    final String mySdkLocation;
    final IAndroidTarget myTarget;
    final String myGenDirPath;
    final String myRawDirPath;

    private RenderscriptAutogenerationItem(@NotNull String sdkLocation,
                                           @NotNull IAndroidTarget target,
                                           @NotNull String genDirPath,
                                           @NotNull String rawDirPath) {
      mySdkLocation = sdkLocation;
      myTarget = target;
      myGenDirPath = genDirPath;
      myRawDirPath = rawDirPath;
    }
  }

  private static class BuildconfigAutogenerationItem {
    final String myPackage;
    final String mySourceRootOsPath;

    private BuildconfigAutogenerationItem(@NotNull String aPackage, @NotNull String sourceRootOsPath) {
      myPackage = aPackage;
      mySourceRootOsPath = sourceRootOsPath;
    }
  }
}
