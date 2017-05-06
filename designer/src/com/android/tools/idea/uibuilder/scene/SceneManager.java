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
package com.android.tools.idea.uibuilder.scene;

import com.android.SdkConstants;
import com.android.tools.idea.uibuilder.handlers.constraint.targets.ConstraintDragDndTarget;
import com.android.tools.idea.uibuilder.model.NlComponent;
import com.android.tools.idea.uibuilder.model.NlModel;
import com.android.tools.idea.uibuilder.model.SelectionModel;
import com.android.tools.idea.uibuilder.scene.decorator.SceneDecoratorFactory;
import com.android.tools.idea.uibuilder.surface.DesignSurface;
import com.android.util.PropertiesMap;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.util.Disposer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A facility for creating and updating {@link Scene}s based on {@link NlModel}s.
 */
abstract public class SceneManager implements Disposable {

  public static final boolean SUPPORTS_LOCKING = false;

  private final NlModel myModel;
  final private DesignSurface myDesignSurface;
  private Scene myScene;

  public SceneManager(NlModel model, DesignSurface surface) {
    myModel = model;
    myDesignSurface = surface;
    Disposer.register(model, this);
  }

  @Override
  public void dispose() {
  }

  @NotNull
  public Scene build() {
    assert myScene == null;
    myScene = new Scene(myDesignSurface);
    return myScene;
  }

  /**
   * Update the Scene with the components in the given NlModel. This method needs to be called in the dispatch thread.
   * {@link #build()} must have been invoked already.
   */
  public void update() {
    List<NlComponent> components = getModel().getComponents();
    Scene scene = getScene();
    if (components.size() == 0) {
      scene.removeAllComponents();
      scene.setRoot(null);
      return;
    }
    Set<SceneComponent> usedComponents = new HashSet<>();
    Set<SceneComponent> oldComponents = new HashSet<>(scene.getSceneComponents());

    NlComponent rootComponent = components.get(0).getRoot();

    SceneComponent root = updateFromComponent(rootComponent, usedComponents);
    root.setToolLocked(false); // the root is always unlocked.
    oldComponents.removeAll(usedComponents);
    // The temporary component are not present in the NLModel so won't be added to the used component array
    oldComponents.removeIf(component -> component instanceof TemporarySceneComponent);
    oldComponents.forEach(scene::removeComponent);

    SelectionModel selectionModel = getDesignSurface().getSelectionModel();
    scene.setRoot(root);
    if (root != null && selectionModel.isEmpty()) {
      addTargets(root);
    }
    scene.needsRebuildList();
  }

  public abstract void addTargets(@NotNull SceneComponent component);

  /**
   * Returns false if the value of the tools:visible attribute is false, true otherwise.
   * When a component is not tool visible, it will only appear in the design mode (not in the blueprint mode)
   * and no interaction will be possible with it from the design surface.
   *
   * @param component component to look at
   * @return tool visibility status
   */
  public static boolean isComponentLocked(@NotNull NlComponent component) {
    if (SUPPORTS_LOCKING) {
      String attribute = component.getLiveAttribute(SdkConstants.TOOLS_URI, SdkConstants.ATTR_LOCKED);
      if (attribute != null) {
        return attribute.equals(SdkConstants.VALUE_TRUE);
      }
    }
    return false;
  }

  /**
   * Update (and if necessary, create) the SceneComponent paired to the given NlComponent
   *
   * @param component      a given NlComponent
   * @param seenComponents Collector of components that were seen during NlComponent tree traversal.
   * @return the SceneComponent paired with the given NlComponent
   */
  @Nullable
  protected SceneComponent updateFromComponent(@NotNull NlComponent component, @NotNull Set<SceneComponent> seenComponents) {
    SceneComponent sceneComponent = getScene().getSceneComponent(component);
    boolean created = false;
    if (sceneComponent == null) {
      sceneComponent = new SceneComponent(myScene, component);
      created = true;
    }
    sceneComponent.setToolLocked(isComponentLocked(component));
    seenComponents.add(sceneComponent);

    boolean isAnimated = myScene.isAnimated();
    if (created) {
      myScene.setAnimated(false);
    }
    updateFromComponent(component, sceneComponent);
    if (created) {
      myScene.setAnimated(isAnimated);
    }

    for (NlComponent nlChild : component.getChildren()) {
      SceneComponent child = updateFromComponent(nlChild, seenComponents);
      if (child != null && child.getParent() != sceneComponent) {
        sceneComponent.addChild(child);
      }
    }
    return sceneComponent;
  }

  /**
   * Creates a {@link TemporarySceneComponent} in our Scene.
   */
  @NotNull
  public TemporarySceneComponent createTemporaryComponent(@NotNull NlComponent component) {
    Scene scene = getScene();

    assert scene.getRoot() != null;

    TemporarySceneComponent tempComponent = new TemporarySceneComponent(getScene(), component);
    tempComponent.addTarget(new ConstraintDragDndTarget());
    scene.setAnimated(false);
    scene.getRoot().addChild(tempComponent);
    updateFromComponent(component, tempComponent);
    scene.setAnimated(true);

    return tempComponent;
  }

  abstract protected void updateFromComponent(@NotNull NlComponent component, SceneComponent sceneComponent);

  @NotNull
  protected DesignSurface getDesignSurface() {
    return myDesignSurface;
  }

  @NotNull
  protected NlModel getModel() {
    return myModel;
  }

  @NotNull
  protected Scene getScene() {
    assert myScene != null;
    return myScene;
  }

  public abstract void requestRender();

  public abstract void layout(boolean animate);

  @NotNull
  public abstract SceneDecoratorFactory getSceneDecoratorFactory();

  public abstract Map<Object, PropertiesMap> getDefaultProperties();
}
