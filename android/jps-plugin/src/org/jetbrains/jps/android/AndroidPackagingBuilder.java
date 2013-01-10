package org.jetbrains.jps.android;

import com.android.sdklib.IAndroidTarget;
import com.intellij.openapi.util.Condition;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.util.ArrayUtil;
import com.intellij.util.PathUtilRt;
import com.intellij.util.containers.HashMap;
import com.intellij.util.containers.HashSet;
import org.jetbrains.android.compiler.tools.AndroidApkBuilder;
import org.jetbrains.android.compiler.tools.AndroidApt;
import org.jetbrains.android.util.AndroidCommonUtils;
import org.jetbrains.android.util.AndroidCompilerMessageKind;
import org.jetbrains.android.util.AndroidNativeLibData;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.jps.ProjectPaths;
import org.jetbrains.jps.android.builder.AndroidPackagingBuildTarget;
import org.jetbrains.jps.android.model.JpsAndroidModuleExtension;
import org.jetbrains.jps.builders.BuildOutputConsumer;
import org.jetbrains.jps.builders.BuildRootDescriptor;
import org.jetbrains.jps.builders.BuildTarget;
import org.jetbrains.jps.builders.DirtyFilesHolder;
import org.jetbrains.jps.incremental.CompileContext;
import org.jetbrains.jps.incremental.ProjectBuildException;
import org.jetbrains.jps.incremental.TargetBuilder;
import org.jetbrains.jps.incremental.messages.BuildMessage;
import org.jetbrains.jps.incremental.messages.CompilerMessage;
import org.jetbrains.jps.incremental.messages.ProgressMessage;
import org.jetbrains.jps.incremental.storage.BuildDataManager;
import org.jetbrains.jps.indices.IgnoredFileIndex;
import org.jetbrains.jps.model.JpsProject;
import org.jetbrains.jps.model.java.JpsJavaExtensionService;
import org.jetbrains.jps.model.java.compiler.JpsCompilerExcludes;
import org.jetbrains.jps.model.module.JpsModule;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.util.*;

/**
 * @author Eugene.Kudelevsky
 */
public class AndroidPackagingBuilder extends TargetBuilder<BuildRootDescriptor, AndroidPackagingBuildTarget> {
  @NonNls private static final String BUILDER_NAME = "Android Packager";
  @NonNls private static final String RELEASE_SUFFIX = ".release";
  @NonNls private static final String UNSIGNED_SUFFIX = ".unsigned";


  public AndroidPackagingBuilder() {
    super(Collections.singletonList(AndroidPackagingBuildTarget.MyTargetType.INSTANCE));
  }

  @NotNull
  @Override
  public String getPresentableName() {
    return BUILDER_NAME;
  }

  @Override
  public void build(@NotNull AndroidPackagingBuildTarget target,
                    @NotNull DirtyFilesHolder<BuildRootDescriptor, AndroidPackagingBuildTarget> holder,
                    @NotNull BuildOutputConsumer outputConsumer,
                    @NotNull CompileContext context) throws ProjectBuildException {
    if (AndroidJpsUtil.isLightBuild(context)) {
      return;
    }
    final JpsModule module = target.getModule();

    // todo: simplify
    final Collection<JpsModule> modules = Collections.singletonList(module);

    final Map<JpsModule, AndroidFileSetState> resourcesStates = new HashMap<JpsModule, AndroidFileSetState>();
    final Map<JpsModule, AndroidFileSetState> assetsStates = new HashMap<JpsModule, AndroidFileSetState>();
    final Map<JpsModule, File> manifestFiles = new HashMap<JpsModule, File>();

    try {
      fillStates(modules, resourcesStates, assetsStates, manifestFiles);

      if (!doCaching(target, context, modules, resourcesStates)) {
        throw new ProjectBuildException();
      }

      if (!doResourcePackaging(target, context, modules, resourcesStates, assetsStates, manifestFiles)) {
        throw new ProjectBuildException();
      }

      if (!doPackaging(target, context, modules, outputConsumer)) {
        throw new ProjectBuildException();
      }
    }
    catch (ProjectBuildException e) {
      throw e;
    }
    catch (Exception e) {
      AndroidJpsUtil.handleException(context, e, BUILDER_NAME);
    }
  }

  @SuppressWarnings("unchecked")
  private static void fillStates(@NotNull Collection<JpsModule> modules,
                                 @NotNull Map<JpsModule, AndroidFileSetState> resourcesStates,
                                 @NotNull Map<JpsModule, AndroidFileSetState> assetsStates,
                                 @NotNull Map<JpsModule, File> manifestFiles) throws IOException {
    for (JpsModule module : modules) {
      final JpsAndroidModuleExtension extension = AndroidJpsUtil.getExtension(module);

      if (extension == null) {
        continue;
      }
      final List<String> resourceDirs = new ArrayList<String>();
      final List<String> assetsDirs = new ArrayList<String>();
      final File resourceDir = extension.getResourceDir();

      if (resourceDir != null) {
        resourceDirs.add(resourceDir.getPath());
      }
      final File assetsDir = extension.getAssetsDir();

      if (assetsDir != null) {
        assetsDirs.add(assetsDir.getPath());
      }
      final File manifestFile = AndroidJpsUtil.getManifestFileForCompilationPath(extension);

      if (manifestFile != null) {
        manifestFiles.put(module, manifestFile);
      }

      for (JpsAndroidModuleExtension libExtension : AndroidJpsUtil.getAllAndroidDependencies(module, true)) {
        final File libResDir = libExtension.getResourceDir();

        if (libResDir != null) {
          resourceDirs.add(libResDir.getPath());
        }
        final File libAssetsDir = libExtension.getAssetsDir();

        if (libAssetsDir != null && extension.isIncludeAssetsFromLibraries()) {
          assetsDirs.add(libAssetsDir.getPath());
        }
      }
      resourcesStates.put(module, new AndroidFileSetState(resourceDirs, Condition.TRUE, true));
      assetsStates.put(module, new AndroidFileSetState(assetsDirs, Condition.TRUE, true));
    }
  }

  private static boolean doCaching(@NotNull BuildTarget<?> target,
                                   @NotNull CompileContext context,
                                   @NotNull Collection<JpsModule> modules,
                                   @NotNull Map<JpsModule, AndroidFileSetState> module2state) throws IOException {
    boolean success = true;
    final AndroidFileSetStorage.Provider provider = new AndroidFileSetStorage.Provider("resource_caching");
    final AndroidFileSetStorage storage = context.getProjectDescriptor().dataManager.getStorage(target, provider);

    for (JpsModule module : modules) {
      final AndroidFileSetState state = module2state.get(module);

      try {
        if (!runPngCaching(context, module, storage, state)) {
          success = false;
        }
      }
      catch (IOException e) {
        AndroidJpsUtil.reportExceptionError(context, null, e, BUILDER_NAME);
      }
    }
    return success;
  }

  private static boolean runPngCaching(@NotNull CompileContext context,
                                       @NotNull JpsModule module,
                                       @NotNull AndroidFileSetStorage storage,
                                       @Nullable AndroidFileSetState state) throws IOException {
    if (context.isMake()) {
      final AndroidFileSetState savedState = storage.getState(module.getName());
      if (savedState != null && savedState.equalsTo(state)) {
        return true;
      }
    }

    final JpsAndroidModuleExtension extension = AndroidJpsUtil.getExtension(module);
    if (extension == null) {
      return true;
    }

    context.processMessage(new CompilerMessage(BUILDER_NAME, BuildMessage.Kind.INFO,
                                               AndroidJpsBundle.message("android.jps.progress.res.caching", module.getName())));

    final File resourceDir = AndroidJpsUtil.getResourceDirForCompilationPath(extension);
    if (resourceDir == null) {
      return true;
    }

    final AndroidPlatform platform = AndroidJpsUtil.getAndroidPlatform(module, context, BUILDER_NAME);
    if (platform == null) {
      return false;
    }

    final File resCacheDir = AndroidJpsUtil.getResourcesCacheDir(context, module);

    if (context.isProjectRebuild() && resCacheDir.exists()) {
      if (!FileUtil.delete(resCacheDir)) {
        context.processMessage(new CompilerMessage(BUILDER_NAME, BuildMessage.Kind.ERROR,
                                                   AndroidJpsBundle.message("android.jps.cannot.create.directory", resCacheDir.getPath())));
        return false;
      }
    }

    if (!resCacheDir.exists()) {
      if (!resCacheDir.mkdirs()) {
        context.processMessage(new CompilerMessage(BUILDER_NAME, BuildMessage.Kind.ERROR,
                                                   AndroidJpsBundle.message("android.jps.cannot.create.directory", resCacheDir.getPath())));
        return false;    
      }
    }

    final IAndroidTarget target = platform.getTarget();

    final Map<AndroidCompilerMessageKind, List<String>> messages =
      AndroidApt.crunch(target, Collections.singletonList(resourceDir.getPath()), resCacheDir.getPath());

    AndroidJpsUtil.addMessages(context, messages, BUILDER_NAME, module.getName());

    final boolean success = messages.get(AndroidCompilerMessageKind.ERROR).isEmpty();
    storage.update(module.getName(), success ? state : null);
    return success;
  }

  private static boolean doResourcePackaging(@NotNull BuildTarget<?> target,
                                             @NotNull CompileContext context,
                                             @NotNull Collection<JpsModule> modules,
                                             @NotNull Map<JpsModule, AndroidFileSetState> resourcesStates,
                                             @NotNull Map<JpsModule, AndroidFileSetState> assetsStates,
                                             @NotNull Map<JpsModule, File> manifestFiles) throws IOException {
    boolean success = true;
    final Map<JpsModule, AndroidFileSetState> manifestStates =
      new HashMap<JpsModule, AndroidFileSetState>();

    for (JpsModule module : modules) {
      final File manifestFile = manifestFiles.get(module);
      manifestStates.put(module, new AndroidFileSetState(
        manifestFile != null ? Collections.singletonList(manifestFile.getPath()) :
        Collections.<String>emptyList(), Condition.TRUE, false));
    }

    final BuildDataManager dataManager = context.getProjectDescriptor().dataManager;
    final boolean releaseBuild = AndroidJpsUtil.isReleaseBuild(context);

    final String resourcesStorageName = releaseBuild ? "resources_packaging_release" : "resources_packaging_dev";
    final AndroidFileSetStorage.Provider resourcesStorageProvider = new AndroidFileSetStorage.Provider(resourcesStorageName);
    final AndroidFileSetStorage resourcesStorage = dataManager.getStorage(target, resourcesStorageProvider);

    final String assetsStorageName = releaseBuild ? "assets_packaging_release" : "assets_packaging_dev";
    final AndroidFileSetStorage.Provider assetsStorageProvider = new AndroidFileSetStorage.Provider(assetsStorageName);
    final AndroidFileSetStorage assetsStorage = dataManager.getStorage(target, assetsStorageProvider);

    // todo: replace by target-based storage!
    final String manifestStorageName = releaseBuild ? "manifest_packaging_release" : "manifest_packaging_dev";
    final AndroidFileSetStorage.Provider manifestStorageProvider = new AndroidFileSetStorage.Provider(manifestStorageName);
    final AndroidFileSetStorage manifestStorage = dataManager.getStorage(target, manifestStorageProvider);

    final Set<JpsModule> modulesToUpdateState = new HashSet<JpsModule>();

    for (JpsModule module : modules) {
      final JpsAndroidModuleExtension extension = AndroidJpsUtil.getExtension(module);
      if (extension == null) {
        continue;
      }

      final File manifestFile = manifestFiles.get(module);
      if (manifestFile == null) {
        context.processMessage(new CompilerMessage(BUILDER_NAME, BuildMessage.Kind.ERROR,
                                                   AndroidJpsBundle.message("android.jps.errors.manifest.not.found", module.getName())));
        success = false;
        continue;
      }
      boolean updateState = true;

      if (!extension.isLibrary() &&
          !(context.isMake() &&
            checkUpToDate(module, resourcesStates, resourcesStorage) &&
            checkUpToDate(module, assetsStates, assetsStorage) &&
            checkUpToDate(module, manifestStates, manifestStorage))) {

        updateState = packageResources(extension, manifestFile, context);

        if (!updateState) {
          success = false;
        }
      }
      if (updateState) {
        modulesToUpdateState.add(module);
      }
    }

    for (JpsModule module : modules) {
      final boolean updateState = modulesToUpdateState.contains(module);
      resourcesStorage.update(module.getName(), updateState ? resourcesStates.get(module) : null);
      assetsStorage.update(module.getName(), updateState ? assetsStates.get(module) : null);
      manifestStorage.update(module.getName(), updateState ? manifestStates.get(module) : null);
    }
    return success;
  }

  private static boolean doPackaging(@NotNull BuildTarget<?> target,
                                     @NotNull CompileContext context,
                                     @NotNull Collection<JpsModule> modules,
                                     @NotNull BuildOutputConsumer outputConsumer) throws IOException {
    final boolean release = AndroidJpsUtil.isReleaseBuild(context);
    final BuildDataManager dataManager = context.getProjectDescriptor().dataManager;

    boolean success = true;

    final String apkFileSetStorageName = "apk_builder_file_set" + (release ? "_release" : "_dev");
    final AndroidFileSetStorage.Provider fileSetStorageProvider = new AndroidFileSetStorage.Provider(apkFileSetStorageName);
    final AndroidFileSetStorage apkFileSetStorage = dataManager.getStorage(target, fileSetStorageProvider);

    final String apkBuilderStateStorageName = "apk_builder_config" + (release ? "_release" : "_dev");
    final AndroidApkBuilderConfigStateStorage.Provider builderStateStoragetProvider =
      new AndroidApkBuilderConfigStateStorage.Provider(apkBuilderStateStorageName);
    final AndroidApkBuilderConfigStateStorage apkBuilderConfigStateStorage =
      dataManager.getStorage(target, builderStateStoragetProvider);

    for (JpsModule module : modules) {
      try {
        if (!doPackagingForModule(context, module, apkFileSetStorage, apkBuilderConfigStateStorage,
                                  release, outputConsumer)) {
          success = false;
        }
      }
      catch (IOException e) {
        AndroidJpsUtil.reportExceptionError(context, null, e, BUILDER_NAME);
        success = false;
      }
    }
    return success;
  }

  private static boolean doPackagingForModule(@NotNull CompileContext context,
                                              @NotNull JpsModule module,
                                              @NotNull AndroidFileSetStorage apkFileSetStorage,
                                              @NotNull AndroidApkBuilderConfigStateStorage apkBuilderConfigStateStorage,
                                              boolean release,
                                              @NotNull BuildOutputConsumer outputConsumer) throws IOException {
    final JpsAndroidModuleExtension extension = AndroidJpsUtil.getExtension(module);
    if (extension == null || extension.isLibrary()) {
      return true;
    }

    final String[] sourceRoots = AndroidJpsUtil.toPaths(AndroidJpsUtil.getSourceRootsForModuleAndDependencies(module));

    final File intArtifactsDir = AndroidJpsUtil.getDirectoryForIntermediateArtifacts(context, module);

    final File moduleOutputDir = ProjectPaths.getModuleOutputDir(module, false);
    if (moduleOutputDir == null) {
      context.processMessage(new CompilerMessage(BUILDER_NAME, BuildMessage.Kind.ERROR, AndroidJpsBundle
        .message("android.jps.errors.output.dir.not.specified", module.getName())));
      return false;
    }

    final AndroidPlatform platform = AndroidJpsUtil.getAndroidPlatform(module, context, BUILDER_NAME);
    if (platform == null) {
      return false;
    }

    final Set<String> externalJarsSet = AndroidJpsUtil.getExternalLibraries(context, module, platform);
    final File resPackage = getPackagedResourcesFile(module, intArtifactsDir);

    final File classesDexFile = new File(intArtifactsDir.getPath(), AndroidCommonUtils.CLASSES_FILE_NAME);

    final String sdkPath = platform.getSdk().getHomePath();
    final String outputPath = AndroidJpsUtil.getApkPath(extension, moduleOutputDir);
    if (outputPath == null) {
      context.processMessage(new CompilerMessage(BUILDER_NAME, BuildMessage.Kind.ERROR, AndroidJpsBundle
        .message("android.jps.errors.cannot.compute.output.apk", module.getName())));
      return false;
    }
    final String customKeyStorePath = FileUtil.toSystemDependentName(extension.getCustomDebugKeyStorePath());
    final String[] nativeLibDirs = collectNativeLibsFolders(extension);

    final String resPackagePath = release ? resPackage.getPath() + RELEASE_SUFFIX : resPackage.getPath();

    final String outputApkPath = release
                                 ? AndroidCommonUtils.addSuffixToFileName(outputPath, UNSIGNED_SUFFIX)
                                 : outputPath;

    final String classesDexFilePath = classesDexFile.getPath();
    final String[] externalJars = ArrayUtil.toStringArray(externalJarsSet);

    final List<AndroidNativeLibData> additionalNativeLibs = extension.getAdditionalNativeLibs();

    final AndroidFileSetState currentFileSetState =
      buildCurrentApkBuilderState(context.getProjectDescriptor().getProject(), resPackagePath, classesDexFilePath, nativeLibDirs, sourceRoots,
                                  externalJars, release);

    final AndroidApkBuilderConfigState currentApkBuilderConfigState =
      new AndroidApkBuilderConfigState(outputApkPath, customKeyStorePath, additionalNativeLibs);

    if (context.isMake()) {
      final AndroidFileSetState savedApkFileSetState = apkFileSetStorage.getState(module.getName());
      final AndroidApkBuilderConfigState savedApkBuilderConfigState = apkBuilderConfigStateStorage.getState(module.getName());

      if (currentFileSetState.equalsTo(savedApkFileSetState) &&
          currentApkBuilderConfigState.equalsTo(savedApkBuilderConfigState)) {
        return true;
      }
    }
    context
      .processMessage(new ProgressMessage(AndroidJpsBundle.message("android.jps.progress.packaging", AndroidJpsUtil.getApkName(module))));

    final Map<AndroidCompilerMessageKind, List<String>> messages = AndroidApkBuilder
      .execute(resPackagePath, classesDexFilePath, sourceRoots, externalJars, nativeLibDirs, additionalNativeLibs,
               outputApkPath, release, sdkPath, customKeyStorePath, new MyExcludedSourcesFilter(context.getProjectDescriptor().getProject()));

    if (messages.get(AndroidCompilerMessageKind.ERROR).size() == 0) {
      final File dst = new File(
        AndroidCommonUtils.addSuffixToFileName(outputPath, AndroidCommonUtils.ANDROID_FINAL_PACKAGE_FOR_ARTIFACT_SUFFIX));
      FileUtil.copy(new File(outputApkPath), dst);
      outputConsumer.registerOutputFile(dst, Collections.<String>emptyList());
    }
    AndroidJpsUtil.addMessages(context, messages, BUILDER_NAME, module.getName());
    final boolean success = messages.get(AndroidCompilerMessageKind.ERROR).isEmpty();

    apkFileSetStorage.update(module.getName(), success ? currentFileSetState : null);
    apkBuilderConfigStateStorage.update(module.getName(), success ? currentApkBuilderConfigState : null);
    return success;
  }

  @SuppressWarnings("unchecked")
  private static AndroidFileSetState buildCurrentApkBuilderState(@NotNull JpsProject project,
                                                                 @NotNull String resPackagePath,
                                                                 @NotNull String classesDexFilePath,
                                                                 @NotNull String[] nativeLibDirs,
                                                                 @NotNull String[] sourceRoots,
                                                                 @NotNull String[] externalJars,
                                                                 boolean release) {
    final List<String> roots = new ArrayList<String>();
    roots.add(resPackagePath);
    roots.add(classesDexFilePath);
    roots.addAll(Arrays.asList(externalJars));

    for (String sourceRootPath : sourceRoots) {
      final List<File> files = new ArrayList<File>();
      AndroidApkBuilder.collectStandardSourceFolderResources(new File(sourceRootPath), files, new MyExcludedSourcesFilter(project));
      roots.addAll(AndroidJpsUtil.toPaths(files));
    }

    for (String nativeLibDir : nativeLibDirs) {
      final List<File> files = new ArrayList<File>();
      AndroidApkBuilder.collectNativeLibraries(new File(nativeLibDir), files, !release);
      roots.addAll(AndroidJpsUtil.toPaths(files));
    }

    return new AndroidFileSetState(roots, Condition.TRUE, false);
  }

  @NotNull
  private static String[] collectNativeLibsFolders(@NotNull JpsAndroidModuleExtension extension) throws IOException {
    final List<String> result = new ArrayList<String>();
    final File libsDir = extension.getNativeLibsDir();

    if (libsDir != null) {
      result.add(libsDir.getPath());
    }

    for (JpsAndroidModuleExtension depExtension : AndroidJpsUtil.getAllAndroidDependencies(extension.getModule(), true)) {
      final File depLibsDir = depExtension.getNativeLibsDir();
      if (depLibsDir != null) {
        result.add(depLibsDir.getPath());
      }
    }
    return ArrayUtil.toStringArray(result);
  }

  private static boolean checkUpToDate(@NotNull JpsModule module,
                                       @NotNull Map<JpsModule, AndroidFileSetState> module2state,
                                       @NotNull AndroidFileSetStorage storage) throws IOException {
    final AndroidFileSetState moduleState = module2state.get(module);
    final AndroidFileSetState savedState = storage.getState(module.getName());
    return savedState != null && savedState.equalsTo(moduleState);
  }

  private static boolean packageResources(@NotNull JpsAndroidModuleExtension extension, @NotNull File manifestFile, @NotNull CompileContext context) {
    final JpsModule module = extension.getModule();

    try {
      context.processMessage(new ProgressMessage(AndroidJpsBundle.message("android.jps.progress.packaging.resources", module.getName())));

      final ArrayList<String> assetsDirPaths = new ArrayList<String>();
      collectAssetDirs(extension, assetsDirPaths);

      File outputDir = AndroidJpsUtil.getDirectoryForIntermediateArtifacts(context, module);
      outputDir = AndroidJpsUtil.createDirIfNotExist(outputDir, context, BUILDER_NAME);
      if (outputDir == null) {
        return false;
      }

      final AndroidPlatform platform = AndroidJpsUtil.getAndroidPlatform(module, context, BUILDER_NAME);
      if (platform == null) {
        return false;
      }
      final IAndroidTarget target = platform.getTarget();

      final String outputFilePath = getPackagedResourcesFile(module, outputDir).getPath();
      final String[] resourceDirPaths = AndroidJpsUtil.collectResourceDirsForCompilation(extension, true, context);

      return doPackageResources(context, manifestFile, target, resourceDirPaths, ArrayUtil.toStringArray(assetsDirPaths), outputFilePath,
                                AndroidJpsUtil.isReleaseBuild(context), module.getName());
    }
    catch (IOException e) {
      AndroidJpsUtil.reportExceptionError(context, null, e, BUILDER_NAME);
      return false;
    }
  }

  private static boolean doPackageResources(@NotNull final CompileContext context,
                                            @NotNull File manifestFile,
                                            @NotNull IAndroidTarget target,
                                            @NotNull String[] resourceDirPaths,
                                            @NotNull String[] assetsDirPaths,
                                            @NotNull String outputFilePath,
                                            boolean releasePackage,
                                            @NotNull String moduleName) {
    try {
      final String outputPath = releasePackage
                                ? outputFilePath + RELEASE_SUFFIX
                                : outputFilePath;

      final IgnoredFileIndex ignoredFileIndex = context.getProjectDescriptor().getIgnoredFileIndex();

      final Map<AndroidCompilerMessageKind, List<String>> messages = AndroidApt
        .packageResources(target, -1, manifestFile.getPath(), resourceDirPaths, assetsDirPaths, outputPath, null,
                          !releasePackage, 0, new FileFilter() {
          @Override
          public boolean accept(File pathname) {
            return !ignoredFileIndex.isIgnored(PathUtilRt.getFileName(pathname.getPath()));
          }
        });

      AndroidJpsUtil.addMessages(context, messages, BUILDER_NAME, moduleName);
      return messages.get(AndroidCompilerMessageKind.ERROR).size() == 0;
    }
    catch (final IOException e) {
      AndroidJpsUtil.reportExceptionError(context, null, e, BUILDER_NAME);
      return false;
    }
  }

  private static void collectAssetDirs(@NotNull JpsAndroidModuleExtension extension, @NotNull List<String> result) throws IOException {
    final File assetsDir = extension.getAssetsDir();
    
    if (assetsDir != null) {
      result.add(assetsDir.getPath());
    }

    if (extension.isIncludeAssetsFromLibraries()) {
      for (JpsAndroidModuleExtension depExtension : AndroidJpsUtil.getAllAndroidDependencies(extension.getModule(), true)) {
        final File depAssetsDir = depExtension.getAssetsDir();

        if (depAssetsDir != null) {
          result.add(depAssetsDir.getPath());
        }
      }
    }
  }

  @NotNull
  private static File getPackagedResourcesFile(@NotNull JpsModule module, @NotNull File outputDir) {
    return new File(outputDir.getPath(), module.getName() + ".apk.res");
  }

  private static class MyExcludedSourcesFilter implements Condition<File> {
    private final JpsCompilerExcludes myExcludes;

    public MyExcludedSourcesFilter(@NotNull JpsProject project) {
      myExcludes = JpsJavaExtensionService.getInstance().getOrCreateCompilerConfiguration(project).getCompilerExcludes();
    }

    @Override
    public boolean value(File file) {
      return !myExcludes.isExcluded(file);
    }
  }
}
