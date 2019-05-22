/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.tools.idea.npw.template

import com.android.tools.adtui.TabularLayout
import com.android.tools.adtui.TooltipLabel
import com.android.tools.adtui.validation.ValidatorPanel
import com.android.tools.idea.npw.FormFactor
import com.android.tools.idea.npw.assetstudio.icon.AndroidIconType
import com.android.tools.idea.npw.model.RenderTemplateModel
import com.android.tools.idea.npw.platform.Language
import com.android.tools.idea.npw.project.AndroidGradleModuleUtils
import com.android.tools.idea.npw.template.components.CheckboxProvider
import com.android.tools.idea.npw.template.components.ComponentProvider
import com.android.tools.idea.npw.template.components.EnumComboProvider
import com.android.tools.idea.npw.template.components.LabelWithEditButtonProvider
import com.android.tools.idea.npw.template.components.LanguageComboProvider
import com.android.tools.idea.npw.template.components.ModuleTemplateComboProvider
import com.android.tools.idea.npw.template.components.PackageComboProvider
import com.android.tools.idea.npw.template.components.ParameterComponentProvider
import com.android.tools.idea.npw.template.components.SeparatorProvider
import com.android.tools.idea.npw.template.components.TextFieldProvider
import com.android.tools.idea.observable.AbstractProperty
import com.android.tools.idea.observable.BindingsManager
import com.android.tools.idea.observable.ListenerManager
import com.android.tools.idea.observable.core.ObjectProperty
import com.android.tools.idea.observable.core.ObservableBool
import com.android.tools.idea.observable.core.StringProperty
import com.android.tools.idea.observable.core.StringValueProperty
import com.android.tools.idea.observable.expressions.Expression
import com.android.tools.idea.observable.ui.IconProperty
import com.android.tools.idea.observable.ui.SelectedItemProperty
import com.android.tools.idea.observable.ui.TextProperty
import com.android.tools.idea.observable.ui.VisibleProperty
import com.android.tools.idea.projectsystem.NamedModuleTemplate
import com.android.tools.idea.templates.CircularParameterDependencyException
import com.android.tools.idea.templates.Parameter
import com.android.tools.idea.templates.ParameterValueResolver
import com.android.tools.idea.templates.StringEvaluator
import com.android.tools.idea.templates.Template
import com.android.tools.idea.templates.TemplateMetadata
import com.android.tools.idea.templates.TemplateMetadata.ATTR_CLASS_NAME
import com.android.tools.idea.templates.TemplateMetadata.ATTR_IS_LAUNCHER
import com.android.tools.idea.templates.TemplateMetadata.ATTR_PACKAGE_NAME
import com.android.tools.idea.ui.wizard.StudioWizardStepPanel.wrappedWithVScroll
import com.android.tools.idea.ui.wizard.WizardUtils
import com.android.tools.idea.wizard.model.ModelWizardStep
import com.google.common.base.Joiner
import com.google.common.base.Strings
import com.google.common.io.Files
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.module.Module
import com.intellij.ui.PopupHandler
import com.intellij.ui.RecentsManager
import com.intellij.ui.components.JBLabel
import com.intellij.uiDesigner.core.GridConstraints
import com.intellij.uiDesigner.core.GridConstraints.ANCHOR_CENTER
import com.intellij.uiDesigner.core.GridConstraints.ANCHOR_NORTHWEST
import com.intellij.uiDesigner.core.GridConstraints.ANCHOR_WEST
import com.intellij.uiDesigner.core.GridConstraints.FILL_BOTH
import com.intellij.uiDesigner.core.GridConstraints.FILL_HORIZONTAL
import com.intellij.uiDesigner.core.GridConstraints.FILL_NONE
import com.intellij.uiDesigner.core.GridLayoutManager
import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.io.File
import java.util.Optional
import javax.swing.Box
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSeparator
import javax.swing.SwingConstants

private val log get() = logger<ConfigureTemplateParametersStep>()

/**
 * A step which takes a [Template] (generated by a template.xml file) and wraps a UI around it,
 * allowing a user to modify its various parameters.
 *
 * Far from being generic data, the template edited by this step is very Android specific, and  needs to be aware of things like
 * the current project/module, package name, min supported API, previously configured values, etc.
 */
class ConfigureTemplateParametersStep(model: RenderTemplateModel, title: String, private val myTemplates: List<NamedModuleTemplate>)
  : ModelWizardStep<RenderTemplateModel>(model, title) {
  private val bindings = BindingsManager()
  private val listeners = ListenerManager()
  private val thumbnailsCache = IconLoader.createLoadingCache()
  private val parameterRows = hashMapOf<Parameter, RowEntry<*>>()
  private val userValues = hashMapOf<Parameter, Any>()
  private val parameterEvaluator = StringEvaluator()
  private val thumbPath = StringValueProperty()
  /**
   * Validity check of all parameters is performed when any parameter changes, and the first error found is set here.
   * This is then registered as its own validator with [validatorPanel].
   * This vastly simplifies validation, as we no longer have to worry about implicit relationships between parameters
   * (when changing one makes another valid/invalid).
   */
  private val invalidParameterMessage = StringValueProperty()

  private val templateDescriptionLabel = JLabel().apply {
    font = Font("Default", Font.BOLD, 18)
  }
  private val templateThumbLabel = JLabel().apply {
    horizontalTextPosition = SwingConstants.CENTER
    verticalAlignment = SwingConstants.TOP
  }
  private val parametersPanel = JPanel(TabularLayout("Fit-,*").setVGap(10))
  private val footerSeparator = JSeparator()
  private val parameterDescriptionLabel = TooltipLabel().apply {
    setScope(parametersPanel)
    // Add an extra blank line under the template description to separate it from the main body
    border = JBUI.Borders.emptyBottom(templateDescriptionLabel.font.size)
  }

  private val rootPanel = JPanel(GridLayoutManager(4, 3)).apply {
    val anySize = Dimension(-1, -1)
    add(templateDescriptionLabel, GridConstraints(0, 1, 1, 1, ANCHOR_WEST, FILL_BOTH, 3, 0, anySize, anySize, anySize, 0, false))
    add(templateThumbLabel, GridConstraints(1, 0, 1, 1, ANCHOR_NORTHWEST, FILL_NONE, 0, 0, anySize, anySize, anySize, 0, false))
    add(parametersPanel, GridConstraints(1, 1, 1, 2, ANCHOR_CENTER, FILL_BOTH, 3, 7, anySize, anySize, anySize, 0, false))
    add(footerSeparator, GridConstraints(2, 1, 1, 1, ANCHOR_CENTER, FILL_HORIZONTAL, 3, 0, anySize, anySize, anySize, 0, false))
    add(parameterDescriptionLabel, GridConstraints(3, 1, 1, 1, ANCHOR_WEST, FILL_NONE, 1, 2, anySize, anySize, anySize, 0, false))
  }

  private val validatorPanel: ValidatorPanel = ValidatorPanel(this, wrappedWithVScroll(rootPanel))

  private var evaluationState = EvaluationState.NOT_EVALUATING

  private val isNewModule: Boolean
    get() = model.module == null

  /**
   * Get the default thumbnail path, which is useful at initialization time before we have all parameters set up.
   */
  private val defaultThumbnailPath: String
    get() = model.templateHandle!!.metadata.thumbnailPath ?: ""

  /**
   * Get the current thumbnail path, based on current parameter values.
   */
  private val currentThumbnailPath: String
    get() = model.templateHandle!!.metadata.getThumbnailPath { parameterId ->
      val parameter = model.templateHandle!!.metadata.getParameter(parameterId!!)
      parameterRows[parameter]?.property?.get()
    } ?: ""

  /**
   * Given a parameter, return a String key we can use to interact with IntelliJ's [RecentsManager] system.
   */
  private fun getRecentsKeyForParameter(parameter: Parameter) = "android.template.${parameter.id!!}"

  override fun createDependentSteps(): Collection<ModelWizardStep<*>> {
    val template = model.templateHandle
    return if (template != null && template.metadata.iconType == AndroidIconType.NOTIFICATION) {
      checkNotNull(model.androidFacet) {
        "Android Facet is null only for a not-yet-created project but it's impossible to create new project with notification activity"
      }
      listOf(GenerateIconsStep(model.androidFacet!!, model))
    }
    else {
      super.createDependentSteps()
    }
  }

  override fun shouldShow(): Boolean = model.templateHandle != null

  @Suppress("UNCHECKED_CAST")
  override fun onEntering() {
    // The Model TemplateHandle may have changed, rebuild the panel
    resetPanel()

    val templateHandle = model.templateHandle
    val templateMetadata = templateHandle!!.metadata

    ApplicationManager.getApplication().invokeLater(
      {
        // We want to set the label's text AFTER the wizard has been packed. Otherwise, its
        // width calculation gets involved and can really stretch out some wizards if the label is
        // particularly long (see Master/Detail Activity for example).
        templateDescriptionLabel.text = WizardUtils.toHtmlString(templateMetadata.description ?: "")
      }, ModalityState.any())

    templateMetadata.formFactor?.let {
      icon = FormFactor[it].icon
    }

    val thumb = IconProperty(templateThumbLabel)
    val thumbVisibility = VisibleProperty(templateThumbLabel)
    bindings.bind(thumb, object : Expression<Optional<Icon>>(thumbPath) {
      override fun get(): Optional<Icon> = thumbnailsCache.getUnchecked(File(templateHandle.rootPath, thumbPath.get()))
    })
    bindings.bind(thumbVisibility, object : Expression<Boolean>(thumb) {
      override fun get(): Boolean = thumb.get().isPresent
    })
    thumbPath.set(defaultThumbnailPath)

    val parameterDescription = TextProperty(parameterDescriptionLabel)
    bindings.bind(VisibleProperty(footerSeparator), object : Expression<Boolean>(parameterDescription) {
      override fun get(): Boolean = parameterDescription.get().isNotEmpty()
    })

    for (parameter in templateMetadata.parameters) {
      val row = createRowForParameter(model.module, parameter)
      val property = row.property
      if (property != null) {
        property.addListener {
          // If not evaluating, change comes from the user (or user pressed "Back" and updates are "external". eg Template changed)
          if (evaluationState != EvaluationState.EVALUATING && rootPanel.isShowing) {
            userValues[parameter] = property.get()
            // Evaluate later to prevent modifying Swing values that are locked during read
            enqueueEvaluateParameters()
          }
        }

        val resetParameterGroup = object : ActionGroup() {
          override fun getChildren(e: AnActionEvent?): Array<AnAction> {
            return arrayOf(ResetParameterAction(parameter))
          }
        }
        row.component.addMouseListener(object : PopupHandler() {
          override fun invokePopup(comp: Component, x: Int, y: Int) {
            ActionManager.getInstance().createActionPopupMenu(ActionPlaces.UNKNOWN, resetParameterGroup).component.show(comp, x, y)
          }
        })
      }
      parameterRows[parameter] = row
      row.addToPanel(parametersPanel)
    }

    if (displayLanguageChoice(templateMetadata)) {
      val row = RowEntry("Source Language", LanguageComboProvider())
      row.addToPanel(parametersPanel)
      val language = (row.property as SelectedItemProperty<Language>)
      // LanguageComboProvider always sets this
      bindings.bindTwoWay(ObjectProperty.wrap(language), model.language)
    }

    if (myTemplates.size > 1) {
      val row = RowEntry("Target Source Set", ModuleTemplateComboProvider(myTemplates))
      row.setEnabled(myTemplates.size > 1)
      row.addToPanel(parametersPanel)

      val template = (row.property as SelectedItemProperty<NamedModuleTemplate>)
      // ModuleTemplateComboProvider always sets this
      bindings.bind(model.template, ObjectProperty.wrap(template))
      template.addListener { enqueueEvaluateParameters() }
    }

    validatorPanel.registerMessageSource(invalidParameterMessage)

    evaluateParameters()
  }

  // Note: For new projects and new module we have a different UI.
  private fun displayLanguageChoice(templateMetadata: TemplateMetadata) =
    if (model.androidFacet == null) false
    // For Templates with an Android FormFactor or that have a class/package name, we allow the user to select the programming language
    else templateMetadata.run { formFactor != null || getParameter(ATTR_CLASS_NAME) != null || getParameter(ATTR_PACKAGE_NAME) != null }

  /**
   * Every template parameter, based on its type, can generate a row of* components. For example, a text parameter becomes a
   * "Label: Textfield" set, while a list of choices becomes "Label: pulldown".
   *
   * This method takes an input [Parameter] and returns a generated [RowEntry] for  it, which neatly encapsulates its UI.
   * The caller should use [RowEntry.addToPanel] after receiving it.
   */
  private fun createRowForParameter(module: Module?, parameter: Parameter): RowEntry<*> {
    requireNotNull(parameter.name)
    val name = parameter.name

    // TODO: Extract this logic into an extension point at some point, in order to be more friendly to third-party plugins with templates?
    if (ATTR_PACKAGE_NAME == parameter.id) {
      val rowEntry = if (module != null)
        RowEntry(name, PackageComboProvider(module.project, parameter, model.packageName.get(), getRecentsKeyForParameter(parameter)))
      else
        RowEntry(name, LabelWithEditButtonProvider(parameter))

      // All ATTR_PACKAGE_NAME providers should be string types and provide StringProperties
      val packageName = (rowEntry.property as StringProperty?)!!
      bindings.bindTwoWay(packageName, model.packageName)
      // Model.packageName is used for parameter evaluation, but updated asynchronously. Do new evaluation when value changes.
      listeners.listen(model.packageName) { enqueueEvaluateParameters() }
      return rowEntry
    }

    return when (parameter.type) {
      Parameter.Type.STRING -> RowEntry(name, TextFieldProvider(parameter))
      Parameter.Type.BOOLEAN -> RowEntry(CheckboxProvider(parameter), false)
      Parameter.Type.SEPARATOR -> RowEntry(SeparatorProvider(parameter), true)
      Parameter.Type.ENUM -> RowEntry(name, EnumComboProvider(parameter))
    }
  }

  /**
   * Instead of evaluating all parameters immediately, invoke the request to run later. This option allows us to avoid the situation where
   * a value has just changed, is forcefully re-evaluated immediately, and causes Swing to throw an exception between we're editing a
   * value while it's in a locked read-only state.
   */
  private fun enqueueEvaluateParameters() {
    if (evaluationState == EvaluationState.REQUEST_ENQUEUED) {
      return
    }
    evaluationState = EvaluationState.REQUEST_ENQUEUED

    ApplicationManager.getApplication().invokeLater({ this.evaluateParameters() }, ModalityState.any())
  }

  /** If we are creating a new module, there are some fields that we need to hide. */
  private fun isParameterVisible(parameter: Parameter): Boolean =
    !isNewModule || ATTR_PACKAGE_NAME != parameter.id && ATTR_IS_LAUNCHER != parameter.id

  /**
   * Run through all parameters for our current template and update their values,
   * including visibility, enabled state, and actual values.
   *
   * Because our templating system is opaque to us, this operation is relatively overkill
   * (we evaluate all parameters every time, not just ones we suspect have changed),
   * but this should only get run in response to user input, which isn't too often.
   */
  private fun evaluateParameters() {
    evaluationState = EvaluationState.EVALUATING

    val parameters = model.templateHandle!!.metadata.parameters
    val excludedParameters = hashSetOf<String>()

    try {
      val additionalValues = mutableMapOf<String, Any>()
      TemplateValueInjector(additionalValues).addTemplateAdditionalValues(model.packageName.get(), model.template)

      val allValues = additionalValues.toMutableMap()

      val parameterValues = ParameterValueResolver.resolve(parameters, userValues, additionalValues, ParameterDeduplicator())
      for (parameter in parameters) {
        val value = parameterValues[parameter] ?: continue
        parameterRows[parameter]!!.setValue(value)
        allValues[parameter.id!!] = value
      }

      // TODO(qumeric): simplify this loop when we will have new parameters
      for (parameter in parameters) {
        checkNotNull(parameter.id)
        val enabledStr = parameter.enabled ?: ""
        if (enabledStr.isNotEmpty()) {
          val enabled = parameterEvaluator.evaluateBooleanExpression(enabledStr, allValues, true)
          parameterRows[parameter]!!.setEnabled(enabled)
          if (!enabled) {
            excludedParameters.add(parameter.id)
          }
        }

        if (!isParameterVisible(parameter)) {
          parameterRows[parameter]!!.setVisible(false)
          excludedParameters.add(parameter.id)
          continue
        }

        val visibilityStr = parameter.visibility ?: ""
        if (visibilityStr.isNotEmpty()) {
          val visible = parameterEvaluator.evaluateBooleanExpression(visibilityStr, allValues, true)
          parameterRows[parameter]!!.setVisible(visible)
          if (!visible) {
            excludedParameters.add(parameter.id)
          }
        }
      }
      // Aggressively update the icon path just in case it changed
      thumbPath.set(currentThumbnailPath)
    }
    catch (e: CircularParameterDependencyException) {
      log.error("Circular dependency between parameters in template %1\$s", e, model.templateHandle!!.metadata.title!!)
    }
    finally {
      evaluationState = EvaluationState.NOT_EVALUATING
    }

    invalidParameterMessage.set(validateAllParametersExcept(excludedParameters) ?: "")
  }

  private fun validateAllParametersExcept(excludedParameters: Set<String>): String? {
    val parameters = model.templateHandle!!.metadata.parameters
    val project = model.project.valueOrNull
    val sourceProvider = AndroidGradleModuleUtils.getSourceProvider(model.template.get())

    return parameters.mapNotNull { parameter ->
      val property = parameterRows[parameter]?.property
      if (property == null || excludedParameters.contains(parameter.id)) {
        return@mapNotNull null
      }

      parameter.validate(project, model.module, sourceProvider, model.packageName.get(), property.get(), getRelatedValues(parameter))
    }.firstOrNull()
  }

  override fun getComponent(): JComponent = validatorPanel

  override fun getPreferredFocusComponent(): JComponent? = parametersPanel.components.firstOrNull {
    val child = it as JComponent
    child.componentCount == 0 && child.isFocusable && child.isVisible
  } as? JComponent

  override fun canGoForward(): ObservableBool = validatorPanel.hasErrors().not()

  private fun resetPanel() {
    parametersPanel.removeAll()
    parameterRows.clear()
    dispose()
  }

  override fun dispose() {
    bindings.releaseAll()
    listeners.releaseAll()
    thumbnailsCache.invalidateAll()
  }

  /**
   * When finished with this step, calculate and install a bunch of values that will be used in our
   * template's [data model](http://freemarker.incubator.apache.org/docs/dgui_quickstart_basics.html).
   */
  override fun onProceeding() {
    // Some parameter values should be saved for later runs through this wizard, so do that first.
    parameterRows.values.forEach { rowEntry ->
      rowEntry.accept()
    }

    // Prepare the template data-model, starting from scratch and filling in all values we know
    model.templateValues.clear()

    for (parameter in parameterRows.keys) {
      val property = parameterRows[parameter]?.property ?: continue
      model.templateValues[parameter.id!!] = property.get()
    }
  }

  /**
   * Fetches the values of all parameters that are related to the target parameter. This is useful when validating a parameter's value.
   */
  private fun getRelatedValues(parameter: Parameter): Set<Any> =
    parameter.template.getRelatedParams(parameter).mapNotNull { parameterRows[it]?.property?.get() }.toSet()

  /**
   * Because the FreeMarker templating engine is mostly opaque to us, any time any parameter changes, we need to re-evaluate all parameters.
   * Parameter evaluation can be started immediately via [evaluateParameters] or with a delay using [enqueueEvaluateParameters].
   */
  private enum class EvaluationState {
    NOT_EVALUATING,
    REQUEST_ENQUEUED,
    EVALUATING
  }

  /**
   * A template is broken down into separate fields, each which is given a row with optional header.
   * This class wraps all UI elements in the row, providing methods for managing them.
   */
  private class RowEntry<T : JComponent> {
    val component: T
    val property: AbstractProperty<*>?

    private val header: JPanel?
    private val componentProvider: ComponentProvider<T>
    /** A row is usually broken into two columns, but the item can optionally grow into both columns if it doesn't have a header. */
    private val wantGrow: Boolean

    constructor(headerText: String, componentProvider: ComponentProvider<T>) {
      val headerLabel = JBLabel("$headerText:")
      header = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
        add(headerLabel)
        add(Box.createHorizontalStrut(20))
      }
      wantGrow = false
      this.componentProvider = componentProvider
      component = componentProvider.createComponent()
      property = componentProvider.createProperty(component)

      headerLabel.labelFor = component
    }

    constructor(componentProvider: ParameterComponentProvider<T>, stretch: Boolean) {
      header = null
      wantGrow = stretch
      this.componentProvider = componentProvider
      component = componentProvider.createComponent()
      property = componentProvider.createProperty(component)
    }

    fun addToPanel(panel: JPanel) {
      require(panel.layout is TabularLayout)
      val row = panel.componentCount

      if (header != null) {
        panel.add(header, TabularLayout.Constraint(row, 0))
        assert(!wantGrow)
      }

      val colSpan = if (wantGrow) 2 else 1
      panel.add(component, TabularLayout.Constraint(row, 1, colSpan))
    }

    fun setEnabled(enabled: Boolean) {
      header?.isEnabled = enabled
      component.isEnabled = enabled
    }

    fun setVisible(visible: Boolean) {
      header?.isVisible = visible
      component.isVisible = visible
    }

    @Suppress("UNCHECKED_CAST")
    fun <V> setValue(value: V) {
      checkNotNull(property)
      (property as AbstractProperty<V>).set(value)
    }

    fun accept() {
      componentProvider.accept(component)
    }
  }

  private inner class ParameterDeduplicator : ParameterValueResolver.Deduplicator {
    override fun deduplicate(parameter: Parameter, value: String?): String? {
      if (Strings.isNullOrEmpty(value) || !parameter.constraints.contains(Parameter.Constraint.UNIQUE)) {
        return value
      }

      var suggested: String? = value
      val extPart = Files.getFileExtension(value!!)

      // First remove file extension. Then remove all trailing digits, because we probably were the ones that put them there.
      // For example, if two parameters affect each other, say "Name" and "Layout", you get this:
      // Step 1) Resolve "Name" -> "Name2", causes related "Layout" to become "Layout2"
      // Step 2) Resolve "Layout2" -> "Layout22"
      // Although we may possibly strip real digits from a name, it's much more likely we're not,
      // and a user can always modify the related value manually in that rare case.
      val namePart = value.replace(".$extPart", "").replace("\\d*$".toRegex(), "")
      val filenameJoiner = Joiner.on('.').skipNulls()

      var suffix = 2
      val project = model.project.valueOrNull
      val relatedValues = getRelatedValues(parameter)
      val sourceProvider = AndroidGradleModuleUtils.getSourceProvider(model.template.get())
      while (!parameter.uniquenessSatisfied(project, model.module, sourceProvider, model.packageName.get(), suggested, relatedValues)) {
        suggested = filenameJoiner.join(namePart + suffix, Strings.emptyToNull(extPart))
        suffix++
      }
      return suggested
    }
  }

  /**
   * Right-click context action which lets the user clear any modifications they made to a parameter.
   * Once cleared, the parameter is re-evaluated.
   */
  private inner class ResetParameterAction(private val myParameter: Parameter)
    : AnAction("Restore default value", "Discards any user modifications made to this parameter", AllIcons.General.Reset) {

    override fun update(e: AnActionEvent) {
      e.presentation.isEnabled = userValues.containsKey(myParameter)
    }

    override fun actionPerformed(e: AnActionEvent) {
      userValues.remove(myParameter)
      evaluateParameters()
    }
  }
}
