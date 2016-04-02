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
package com.android.tools.idea.uibuilder.property;

import com.android.ide.common.resources.ResourceResolver;
import com.android.tools.idea.uibuilder.model.NlComponent;
import org.jetbrains.android.dom.attrs.AttributeDefinition;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public interface NlProperty {
  /**
   * Get the name of the property.
   */
  @NotNull
  String getName();

  /**
   * Get the value of the property as a string.
   */
  @Nullable
  String getValue();

  /**
   * Set the property value.
   * TODO: Should the value be of type String?
   */
  void setValue(@Nullable Object value);

  /**
   * Get the help information about this property.
   */
  String getTooltipText();

  /**
   * Get the corresponding attribute definition if available.
   */
  @Nullable
  AttributeDefinition getDefinition();

  /**
   * Get the component this property is associated with.
   */
  @NotNull
  NlComponent getComponent();

  /**
   * Get the {@link ResourceResolver} for the component this property is associated with.
   */
  ResourceResolver getResolver();
}
