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
package com.android.tools.idea.wearparing

import com.android.tools.idea.observable.ListenerManager
import com.android.tools.idea.observable.core.BoolValueProperty
import com.android.tools.idea.observable.core.ObservableBool
import com.android.tools.idea.wearparing.ConnectionState.DISCONNECTED
import com.android.tools.idea.wizard.model.ModelWizard
import com.android.tools.idea.wizard.model.ModelWizardStep
import com.intellij.execution.runners.ExecutionUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.JBMenuItem
import com.intellij.openapi.ui.JBPopupMenu
import com.intellij.openapi.ui.Splitter
import com.intellij.ui.CollectionListModel
import com.intellij.ui.IdeBorderFactory
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SideBorder
import com.intellij.ui.SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.JBUI.Borders.empty
import com.intellij.util.ui.UIUtil
import icons.StudioIcons
import icons.StudioIcons.LayoutEditor.Toolbar.INSERT_HORIZ_CHAIN
import org.jetbrains.android.util.AndroidBundle.message
import java.awt.BorderLayout
import java.awt.Component
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseListener
import javax.swing.BoxLayout
import javax.swing.DefaultListSelectionModel
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
import javax.swing.ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
import javax.swing.SwingConstants
import javax.swing.SwingUtilities.isRightMouseButton

class DeviceListStep(model: WearDevicePairingModel, val project: Project, val emptyListClickedAction: () -> Unit) :
  ModelWizardStep<WearDevicePairingModel>(model, "") {
  private val listeners = ListenerManager()
  private val phoneList = createList(
    listName = "phoneList",
    emptyTextTitle = message("wear.assistant.device.list.no.phone")
  )
  private val wearList = createList(
    listName = "wearList",
    emptyTextTitle = message("wear.assistant.device.list.no.wear")
  )
  private val canGoForward = BoolValueProperty()

  override fun onWizardStarting(wizard: ModelWizard.Facade) {
    listeners.listenAndFire(model.deviceList) {
      val (wears, phones) = model.deviceList.get().partition { it.isWearDevice }

      updateList(phoneList, phones)
      updateList(wearList, wears)
    }
  }

  override fun createDependentSteps(): Collection<ModelWizardStep<*>> {
    return listOf(
      NewConnectionAlertStep(model, project),
      DevicesConnectionStep(model, project, true),
      DevicesConnectionStep(model, project, false)
    )
  }

  override fun getComponent(): JComponent = JBPanel<JBPanel<*>>(null).apply {
    border = empty(24)
    layout = BoxLayout(this, BoxLayout.Y_AXIS)

    add(JBLabel(message("wear.assistant.device.list.title"), UIUtil.ComponentStyle.LARGE).apply {
      font = JBFont.label().biggerOn(5.0f)
      alignmentX = Component.LEFT_ALIGNMENT
    })

    add(JBLabel(message("wear.assistant.device.list.subtitle")).apply {
      alignmentX = Component.LEFT_ALIGNMENT
      border = empty(24, 0)
    })

    add(Splitter(false, 0.5f).apply {
      alignmentX = Component.LEFT_ALIGNMENT
      firstComponent = createDevicePanel(message("wear.assistant.device.list.phone.header"), phoneList)
      secondComponent = createDevicePanel(message("wear.assistant.device.list.wear.header"), wearList)
    })
  }

  override fun onProceeding() {
    model.phoneDevice.setNullableValue(phoneList.selectedValue)
    model.wearDevice.setNullableValue(wearList.selectedValue)
  }

  override fun canGoForward(): ObservableBool = canGoForward

  override fun dispose() = listeners.releaseAll()

  private fun updateGoForward() {
    canGoForward.set(phoneList.selectedValue != null && wearList.selectedValue != null)
  }

  private fun createDevicePanel(title: String, list: JBList<PairingDevice>): JPanel {
    return JPanel(BorderLayout()).apply {
      border = IdeBorderFactory.createBorder(SideBorder.ALL)

      add(JBLabel(title).apply {
        font = JBFont.label().asBold()
        border = empty(4, 16)
      }, BorderLayout.NORTH)
      add(ScrollPaneFactory.createScrollPane(list, VERTICAL_SCROLLBAR_AS_NEEDED, HORIZONTAL_SCROLLBAR_NEVER).apply {
        border = IdeBorderFactory.createBorder(SideBorder.TOP)
      }, BorderLayout.CENTER)
    }
  }

  private fun createList(listName: String, emptyTextTitle: String): JBList<PairingDevice> {
    return JBList<PairingDevice>().apply {
      name = listName
      setCellRenderer { _, value, _, isSelected, cellHasFocus ->

        JPanel().apply {
          layout = GridBagLayout()
          add(JBLabel(getDeviceIcon(value)))
          add(
            JPanel().apply {
              layout = BoxLayout(this, BoxLayout.Y_AXIS)
              border = JBUI.Borders.emptyLeft(8)
              isOpaque = false
              add(JBLabel(value.displayName).apply {
                icon = if (!value.isWearDevice && value.hasPlayStore) StudioIcons.Avd.DEVICE_PLAY_STORE else null
                horizontalTextPosition = SwingConstants.LEFT
              })
              add(JBLabel(value.versionName))
            },
            GridBagConstraints().apply {
              fill = GridBagConstraints.HORIZONTAL
              weightx = 1.0
              gridx = 1
            }
          )
          if (value.isPaired) {
            add(JBLabel(INSERT_HORIZ_CHAIN))
          }

          isOpaque = true
          background = UIUtil.getListBackground(isSelected, cellHasFocus)
          foreground = UIUtil.getListForeground(isSelected, cellHasFocus)
          UIUtil.setEnabled(this, value.state != DISCONNECTED, true)
          border = empty(4, 16)
        }
      }

      selectionModel = SomeDisabledSelectionModel(this)
      emptyTextTitle.split("\n").forEach {
        emptyText.appendLine(it)
      }
      emptyText.appendLine(message("wear.assistant.device.list.open.avd"), LINK_PLAIN_ATTRIBUTES) {
        emptyListClickedAction()
      }

      addListSelectionListener {
        if (!it.valueIsAdjusting) {
          updateGoForward()
        }
      }

      addRightClickAction()
    }
  }

  fun updateList(uiList: JBList<PairingDevice>, deviceList: List<PairingDevice>) {
    if (uiList.model.size == deviceList.size) {
      deviceList.forEachIndexed { index, device ->
        val listDevice = uiList.model.getElementAt(index)
        if (listDevice != device) {
          (uiList.model as CollectionListModel).setElementAt(device, index)
          if (device.isPaired && device.state != DISCONNECTED && uiList.selectedIndex < 0) {
            uiList.selectedIndex = index
          }
        }
      }
    }
    else {
      uiList.model = CollectionListModel(deviceList)
    }

    if (uiList.selectedValue?.state == DISCONNECTED) {
      uiList.clearSelection()
    }
    updateGoForward()
  }

  private fun getDeviceIcon(device: PairingDevice): Icon {
    val baseIcon = if (device.isWearDevice) StudioIcons.Avd.DEVICE_WEAR else StudioIcons.Avd.DEVICE_PHONE
    return if (device.isOnline()) ExecutionUtil.getLiveIndicator(baseIcon) else baseIcon
  }

  private fun JBList<PairingDevice>.addRightClickAction() {
    val listener: MouseListener = object : MouseAdapter() {
      override fun mousePressed(e: MouseEvent) {
        val row = locationToIndex(e.point)
        if (row >= 0 && isRightMouseButton(e)) {
          val listDevice = model.getElementAt(row)
          if (listDevice.isPaired) {
            val menu = JBPopupMenu()
            val item = JBMenuItem(message("wear.assistant.device.list.forget.connection"))
            item.addActionListener {
              WearPairingManager.removeKeepForwardAlive()
            }
            menu.add(item)
            JBPopupMenu.showByEvent(e, menu)
          }
        }
      }
    }
    addMouseListener(listener)
  }

  private class SomeDisabledSelectionModel(val list: JBList<PairingDevice>) : DefaultListSelectionModel() {
    init {
      selectionMode = ListSelectionModel.SINGLE_SELECTION
    }

    override fun setSelectionInterval(idx0: Int, idx1: Int) {
      // Note from javadoc: in SINGLE_SELECTION selection mode, only the second index is used
      val n = if (idx1 < 0 || idx1 >= list.model.size || list.model.getElementAt(idx1).state == DISCONNECTED) -1 else idx1
      super.setSelectionInterval(n, n)
    }
  }
}