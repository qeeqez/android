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
package com.android.tools.idea.gradle.dsl.parser.groovy;

import static com.android.tools.idea.gradle.dsl.api.ext.GradlePropertyModel.iStr;
import static com.android.tools.idea.gradle.dsl.api.ext.PropertyType.REGULAR;
import static com.android.tools.idea.gradle.dsl.api.ext.PropertyType.VARIABLE;
import static com.android.tools.idea.gradle.dsl.model.notifications.NotificationTypeReference.INCOMPLETE_PARSING;
import static com.android.tools.idea.gradle.dsl.model.notifications.NotificationTypeReference.INVALID_EXPRESSION;
import static com.android.tools.idea.gradle.dsl.parser.groovy.GroovyDslUtil.ensureUnquotedText;
import static com.android.tools.idea.gradle.dsl.parser.groovy.GroovyDslUtil.findInjections;
import static com.intellij.psi.util.PsiTreeUtil.findChildOfType;
import static com.intellij.psi.util.PsiTreeUtil.getChildOfType;
import static com.intellij.psi.util.PsiTreeUtil.getNextSiblingOfType;

import com.android.tools.idea.gradle.dsl.api.dependencies.ArtifactDependencySpec;
import com.android.tools.idea.gradle.dsl.api.ext.ReferenceTo;
import com.android.tools.idea.gradle.dsl.model.GradleBuildModelImpl;
import com.android.tools.idea.gradle.dsl.model.android.AndroidModelImpl;
import com.android.tools.idea.gradle.dsl.parser.GradleDslParser;
import com.android.tools.idea.gradle.dsl.parser.GradleReferenceInjection;
import com.android.tools.idea.gradle.dsl.parser.SharedParserUtilsKt;
import com.android.tools.idea.gradle.dsl.parser.configurations.ConfigurationDslElement;
import com.android.tools.idea.gradle.dsl.parser.configurations.ConfigurationsDslElement;
import com.android.tools.idea.gradle.dsl.parser.dependencies.FakeArtifactElement;
import com.android.tools.idea.gradle.dsl.parser.elements.GradleDslClosure;
import com.android.tools.idea.gradle.dsl.parser.elements.GradleDslElement;
import com.android.tools.idea.gradle.dsl.parser.elements.GradleDslExpression;
import com.android.tools.idea.gradle.dsl.parser.elements.GradleDslExpressionList;
import com.android.tools.idea.gradle.dsl.parser.elements.GradleDslExpressionMap;
import com.android.tools.idea.gradle.dsl.parser.elements.GradleDslLiteral;
import com.android.tools.idea.gradle.dsl.parser.elements.GradleDslMethodCall;
import com.android.tools.idea.gradle.dsl.parser.elements.GradleDslSettableExpression;
import com.android.tools.idea.gradle.dsl.parser.elements.GradleDslSimpleExpression;
import com.android.tools.idea.gradle.dsl.parser.elements.GradleDslUnknownElement;
import com.android.tools.idea.gradle.dsl.parser.elements.GradleNameElement;
import com.android.tools.idea.gradle.dsl.parser.elements.GradlePropertiesDslElement;
import com.android.tools.idea.gradle.dsl.parser.files.GradleDslFile;
import com.google.common.collect.ImmutableList;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.psi.PsiElement;
import com.intellij.util.IncorrectOperationException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.groovy.lang.psi.GroovyElementVisitor;
import org.jetbrains.plugins.groovy.lang.psi.GroovyFile;
import org.jetbrains.plugins.groovy.lang.psi.GroovyPsiElement;
import org.jetbrains.plugins.groovy.lang.psi.GroovyPsiElementFactory;
import org.jetbrains.plugins.groovy.lang.psi.GroovyPsiElementVisitor;
import org.jetbrains.plugins.groovy.lang.psi.api.auxiliary.GrListOrMap;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.GrVariable;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.GrVariableDeclaration;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.arguments.GrArgumentLabel;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.arguments.GrArgumentList;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.arguments.GrNamedArgument;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.blocks.GrClosableBlock;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrApplicationStatement;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrAssignmentExpression;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrCommandArgumentList;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrExpression;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrNewExpression;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrReferenceExpression;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.literals.GrLiteral;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.literals.GrString;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.path.GrIndexProperty;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.path.GrMethodCallExpression;
import org.jetbrains.plugins.groovy.lang.psi.api.types.GrCodeReferenceElement;


/**
 * Generic parser to parse .gradle files.
 * <p>
 * <p>It parses any general application statements or assigned statements in the .gradle file directly and stores them as key value pairs
 * in the {@link GradleBuildModelImpl}. For every closure block section like {@code android{}}, it will create block elements like
 * {@link AndroidModelImpl}. See {@link #getBlockElement(List, GradlePropertiesDslElement)} for all the block elements currently supported
 * by this parser.
 */
public class GroovyDslParser implements GradleDslParser {
  @NotNull private final GroovyFile myPsiFile;
  @NotNull private final GradleDslFile myDslFile;

  public GroovyDslParser(@NotNull GroovyFile file, @NotNull GradleDslFile dslFile) {
    myPsiFile = file;
    myDslFile = dslFile;
  }

  @Override
  public void parse() {
    ApplicationManager.getApplication().assertReadAccessAllowed();

    myPsiFile.acceptChildren(new GroovyPsiElementVisitor(new GroovyElementVisitor() {
      @Override
      public void visitMethodCallExpression(@NotNull GrMethodCallExpression e) {
        process(e);
      }

      @Override
      public void visitAssignmentExpression(@NotNull GrAssignmentExpression e) {
        process(e);
      }

      @Override
      public void visitReferenceExpression(@NotNull GrReferenceExpression e) {
        process(e);
      }

      @Override
      public void visitApplicationStatement(@NotNull GrApplicationStatement e) {
        process(e);
      }

      @Override
      public void visitVariableDeclaration(@NotNull GrVariableDeclaration e) {
        process(e);
      }

      void process(GroovyPsiElement e) {
        parse(e, myDslFile);
      }
    }));
  }

  @Override
  @Nullable
  public PsiElement convertToPsiElement(@NotNull Object literal) {
    ApplicationManager.getApplication().assertReadAccessAllowed();
    try {
      return GroovyDslUtil.createLiteral(myDslFile, literal);
    }
    catch (IncorrectOperationException e) {
      myDslFile.getContext().getNotificationForType(myDslFile, INVALID_EXPRESSION).addError(e);
      return null;
    }
  }

  @Override
  public void setUpForNewValue(@NotNull GradleDslLiteral context, @Nullable PsiElement newValue) {
    if (newValue == null) {
      return;
    }

    boolean isReference = newValue instanceof GrReferenceExpression || newValue instanceof GrIndexProperty;
    context.setReference(isReference);
  }

  @Override
  @Nullable
  public Object extractValue(@NotNull GradleDslSimpleExpression context, @NotNull PsiElement literal, boolean resolve) {
    ApplicationManager.getApplication().assertReadAccessAllowed();

    if (literal instanceof GrReferenceExpression || literal instanceof GrIndexProperty) {
      if (resolve) {
        GradleDslElement e = context.resolveReference(literal.getText(), true);
        // Only attempt to get the value if it is a simple expression.
        if (e instanceof GradleDslSimpleExpression) {
          return ((GradleDslSimpleExpression)e).getValue();
        }
      }
      return literal.getText();
    }

    if (!(literal instanceof GrLiteral)) {
      return new ReferenceTo(literal.getText());
    }

    // If this literal has a value then return it: this will be the case for non-string values.
    Object value = ((GrLiteral)literal).getValue();
    if (value != null) {
      return value;
    }

    // Everything left should be a string
    if (!(literal instanceof GrString)) {
      return null;
    }

    // If we shouldn't resolve the value then just return the text.
    if (!resolve) {
      return ensureUnquotedText(literal.getText());
    }

    // Check that we are not resolving into a cycle, if we are just return the unresolved text.
    if (context.hasCycle()) {
      return ensureUnquotedText(literal.getText());
    }

    // Otherwise resolve the value and then return the resolved text.
    Collection<GradleReferenceInjection> injections = context.getResolvedVariables();
    return ensureUnquotedText(GradleReferenceInjection.injectAll(literal, injections));
  }

  @Override
  @Nullable
  public PsiElement convertToExcludesBlock(@NotNull List<ArtifactDependencySpec> excludes) {
    GroovyPsiElementFactory factory = GroovyPsiElementFactory.getInstance(myDslFile.getProject());
    GrClosableBlock block = factory.createClosureFromText("{\n}");
    for (ArtifactDependencySpec spec : excludes) {
      String group = FakeArtifactElement.shouldInterpolate(spec.getGroup()) ? iStr(spec.getGroup()) : "'" + spec.getGroup() + "'";
      String name = FakeArtifactElement.shouldInterpolate(spec.getName()) ? iStr(spec.getName()) : "'" + spec.getName() + "'";
      String text = String.format("exclude group: %s, module: %s", group, name);
      block.addBefore(factory.createStatementFromText(text), block.getLastChild());
      PsiElement lineTerminator = factory.createLineTerminator(1);
      block.addBefore(lineTerminator, block.getLastChild());
    }
    return block;
  }

  @Override
  public boolean shouldInterpolate(@NotNull GradleDslElement elementToCheck) {
    // Get the correct psiElement to check.
    PsiElement element;
    if (elementToCheck instanceof GradleDslSettableExpression) {
      element = ((GradleDslSettableExpression)elementToCheck).getCurrentElement();
    }
    else if (elementToCheck instanceof GradleDslSimpleExpression) {
      element = ((GradleDslSimpleExpression)elementToCheck).getExpression();
    }
    else {
      element = elementToCheck.getPsiElement();
    }

    return element instanceof GrString;
  }

  @Override
  @NotNull
  public List<GradleReferenceInjection> getResolvedInjections(@NotNull GradleDslSimpleExpression context, @NotNull PsiElement psiElement) {
    return findInjections(context, psiElement, false);
  }

  @NotNull
  @Override
  public List<GradleReferenceInjection> getInjections(@NotNull GradleDslSimpleExpression context, @NotNull PsiElement psiElement) {
    return findInjections(context, psiElement, true);
  }

  @Nullable
  @Override
  public GradlePropertiesDslElement getBlockElement(@NotNull List<String> nameParts,
                                                    @NotNull GradlePropertiesDslElement parentElement,
                                                    @Nullable GradleNameElement nameElement) {
    return SharedParserUtilsKt.getBlockElement(myDslFile, nameParts, parentElement, nameElement);
  }

  private void parse(@NotNull PsiElement psiElement, @NotNull GradleDslFile gradleDslFile) {
    boolean success = false;
    if (psiElement instanceof GrMethodCallExpression) {
      success = parse((GrMethodCallExpression)psiElement, (GradlePropertiesDslElement)gradleDslFile);
    }
    else if (psiElement instanceof GrAssignmentExpression) {
      success = parse((GrAssignmentExpression)psiElement, (GradlePropertiesDslElement)gradleDslFile);
    }
    else if (psiElement instanceof GrApplicationStatement) {
      success = parse((GrApplicationStatement)psiElement, (GradlePropertiesDslElement)gradleDslFile);
    }
    else if (psiElement instanceof GrVariableDeclaration) {
      success = parse((GrVariableDeclaration)psiElement, (GradlePropertiesDslElement)gradleDslFile);
    }
    else if (psiElement instanceof GrReferenceExpression) {
      success = parse((GrReferenceExpression)psiElement, (GradlePropertiesDslElement)gradleDslFile);
    }
    if (!success) {
      gradleDslFile.notification(INCOMPLETE_PARSING).addUnknownElement(psiElement);
    }
  }

  private boolean parse(@NotNull GrReferenceExpression element, @NotNull GradlePropertiesDslElement dslElement) {
    GradleNameElement name = GradleNameElement.from(element);

    if (name.isQualified()) {
      GradlePropertiesDslElement nestedElement = getBlockElement(name.qualifyingParts(), dslElement, null);
      if (nestedElement != null) {
        dslElement = nestedElement;
      }
    }
    GradleDslElement resultElement;
    // Only supported in configuration block currently.
    if (!(dslElement instanceof ConfigurationsDslElement)) {
      return false;
    }
    resultElement = new ConfigurationDslElement(dslElement, element, name, false);
    resultElement.setElementType(REGULAR);
    dslElement.addParsedElement(resultElement);
    return true;
  }

  private boolean parse(@NotNull GrMethodCallExpression expression, @NotNull GradlePropertiesDslElement dslElement) {
    GrReferenceExpression referenceExpression = findChildOfType(expression, GrReferenceExpression.class);
    if (referenceExpression == null) {
      return false;
    }

    // If the reference has multiple parts i.e google().with then strip the end as these kind of calls are not supported.
    if (expression.getChildren().length > 1 &&
        referenceExpression.getChildren().length == 1 &&
        referenceExpression.getChildren()[0] instanceof GrMethodCallExpression) {
      return parse((GrMethodCallExpression)referenceExpression.getChildren()[0], dslElement);
    }

    GradleNameElement name = GradleNameElement.from(referenceExpression);
    if (name.isEmpty()) {
      return false;
    }

    if (name.isQualified()) {
      dslElement = getBlockElement(name.qualifyingParts(), dslElement, null);
    }

    if (dslElement == null) {
      return false;
    }

    GrClosableBlock[] closureArguments = expression.getClosureArguments();
    GrArgumentList argumentList = expression.getArgumentList();
    if (argumentList.getAllArguments().length > 0 || closureArguments.length == 0) {
      // This element is a method call with arguments and an optional closure associated with it.
      // ex: compile("dependency") {}
      GradleDslSimpleExpression methodCall = getMethodCall(dslElement, expression, name, argumentList, name.fullName(), false);
      if (closureArguments.length > 0) {
        methodCall.setParsedClosureElement(getClosureElement(methodCall, closureArguments[0], name));
      }
      methodCall.setElementType(REGULAR);
      dslElement.addParsedElement(methodCall);
      return true;
    }

    // Now this element is pure block element, i.e a method call with no argument but just a closure argument. So, here just process the
    // closure and treat it as a block element.
    // ex: android {}
    GrClosableBlock closableBlock = closureArguments[0];
    List<GradlePropertiesDslElement> blockElements = new ArrayList<>(); // The block elements this closure needs to be applied.

    if (dslElement instanceof GradleDslFile && name.name().equals("allprojects")) {
      // The "allprojects" closure needs to be applied to this project and all it's sub projects.
      blockElements.add(dslElement);
      // After applying the allprojects closure to this project, process it as subprojects section to also pass the same properties to
      // subprojects.
      name = GradleNameElement.create("subprojects");
    }

    GradlePropertiesDslElement blockElement = getBlockElement(ImmutableList.of(name.name()), dslElement, name);
    if (blockElement != null) {
      blockElement.setPsiElement(closableBlock);
      blockElements.add(blockElement);
    }

    if (blockElements.isEmpty()) {
      return false;
    }
    for (GradlePropertiesDslElement element : blockElements) {
      parse(closableBlock, element);
    }
    return true;
  }

  private void parse(@NotNull GrClosableBlock closure, @NotNull final GradlePropertiesDslElement blockElement) {
    closure.acceptChildren(new GroovyElementVisitor() {
      @Override
      public void visitMethodCallExpression(@NotNull GrMethodCallExpression methodCallExpression) {
        parse(methodCallExpression, blockElement);
      }

      @Override
      public void visitApplicationStatement(@NotNull GrApplicationStatement applicationStatement) {
        parse(applicationStatement, blockElement);
      }

      @Override
      public void visitAssignmentExpression(@NotNull GrAssignmentExpression expression) {
        parse(expression, blockElement);
      }

      @Override
      public void visitReferenceExpression(@NotNull GrReferenceExpression referenceExpression) {
        parse(referenceExpression, blockElement);
      }

      @Override
      public void visitVariableDeclaration(@NotNull GrVariableDeclaration variableDeclaration) {
        parse(variableDeclaration, blockElement);
      }
    });
  }

  private boolean parse(@NotNull GrApplicationStatement statement, @NotNull GradlePropertiesDslElement blockElement) {
    GrReferenceExpression referenceExpression = getChildOfType(statement, GrReferenceExpression.class);
    if (referenceExpression == null) {
      return false;
    }

    GrCommandArgumentList argumentList = getNextSiblingOfType(referenceExpression, GrCommandArgumentList.class);
    if (argumentList == null) {
      return false;
    }

    GroovyPsiElement[] arguments = argumentList.getAllArguments();
    if (arguments.length == 0) {
      return false;
    }

    GradleNameElement name = GradleNameElement.from(referenceExpression);
    if (name.isEmpty()) {
      return false;
    }

    if (name.isQualified()) {
      GradlePropertiesDslElement nestedElement = getBlockElement(name.qualifyingParts(), blockElement, null);
      if (nestedElement != null) {
        blockElement = nestedElement;
      }
      else {
        return false;
      }
    }

    // TODO: This code highly restricts the arguments allowed in an application statement. Fix this.
    GradleDslElement propertyElement = null;
    if (arguments[0] instanceof GrExpression) { // ex: proguardFiles 'proguard-android.txt', 'proguard-rules.pro'
      List<GrExpression> expressions = new ArrayList<>(arguments.length);
      for (GroovyPsiElement element : arguments) {
        // We need to make sure all of these are GrExpressions, there can be multiple types.
        // We currently can't handle different argument types.
        if (element instanceof GrExpression && !(element instanceof GrClosableBlock)) {
          expressions.add((GrExpression)element);
        }
      }
      if (expressions.size() == 1) {
        propertyElement = createExpressionElement(blockElement, argumentList, name, expressions.get(0));
      }
      else {
        propertyElement = getExpressionList(blockElement, argumentList, name, expressions, false);
      }
    }
    else if (arguments[0] instanceof GrNamedArgument) {
      // ex: manifestPlaceholders activityLabel1:"defaultName1", activityLabel2:"defaultName2"
      List<GrNamedArgument> namedArguments = new ArrayList<>(arguments.length);
      for (GroovyPsiElement element : arguments) {
        // We need to make sure all of these are GrNamedArgument, there can be multiple types.
        // We currently can't handle different argument types.
        if (element instanceof GrNamedArgument && !(element instanceof GrClosableBlock)) {
          namedArguments.add((GrNamedArgument)element);
        }
      }
      propertyElement = getExpressionMap(blockElement, argumentList, name, namedArguments, false);
    }
    if (propertyElement == null) {
      return false;
    }

    GroovyPsiElement lastArgument = arguments[arguments.length - 1];
    if (lastArgument instanceof GrClosableBlock) {
      propertyElement.setParsedClosureElement(getClosureElement(propertyElement, (GrClosableBlock)lastArgument, name));
    }

    propertyElement.setElementType(REGULAR);
    blockElement.addParsedElement(propertyElement);
    return true;
  }

  @NotNull
  private GradleDslExpression createExpressionElement(@NotNull GradleDslElement parent,
                                                      @NotNull GroovyPsiElement psiElement,
                                                      @NotNull GradleNameElement name,
                                                      @NotNull GrExpression expression) {
    GradleDslExpression propertyElement;
    if (expression instanceof GrListOrMap) {
      GrListOrMap listOrMap = (GrListOrMap)expression;
      if (listOrMap.isMap()) { // ex: manifestPlaceholders = [activityLabel1:"defaultName1", activityLabel2:"defaultName2"]
        propertyElement = getExpressionMap(parent, listOrMap, name, Arrays.asList(listOrMap.getNamedArguments()), true);
      }
      else { // ex: proguardFiles = ['proguard-android.txt', 'proguard-rules.pro']
        propertyElement = getExpressionList(parent, listOrMap, name, Arrays.asList(listOrMap.getInitializers()), true);
      }
    }
    else if (expression instanceof GrClosableBlock) {
      propertyElement = getClosureElement(parent, (GrClosableBlock)expression, name);
    }
    else {
      propertyElement = getExpressionElement(parent, psiElement, name, expression);
    }

    return propertyElement;
  }

  private boolean parse(@NotNull GrVariableDeclaration declaration, @NotNull GradlePropertiesDslElement blockElement) {
    if (declaration.getVariables().length == 0) {
      return false;
    }

    for (GrVariable variable : declaration.getVariables()) {
      if (variable == null) {
        return false;
      }
      GrExpression init = variable.getInitializerGroovy();
      if (init == null) {
        return false;
      }

      GradleNameElement name = GradleNameElement.from(variable);
      GradleDslElement variableElement =
        createExpressionElement(blockElement, declaration, name, init);

      variableElement.setElementType(VARIABLE);
      blockElement.setParsedElement(variableElement);
    }
    return true;
  }

  private boolean parse(@NotNull GrAssignmentExpression assignment, @NotNull GradlePropertiesDslElement blockElement) {
    PsiElement operationToken = assignment.getOperationToken();
    if (!operationToken.getText().equals("=")) {
      return false; // TODO: Add support for other operators like +=.
    }

    GrExpression left = assignment.getLValue();
    GradleNameElement name = GradleNameElement.from(left);
    if (name.isEmpty()) {
      return false;
    }

    if (name.isQualified()) {
      GradlePropertiesDslElement nestedElement = getBlockElement(name.qualifyingParts(), blockElement, null);
      if (nestedElement != null) {
        blockElement = nestedElement;
      }
      else {
        return false;
      }
    }

    GrExpression right = assignment.getRValue();
    if (right == null) {
      return false;
    }

    GradleDslElement propertyElement = createExpressionElement(blockElement, assignment, name, right);
    propertyElement.setUseAssignment(true);
    propertyElement.setElementType(REGULAR);

    blockElement.setParsedElement(propertyElement);
    return true;
  }

  @NotNull
  private GradleDslExpression getExpressionElement(@NotNull GradleDslElement parentElement,
                                                   @NotNull GroovyPsiElement psiElement,
                                                   @NotNull GradleNameElement propertyName,
                                                   @NotNull GrExpression propertyExpression) {
    if (propertyExpression instanceof GrLiteral) { // ex: compileSdkVersion 23 or compileSdkVersion = "android-23"
      return new GradleDslLiteral(parentElement, psiElement, propertyName, propertyExpression, false);
    }

    if (propertyExpression instanceof GrReferenceExpression) { // ex: compileSdkVersion SDK_VERSION or sourceCompatibility = VERSION_1_5
      return new GradleDslLiteral(parentElement, psiElement, propertyName, propertyExpression, true);
    }

    if (propertyExpression instanceof GrMethodCallExpression) { // ex: compile project("someProject")
      GrMethodCallExpression methodCall = (GrMethodCallExpression)propertyExpression;
      GrReferenceExpression callReferenceExpression = getChildOfType(methodCall, GrReferenceExpression.class);
      if (callReferenceExpression != null) {
        String methodName = callReferenceExpression.getText();
        if (!methodName.isEmpty()) {
          GrArgumentList argumentList = methodCall.getArgumentList();
          return getMethodCall(parentElement, methodCall, propertyName, argumentList, methodName, false);
        }
      }
    }

    if (propertyExpression instanceof GrIndexProperty) {
      return new GradleDslLiteral(parentElement, psiElement, propertyName, propertyExpression, true);
    }

    if (propertyExpression instanceof GrNewExpression) {
      GrNewExpression newExpression = (GrNewExpression)propertyExpression;
      GrCodeReferenceElement referenceElement = newExpression.getReferenceElement();
      if (referenceElement != null) {
        String objectName = referenceElement.getReferenceName();
        if (objectName != null && !objectName.isEmpty()) {
          GrArgumentList argumentList = newExpression.getArgumentList();
          if (argumentList != null) {
            if (argumentList.getAllArguments().length > 0) {
              return getMethodCall(parentElement, newExpression, propertyName, argumentList, objectName, true);
            }
          }
        }
      }
    }

    // We have no idea what it is.
    parentElement.notification(INCOMPLETE_PARSING).addUnknownElement(propertyExpression);
    return new GradleDslUnknownElement(parentElement, propertyExpression, propertyName);
  }

  @NotNull
  private GradleDslMethodCall getMethodCall(@NotNull GradleDslElement parentElement,
                                            @NotNull PsiElement psiElement,
                                            @NotNull GradleNameElement propertyName,
                                            @NotNull GrArgumentList argumentList,
                                            @NotNull String methodName,
                                            boolean isConstructor) {
    GradleDslMethodCall methodCall = new GradleDslMethodCall(parentElement, psiElement, propertyName, methodName, isConstructor);
    GradleDslExpressionList arguments =
      getExpressionList(methodCall, argumentList, GradleNameElement.empty(),
                        Arrays.asList(argumentList.getExpressionArguments()), false);
    methodCall.setParsedArgumentList(arguments);

    GrNamedArgument[] namedArguments = argumentList.getNamedArguments();
    if (namedArguments.length > 0) {
      methodCall.addParsedExpression(
        getExpressionMap(methodCall, argumentList, GradleNameElement.empty(), Arrays.asList(namedArguments), false));
    }

    return methodCall;
  }

  @NotNull
  private GradleDslExpressionList getExpressionList(@NotNull GradleDslElement parentElement,
                                                    @NotNull GroovyPsiElement listPsiElement, // GrArgumentList or GrListOrMap
                                                    @NotNull GradleNameElement propertyName,
                                                    @NotNull List<GrExpression> propertyExpressions,
                                                    boolean isLiteral) {
    GradleDslExpressionList expressionList = new GradleDslExpressionList(parentElement, listPsiElement, isLiteral, propertyName);
    for (GrExpression expression : propertyExpressions) {
      GradleDslExpression expressionElement = createExpressionElement(expressionList, expression, GradleNameElement.empty(), expression);
      if (expressionElement instanceof GradleDslClosure) {
        // Only the last closure will count.
        parentElement.setParsedClosureElement((GradleDslClosure)expressionElement);
      }
      else {
        expressionList.addParsedExpression(expressionElement);
      }
    }
    return expressionList;
  }

  @NotNull
  private GradleDslExpressionMap getExpressionMap(@NotNull GradleDslElement parentElement,
                                                  @NotNull GroovyPsiElement mapPsiElement, // GrArgumentList or GrListOrMap
                                                  @NotNull GradleNameElement propertyName,
                                                  @NotNull List<GrNamedArgument> namedArguments,
                                                  boolean isLiteralMap) {
    GradleDslExpressionMap expressionMap = new GradleDslExpressionMap(parentElement, mapPsiElement, propertyName, isLiteralMap);
    for (GrNamedArgument namedArgument : namedArguments) {
      GrArgumentLabel nameLabel = namedArgument.getLabel();
      if (nameLabel == null) {
        continue;
      }
      GradleNameElement argName = GradleNameElement.from(nameLabel.getNameElement());
      if (argName.isEmpty()) {
        continue;
      }
      GrExpression valueExpression = namedArgument.getExpression();
      if (valueExpression == null) {
        continue;
      }
      GradleDslElement valueElement = createExpressionElement(expressionMap, mapPsiElement, argName, valueExpression);
      if (valueElement instanceof GradleDslUnknownElement && valueExpression instanceof GrListOrMap) {
        GrListOrMap listOrMap = (GrListOrMap)valueExpression;
        if (listOrMap.isMap()) {
          valueElement = getExpressionMap(expressionMap, listOrMap, argName, Arrays.asList(listOrMap.getNamedArguments()), true);
        }
        else { // ex: flatDir name: "libs", dirs: ["libs1", "libs2"]
          valueElement = getExpressionList(expressionMap, listOrMap, argName, Arrays.asList(listOrMap.getInitializers()), true);
        }
      }
      expressionMap.setParsedElement(valueElement);
    }
    return expressionMap;
  }

  @NotNull
  private GradleDslClosure getClosureElement(@NotNull GradleDslElement parentElement,
                                             @NotNull GrClosableBlock closableBlock,
                                             @NotNull GradleNameElement propertyName) {
    GradleDslClosure closureElement = new GradleDslClosure(parentElement, closableBlock, propertyName);
    parse(closableBlock, closureElement);
    return closureElement;
  }
}
