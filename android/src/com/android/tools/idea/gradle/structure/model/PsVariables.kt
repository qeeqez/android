/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.tools.idea.gradle.structure.model

import com.android.tools.idea.gradle.dsl.api.ext.GradlePropertyModel
import com.android.tools.idea.gradle.structure.model.meta.*
import com.google.common.annotations.VisibleForTesting

open class PsVariables(
  override val model: PsModel,
  override val title: String,
  private val parentScope: PsVariablesScope?
) : PsVariablesScope {
  override val name: String = model.name

  override fun <ValueT : Any> getAvailableVariablesFor(
    property: ModelPropertyContext<ValueT>
  ): List<Annotated<ParsedValue.Set.Parsed<ValueT>>> =
  // TODO(solodkyy): Merge with variables available at the project level.
    getContainer(model)
      ?.inScopeProperties
      ?.map { it.key to it.value.resolve() }
      ?.flatMap {
        when (it.second.valueType) {
          GradlePropertyModel.ValueType.LIST -> listOf()
          GradlePropertyModel.ValueType.MAP ->
            it.second.getValue(GradlePropertyModel.MAP_TYPE)?.map { entry ->
              "${it.first}.${entry.key}" to entry.value
            }.orEmpty()
          else -> listOf(it)
        }
      }
      ?.mapNotNull { (name, resolvedProperty) ->
        resolvedProperty.getValue(GradlePropertyModel.OBJECT_TYPE)?.let { name to property.parse(it.toString()) }
      }
      ?.mapNotNull { (name, annotatedValue) ->
        when {
          (annotatedValue.value is ParsedValue.Set.Parsed && annotatedValue.annotation !is ValueAnnotation.Error) ->
            ParsedValue.Set.Parsed(annotatedValue.value.value, DslText.Reference(name)).annotateWith(annotatedValue.annotation)
          else -> null
        }
      } ?: listOf()

  override fun getModuleVariables(): List<PsVariable> =
    getContainer(model)?.properties?.map { PsVariable(it, model, this) } ?: listOf()

  override fun getVariableScopes(): List<PsVariablesScope> =
    parentScope?.getVariableScopes().orEmpty() + this

  override fun getNewVariableName(preferredName: String) =
    generateSequence(0, { it + 1 })
      .map { if (it == 0) preferredName else "$preferredName$it" }
      .first { getContainer(model)!!.findProperty(it).valueType == GradlePropertyModel.ValueType.NONE }

  override fun getOrCreateVariable(name: String): PsVariable = getContainer(model)!!.findProperty(name).let {
    PsVariable(it, model, this)
  }

  override fun addNewVariable(name: String) = getOrCreateVariable(name)

  @VisibleForTesting
  protected open fun getContainer(from: PsModel) =
    when (from) {
      is PsProject -> from.parsedModel.projectBuildModel?.ext()
      is PsModule -> from.parsedModel?.ext()
      else -> throw IllegalStateException()
    }

  fun refresh() {
    // Does nothing since this class is stateless (for now).
  }
}
