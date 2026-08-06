package com.maxvibes.plugin.ui

import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.JBColor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import javax.swing.JPanel
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener

/**
 * Claude Code model and reasoning-effort controls.
 *
 * Owns the Swing widgets and their two-way settings binding. The owner only controls
 * visibility and global enablement.
 */
class ClaudeCliSettingsPanel(
    settings: ClaudeCliSettings,
    onStatus: (String) -> Unit
) : JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)) {

    private val binder = ClaudeCliSettingsBinder(settings, onStatus)

    private val modelCombo = ComboBox<String>().apply {
        isEditable = true
        addItem("Auto")
        addItem("haiku")
        addItem("sonnet")
        addItem("opus")
        preferredSize = Dimension(120, 22)
        font = font.deriveFont(11f)
        toolTipText =
            "Claude Code CLI model. Auto = CLI default; type a full model name for anything else. Applies on next send."

        addActionListener {
            binder.commitModel(editor.item ?: selectedItem)
        }
        editor.editorComponent.addFocusListener(object : FocusAdapter() {
            override fun focusLost(e: FocusEvent?) {
                binder.commitModel(editor.item ?: selectedItem)
            }
        })
        addPopupMenuListener(object : PopupMenuListener {
            override fun popupMenuWillBecomeVisible(e: PopupMenuEvent?) {
                syncModel()
            }

            override fun popupMenuWillBecomeInvisible(e: PopupMenuEvent?) = Unit
            override fun popupMenuCanceled(e: PopupMenuEvent?) = Unit
        })
    }

    private val effortCombo = ComboBox(
        arrayOf("Auto", "low", "medium", "high", "xhigh", "max")
    ).apply {
        preferredSize = Dimension(90, 22)
        font = font.deriveFont(11f)
        toolTipText =
            "Reasoning effort (Claude Code). Auto = model default. Applies on next send."

        addActionListener {
            binder.commitEffort(selectedItem)
        }
        addPopupMenuListener(object : PopupMenuListener {
            override fun popupMenuWillBecomeVisible(e: PopupMenuEvent?) {
                syncEffort()
            }

            override fun popupMenuWillBecomeInvisible(e: PopupMenuEvent?) = Unit
            override fun popupMenuCanceled(e: PopupMenuEvent?) = Unit
        })
    }

    init {
        background = JBColor.background()
        isVisible = false
        add(modelCombo)
        add(effortCombo)
        sync()
    }

    fun sync() {
        syncModel()
        syncEffort()
    }

    private fun syncModel() {
        binder.syncModel { modelCombo.selectedItem = it }
    }

    private fun syncEffort() {
        binder.syncEffort { effortCombo.selectedItem = it }
    }

    fun setClaudeCodeVisible(visible: Boolean) {
        isVisible = visible
    }

    fun setControlsEnabled(enabled: Boolean) {
        modelCombo.isEnabled = enabled
        effortCombo.isEnabled = enabled
    }
}
