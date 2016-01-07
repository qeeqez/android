/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.tools.idea.fd;

import com.android.builder.model.InstantRun;
import com.android.tools.idea.gradle.AndroidGradleModel;
import com.android.utils.XmlUtils;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.collect.Lists;
import com.google.common.io.Files;
import com.intellij.util.ArrayUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * {@link InstantRunBuildInfo} models the build-info.xml file that is generated by an instant-run aware Gradle build.
 */
public class InstantRunBuildInfo {
  private static final String ATTR_BUILD_ID = "build-id";
  public static final String ATTR_API_LEVEL = "api-level";
  private static final String ATTR_FORMAT = "format";

  private static final String ATTR_VERIFIER_STATUS = "verifier";

  // Note: The verifier status can be any number of values (See InstantRunVerifierStatus enum in gradle).
  // Currently, the only contract between gradle and the IDE is that the value is set to COMPATIBLE if the build can be hotswapped
  private static final String VALUE_VERIFIER_STATUS_COMPATIBLE = "COMPATIBLE";

  private static final String TAG_ARTIFACT = "artifact";
  private static final String ATTR_ARTIFACT_LOCATION = "location";
  private static final String ATTR_ARTIFACT_TYPE = "type";
  private static final String VALUE_ARTIFACT_TYPE_SPLIT = "SPLIT";
  private static final String VALUE_ARTIFACT_TYPE_MAIN = "MAIN";

  @NotNull private final Element myRoot;
  @Nullable private List<InstantRunArtifact> myArtifacts;

  public InstantRunBuildInfo(@NotNull Element root) {
    myRoot = root;
  }

  @NotNull
  public String getBuildId() {
    return myRoot.getAttribute(ATTR_BUILD_ID);
  }

  @NotNull
  public String getVerifierStatus() {
    return myRoot.getAttribute(ATTR_VERIFIER_STATUS);
  }

  public boolean canHotswap() {
    String verifierStatus = getVerifierStatus();
    if (VALUE_VERIFIER_STATUS_COMPATIBLE.equals(verifierStatus)) {
      return true;
    } else if (verifierStatus.isEmpty()) {
      // build-info.xml doesn't currently specify a verifier status if there is *only* a resource
      // change!
      List<InstantRunArtifact> artifacts = getArtifacts();
      if (artifacts.size() == 1 && artifacts.get(0).type == InstantRunArtifactType.RESOURCES) {
        return true;
      }
    }

    return false;
  }

  @NotNull
  public int getApiLevel() {
    String attribute = myRoot.getAttribute(ATTR_API_LEVEL);
    if (attribute != null && !attribute.isEmpty()) {
      try {
        return Integer.parseInt(attribute);
      } catch (NumberFormatException ignore) {
      }
    }
    return -1; // unknown
  }

  @NotNull
  public List<InstantRunArtifact> getArtifacts() {
    if (myArtifacts == null) {
      List<InstantRunArtifact> artifacts = Lists.newArrayList();

      NodeList children = myRoot.getChildNodes();
      for (int i = 0, n = children.getLength(); i < n; i++) {
        Node child = children.item(i);
        if (child.getNodeType() == Node.ELEMENT_NODE) {
          Element element = (Element)child;
          if (!TAG_ARTIFACT.equals(element.getTagName())) {
            continue;
          }

          String location = element.getAttribute(ATTR_ARTIFACT_LOCATION);
          String typeAttribute = element.getAttribute(ATTR_ARTIFACT_TYPE);
          InstantRunArtifactType type = InstantRunArtifactType.valueOf(typeAttribute);
          artifacts.add(new InstantRunArtifact(type, new File(location)));
        }
      }
      myArtifacts = artifacts;
    }

    return myArtifacts;
  }

  /**
   * Returns true if the given list of artifacts contains at least
   * one artifact of any of the given types
   *
   * @param types the types to look for
   * @return true if and only if the list of artifacts contains an artifact of any of the given types
   */
  public boolean hasOneOf(@NotNull InstantRunArtifactType... types) {
    for (InstantRunArtifact artifact : getArtifacts()) {
      if (ArrayUtil.contains(artifact.type, types)) {
        return true;
      }
    }
    return false;
  }

  public boolean hasMainApk() {
    return hasOneOf(InstantRunArtifactType.MAIN);
  }

  @Nullable
  public static InstantRunBuildInfo get(@NotNull AndroidGradleModel model) {
    File buildInfo = getLocalBuildInfoFile(model);
    if (!buildInfo.exists()) {
      return null;
    }

    String xml;
    try {
      xml = Files.toString(buildInfo, Charsets.UTF_8);
    }
    catch (IOException e) {
      return null;
    }

    return getInstantRunBuildInfo(xml);
  }

  @VisibleForTesting
  @Nullable
  static InstantRunBuildInfo getInstantRunBuildInfo(@NotNull String xml) {
    Document doc = XmlUtils.parseDocumentSilently(xml, false);
    if (doc == null) {
      return null;
    }

    return new InstantRunBuildInfo(doc.getDocumentElement());
  }

  @NotNull
  private static File getLocalBuildInfoFile(@NotNull AndroidGradleModel model) {
    InstantRun instantRun = model.getSelectedVariant().getMainArtifact().getInstantRun();

    File file = instantRun.getInfoFile();
    if (!file.exists()) {
      // Temporary hack workaround; model is passing the wrong value! See InstantRunAnchorTask.java
      file = new File(instantRun.getRestartDexFile().getParentFile(), "build-info.xml");
    }

    return file;
  }

  public int getFormat() {
    String attribute = myRoot.getAttribute(ATTR_FORMAT);
    if (attribute != null && !attribute.isEmpty()) {
      try {
        return Integer.parseInt(attribute);
      } catch (NumberFormatException ignore) {
      }
    }
    return 0;
  }
}
