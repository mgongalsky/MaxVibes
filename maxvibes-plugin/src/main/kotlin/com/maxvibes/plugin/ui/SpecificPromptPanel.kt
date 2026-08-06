package com.maxvibes.plugin.ui

import com.intellij.ui.JBColor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.JButton
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu

/**
 * Specific-prompt controls embedded in the chat input strip.
 *
 * Owns widget state and the selection popup. File operations and persistence remain outside
 * and are invoked only through callbacks.
 */
class SpecificPromptPanel(
    private val onSelectPrompt: (String?) -> Unit,
    private val onCreatePrompt: () -> Unit,
    private val onEditPrompt: () -> Unit,
    private val onDeletePrompt: () -> Unit,
    private val onManagePrompts: () -> Unit
) : JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)) {

    private val newPromptButton = JButton("+").apply {
        font = font.deriveFont(Font.BOLD, 12f)
        toolTipText = "Create new task prompt file in .maxvibes/prompts/specific/"
        preferredSize = Dimension(26, 22)
        isFocusPainted = false
    }

    private val editPromptButton = JButton("✏").apply {
        font = font.deriveFont(12f)
        toolTipText = "Open current prompt file for editing"
        preferredSize = Dimension(26, 22)
        isFocusPainted = false
        isEnabled = false
    }

    private val deletePromptButton = JButton("−").apply {
        font = font.deriveFont(Font.BOLD, 13f)
        toolTipText = "Delete current prompt file"
        preferredSize = Dimension(26, 22)
        isFocusPainted = false
        isEnabled = false
    }

    private val managePromptButton = JButton("⚙").apply {
        font = font.deriveFont(12f)
        toolTipText = "Manage skills & prompts"
        preferredSize = Dimension(26, 22)
        isFocusPainted = false
    }

    private val selectPromptButton = JButton("Just Code ▾").apply {
        font = font.deriveFont(11f)
        toolTipText = "Select task prompt"
        preferredSize = Dimension(200, 22)
        isFocusPainted = false
    }

    private var availablePrompts: List<String> = emptyList()
    private var selectedPromptName: String? = null

    init {
        background = JBColor.background()
        add(newPromptButton)
        add(editPromptButton)
        add(deletePromptButton)
        add(managePromptButton)
        add(selectPromptButton)

        newPromptButton.addActionListener { onCreatePrompt() }
        editPromptButton.addActionListener { onEditPrompt() }
        deletePromptButton.addActionListener { onDeletePrompt() }
        managePromptButton.addActionListener { onManagePrompts() }
        selectPromptButton.addActionListener { showSelectionPopup() }
    }

    fun render(availablePrompts: List<String>, selectedPromptName: String?) {
        this.availablePrompts = availablePrompts
        this.selectedPromptName = selectedPromptName

        val displayName = selectedPromptName ?: "Just Code"
        selectPromptButton.text = "$displayName ▾"
        selectPromptButton.toolTipText = if (selectedPromptName != null) {
            "Active prompt: $displayName — click to change"
        } else {
            "No specific prompt active — click to select"
        }

        val hasPrompt = selectedPromptName != null
        editPromptButton.isEnabled = hasPrompt
        deletePromptButton.isEnabled = hasPrompt
    }

    fun setControlsEnabled(enabled: Boolean) {
        newPromptButton.isEnabled = enabled
        editPromptButton.isEnabled = enabled
        deletePromptButton.isEnabled = enabled
        managePromptButton.isEnabled = enabled
        selectPromptButton.isEnabled = enabled
    }

    private fun showSelectionPopup() {
        val popup = JPopupMenu()
        (listOf("Just Code") + availablePrompts).forEach { name ->
            popup.add(JMenuItem(name).apply {
                val selected = if (name == "Just Code") {
                    selectedPromptName == null
                } else {
                    name == selectedPromptName
                }
                font = if (selected) font.deriveFont(Font.BOLD) else font
                addActionListener {
                    onSelectPrompt(if (name == "Just Code") null else name)
                }
            })
        }
        popup.show(selectPromptButton, 0, selectPromptButton.height)
    }
}
