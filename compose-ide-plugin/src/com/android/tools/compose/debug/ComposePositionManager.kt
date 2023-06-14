/*
 * Copyright (C) 2021 The Android Open Source Project
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
package com.android.tools.compose.debug

import com.intellij.debugger.MultiRequestPositionManager
import com.intellij.debugger.NoDataException
import com.intellij.debugger.SourcePosition
import com.intellij.debugger.engine.DebugProcess
import com.intellij.debugger.engine.PositionManagerWithMultipleStackFrames
import com.intellij.debugger.engine.evaluation.EvaluationContext
import com.intellij.debugger.jdi.StackFrameProxyImpl
import com.intellij.debugger.requests.ClassPrepareRequestor
import com.intellij.debugger.ui.impl.watch.StackFrameDescriptorImpl
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.fileTypes.FileType
import com.intellij.util.ThreeState
import com.intellij.xdebugger.frame.XStackFrame
import com.sun.jdi.Location
import com.sun.jdi.ReferenceType
import com.sun.jdi.request.ClassPrepareRequest
import org.jetbrains.kotlin.fileClasses.JvmFileClassUtil
import org.jetbrains.kotlin.idea.base.util.KOTLIN_FILE_TYPES
import org.jetbrains.kotlin.idea.debugger.KotlinPositionManager
import org.jetbrains.kotlin.load.kotlin.PackagePartClassUtils
import org.jetbrains.kotlin.psi.KtFile

/**
 * A PositionManager capable of setting breakpoints inside of ComposableSingleton lambdas.
 *
 * This class essentially resolves breakpoints for lambdas generated by the compose compiler
 * optimization that was introduced in I8c967b14c5d9bf67e5646e60f630f2e29e006366
 * The default [KotlinPositionManager] only locates source positions in enclosing and nested
 * classes, while composable singleton lambdas are cached in a separate top-level class.
 *
 * See https://issuetracker.google.com/190373291 for more information.
 */
class ComposePositionManager(
  private val debugProcess: DebugProcess,
  private val kotlinPositionManager: KotlinPositionManager
) : MultiRequestPositionManager by kotlinPositionManager, PositionManagerWithMultipleStackFrames  {
  override fun getAcceptedFileTypes(): Set<FileType> = KOTLIN_FILE_TYPES

  override fun createStackFrames(descriptor: StackFrameDescriptorImpl): List<XStackFrame> =
    kotlinPositionManager.createStackFrames(descriptor)

  override fun evaluateCondition(context: EvaluationContext,
                                 frame: StackFrameProxyImpl,
                                 location: Location,
                                 expression: String): ThreeState =
    kotlinPositionManager.evaluateCondition(context, frame, location, expression)

  /**
   * Returns all prepared classes which could contain the given classPosition.
   *
   * This handles the case where a user sets a breakpoint in a ComposableSingleton
   * lambda after the debug process has already initialized the corresponding
   * `lambda-n` class.
   */
  override fun getAllClasses(classPosition: SourcePosition): List<ReferenceType> {
    val file = classPosition.file

    // Unlike [KotlinPositionManager] we don't handle compiled code, since the
    // Kotlin decompiler does not undo any Compose specific optimizations.
    if (file !is KtFile) {
      throw NoDataException.INSTANCE
    }

    val vm = debugProcess.virtualMachineProxy
    val singletonClasses = vm.classesByName(computeComposableSingletonsClassName(file)).flatMap { referenceType ->
      if (referenceType.isPrepared) vm.nestedTypes(referenceType) else listOf()
    }

    if (singletonClasses.isEmpty()) {
      throw NoDataException.INSTANCE
    }

    // Since [CompoundPositionManager] returns the first successful result from [getAllClasses],
    // we need to query [KotlinPositionManager] here in order to locate breakpoints
    // in ordinary Kotlin code.
    val kotlinReferences = kotlinPositionManager.getAllClasses(classPosition)
    return kotlinReferences + singletonClasses
  }

  /**
   * Registers search patterns in the form of [ClassPrepareRequest]s for classes which may contain
   * the given `position`, but may not be loaded yet. The `requestor` will be called for any newly
   * prepared class which matches any of the created search patterns.
   */
  override fun createPrepareRequests(requestor: ClassPrepareRequestor, position: SourcePosition): List<ClassPrepareRequest> {
    val file = position.file
    if (file !is KtFile) {
      throw NoDataException.INSTANCE
    }

    // Similar to getAllClasses above, [CompoundPositionManager] uses the first successful
    // position manager, so we need to include the prepare requests from [KotlinPositionManager]
    // in order to locate breakpoints in ordinary Kotlin code.
    val kotlinRequests = kotlinPositionManager.createPrepareRequests(requestor, position)

    val singletonRequest = debugProcess.requestsManager.createClassPrepareRequest(
      requestor,
      "${computeComposableSingletonsClassName(file)}\$*"
    )

    return if (singletonRequest == null) kotlinRequests else kotlinRequests + singletonRequest
  }

  /**
   * A method from [PositionManager] which was superseded by [createPrepareRequests] in [MultiRequestPositionManager].
   * Intellij code should never call this method for subclasses of [MultiRequestPositionManager].
   */
  override fun createPrepareRequest(requestor: ClassPrepareRequestor, position: SourcePosition): ClassPrepareRequest? {
    return createPrepareRequests(requestor, position).firstOrNull()
  }

  /**
   * Compute the name of the ComposableSingletons class for the given file.
   *
   * The Compose compiler plugin creates per-file ComposableSingletons classes to cache
   * composable lambdas without captured variables. We need to locate these classes in order
   * to search them for breakpoint locations.
   *
   * NOTE: The pattern for ComposableSingletons classes needs to be kept in sync with the
   *       code in `ComposerLambdaMemoization.getOrCreateComposableSingletonsClass`.
   *       The optimization was introduced in I8c967b14c5d9bf67e5646e60f630f2e29e006366
   */
  private fun computeComposableSingletonsClassName(file: KtFile): String {
    // The code in `ComposerLambdaMemoization` always uses the file short name and
    // ignores `JvmName` annotations, but (implicitly) respects `JvmPackageName`
    // annotations.
    val filePath = file.virtualFile?.path ?: file.name
    val fileName = filePath.split('/').last()
    val shortName = PackagePartClassUtils.getFilePartShortName(fileName)
    val fileClassFqName = runReadAction { JvmFileClassUtil.getFileClassInfoNoResolve(file) }.facadeClassFqName
    return "${fileClassFqName.parent().asString()}.ComposableSingletons\$$shortName"
  }
}
