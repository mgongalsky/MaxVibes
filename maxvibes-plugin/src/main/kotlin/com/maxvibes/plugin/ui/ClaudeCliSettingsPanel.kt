package com.maxvibes.plugin.ui

import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleListCellRenderer
import com.maxvibes.domain.model.chat.CodingAgentProvider
import com.maxvibes.domain.model.interaction.CodingAgentCapabilities
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import javax.swing.JPanel
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener

/**
 * Coding-agent selector plus the model and reasoning-effort controls of that agent.
 *
 * Owns the Swing widgets and their two-way settings binding; the offered values come
 * from the selected agent's capabilities. The owner only controls visibility and
 * global enablement, and reacts to an agent switch through [onAgentChanged].
 */
class ClaudeCliSettingsPanel(
    private val settings: ClaudeCliSettings,
    onStatus: (String) -> Unit,
    private val onAgentChanged: (CodingAgentProvider) -> Unit
) : JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)) {

    private val binder = ClaudeCliSettingsBinder(settings, onStatus)

    /** Agent whose catalog is currently in the combos; null until the first refresh. */
    private var shownProvider: CodingAgentProvider? = null
    private var suppressAgent = false

    private val agentCombo = ComboBox(CodingAgentProvider.values()).apply {
        preferredSize = Dimension(110, 22)
        font = font.deriveFont(11f)
        toolTipText = "Coding agent driving this chat. Switching starts a fresh agent session."
        renderer = SimpleListCellRenderer.create("") { CodingAgentCapabilities.of(it).displayName }

        addActionListener {
            commitAgent(selectedItem as? CodingAgentProvider)
        }
    }

    private val modelCombo = ComboBox<String>().apply {
        isEditable = true
        preferredSize = Dimension(140, 22)
        font = font.deriveFont(11f)

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
                sync()
            }

            override fun popupMenuWillBecomeInvisible(e: PopupMenuEvent?) = Unit
            override fun popupMenuCanceled(e: PopupMenuEvent?) = Unit
        })
    }

    private val effortCombo = ComboBox<String>().apply {
        preferredSize = Dimension(90, 22)
        font = font.deriveFont(11f)

        addActionListener {
            binder.commitEffort(selectedItem)
        }
        addPopupMenuListener(object : PopupMenuListener {
            override fun popupMenuWillBecomeVisible(e: PopupMenuEvent?) {
                sync()
            }

            override fun popupMenuWillBecomeInvisible(e: PopupMenuEvent?) = Unit
            override fun popupMenuCanceled(e: PopupMenuEvent?) = Unit
        })
    }

    init {
        background = JBColor.background()
        isVisible = false
        add(agentCombo)
        add(modelCombo)
        add(effortCombo)
        sync()
    }

    fun sync() {
        syncAgent()
        refreshCatalog()
        syncModel()
        syncEffort()
    }

    private fun commitAgent(chosen: CodingAgentProvider?) {
        if (suppressAgent || chosen == null || chosen == settings.provider) return
        settings.provider = chosen
        refreshCatalog()
        syncModel()
        syncEffort()
        onAgentChanged(chosen)
    }

    private fun syncAgent() {
        suppressAgent = true
        try {
            agentCombo.selectedItem = settings.provider
        } finally {
            suppressAgent = false
        }
    }

    /**
     * Rebuilds the offered values only when the agent actually changed — an
     * unconditional rebuild would discard a hand-typed model on every render.
     */
    private fun refreshCatalog() {
        val provider = settings.provider
        if (shownProvider == provider) return
        shownProvider = provider

        val capabilities = CodingAgentCapabilities.of(provider)
        binder.suppressingModel {
            modelCombo.removeAllItems()
            modelCombo.addItem(AGENT_SETTING_AUTO)
            capabilities.models.forEach(modelCombo::addItem)
        }
        binder.suppressingEffort {
            effortCombo.removeAllItems()
            effortCombo.addItem(AGENT_SETTING_AUTO)
            capabilities.reasoningLevels.forEach(effortCombo::addItem)
        }

        modelCombo.toolTipText = "${capabilities.displayName} model. " +
                "Auto = CLI default; type a full model name for anything else. Applies on next send."
        effortCombo.toolTipText =
            "Reasoning effort (${capabilities.displayName}). Auto = model default. Applies on next send."
    }

    private fun syncModel() {
        binder.syncModel { modelCombo.selectedItem = it }
    }

    private fun syncEffort() {
        binder.syncEffort { effortCombo.selectedItem = it }
    }

    /** Re-syncs only on the hidden -> visible edge, so a render never clobbers in-progress typing. */
    fun setClaudeCodeVisible(visible: Boolean) {
        if (visible && !isVisible) sync()
        isVisible = visible
    }

    fun setControlsEnabled(enabled: Boolean) {
        agentCombo.isEnabled = enabled
        modelCombo.isEnabled = enabled
        effortCombo.isEnabled = enabled
    }
}
