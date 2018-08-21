/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.android.tools.idea.gradle.project.sync.errors;

import com.android.repository.Revision;
import com.android.repository.api.LocalPackage;
import com.android.repository.api.RemotePackage;
import com.android.repository.api.RepoManager;
import com.android.sdklib.repository.AndroidSdkHandler;
import com.android.tools.idea.gradle.project.sync.hyperlink.InstallCMakeHyperlink;
import com.android.tools.idea.project.hyperlink.NotificationHyperlink;
import com.android.tools.idea.sdk.AndroidSdks;
import com.android.tools.idea.sdk.progress.StudioLoggerProgressIndicator;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static com.android.SdkConstants.FD_CMAKE;
import static com.intellij.openapi.util.text.StringUtil.isNotEmpty;

public class MissingCMakeErrorHandler extends BaseSyncErrorHandler {

  @Nullable
  @Override
  protected String findErrorMessage(@NotNull Throwable rootCause, @NotNull Project project) {
    String message = rootCause.getMessage();
    if (isNotEmpty(message)) {
      String firstLine = getFirstLineMessage(message);
      if (firstLine.startsWith("Failed to find CMake.") || firstLine.startsWith("Unable to get the CMake version")) {
        updateUsageTracker();
        return "Failed to find CMake.";
      }
      else if (isCannotFindCmakeVersionError(firstLine)) {
        updateUsageTracker();
        return message;
      }
    }
    return null;
  }

  @Override
  @NotNull
  protected List<NotificationHyperlink> getQuickFixHyperlinks(@NotNull Project project, @NotNull String text) {
    String firstLine = getFirstLineMessage(text);
    if (isCannotFindCmakeVersionError(firstLine)) {
      // The user requested a specific version of CMake.
      Revision requestedCmakeVersion = extractCmakeVersionFromError(firstLine);
      if (requestedCmakeVersion == null) {
        // Cannot parse the CMake version from the error string.
        return Collections.EMPTY_LIST;
      }

      RepoManager sdkManager = getSdkManager();
      Collection<RemotePackage> remoteCmakePackages = sdkManager.getPackages().getRemotePackagesForPrefix(FD_CMAKE);
      Revision foundCmakeVersion = findBestMatch(remoteCmakePackages, requestedCmakeVersion);
      if (foundCmakeVersion == null) {
        // The requested CMake version was not found in the SDK. Adding a hyperlink is useless as it
        // will only fail to install the package.
        return Collections.EMPTY_LIST;
      }

      Collection<LocalPackage> localCmakePackages = sdkManager.getPackages().getLocalPackagesForPrefix(FD_CMAKE);
      if (isAlreadyInstalled(localCmakePackages, foundCmakeVersion)) {
        // Failed sanity check. The package is already installed.
        return Collections.EMPTY_LIST;
      }

      // Version-specific install of CMake.
      return Collections.singletonList(new InstallCMakeHyperlink(foundCmakeVersion));
    }
    else {
      // Generic install of CMAke.
      return Collections.singletonList(new InstallCMakeHyperlink());
    }
  }

  /**
   * @param firstLine the first line of the error message returned by gradle sync.
   * @return true if input looks like it is an error about not finding a particular version of CMake.
   **/
  private static boolean isCannotFindCmakeVersionError(@NotNull String firstLine) {
    return firstLine.startsWith("CMake") && firstLine.contains("was not found in PATH or by cmake.dir property");
  }

  /**
   * @param firsLine The line inside which the cmake version will be searched.
   * @return The cmake version string included in the error message, null if it's not found or cannot be parsed as a valid revision.
   **/
  @Nullable
  static Revision extractCmakeVersionFromError(@NotNull String firstLine) {
    int startPos = firstLine.indexOf('\'');
    if (startPos == -1) {
      return null;
    }

    int endPos = firstLine.indexOf('\'', startPos + 1);
    if (endPos == -1) {
      return null;
    }

    String version = firstLine.substring(startPos + 1, endPos);
    try {
      return Revision.parseRevision(version);
    }
    catch (NumberFormatException e) {
      return null;
    }
  }

  /**
   * Finds whether the requested cmake version can be installed from the SDK.
   *
   * @param cmakePackages         Remote CMake packages available in the SDK.
   * @param requestedCmakeVersion The CMake version requested by the user.
   * @return The version that best matches the requested version, null if no match was found.
   */
  @Nullable
  static Revision findBestMatch(@NotNull Collection<RemotePackage> cmakePackages, @NotNull Revision requestedCmakeVersion) {
    int[] requestedParts = requestedCmakeVersion.toIntArray(true);

    Revision foundVersion = null;
    for (RemotePackage remotePackage : cmakePackages) {
      Revision remoteCmakeVersion = remotePackage.getVersion();
      int[] candidateParts = remoteCmakeVersion.toIntArray(true);

      if (!versionSatisfies(candidateParts, requestedParts)) {
        continue;
      }

      if (foundVersion == null) {
        foundVersion = remoteCmakeVersion;
        continue;
      }

      if (foundVersion.compareTo(remoteCmakeVersion) < 0) {
        // Among all matching Cmake versions, use the newest one.
        foundVersion = remoteCmakeVersion;
        continue;
      }
    }

    return foundVersion;
  }

  /**
   * @param candidateParts the components of a cmake version that is available in the SDK.
   * @param requestedParts the components of a cmake version that we are looking for.
   * @return true if the version represented by candidateParts is a good match for the version represented by requestedParts.
   */
  static boolean versionSatisfies(@NotNull int[] candidateParts, @NotNull int[] requestedParts) {
    if (candidateParts.length < requestedParts.length) {
      // Request is more specific than the candidate: 3.10 cannot satisfy 3.10.2 request.
      return false;
    }

    for (int i = 0; i < requestedParts.length; ++i) {
      if (requestedParts[i] != candidateParts[i]) {
        return false;
      }
    }

    // Either full match, or a more specific version than the request was found (e.g., 3.10.2 satisfies 3.10).
    return true;
  }

  /**
   * @param cmakePackages local CMake installations available in the SDK.
   * @param cmakeVersion  the cmake version that we are looking for.
   * @return true if a package with the given version exists in cmakePackages.
   */
  private static boolean isAlreadyInstalled(@NotNull Collection<LocalPackage> cmakePackages, @NotNull Revision cmakeVersion) {
    for (LocalPackage localCmakePackage : cmakePackages) {
      if (localCmakePackage.getVersion().equals(cmakeVersion)) {
        return true;
      }
    }

    return false;
  }

  /**
   * @return The currently available SDK manager.
   */
  @NotNull
  protected RepoManager getSdkManager() {
    AndroidSdkHandler sdkHandler = AndroidSdks.getInstance().tryToChooseSdkHandler();
    StudioLoggerProgressIndicator progressIndicator = new StudioLoggerProgressIndicator(getClass());
    return sdkHandler.getSdkManager(progressIndicator);
  }
}
