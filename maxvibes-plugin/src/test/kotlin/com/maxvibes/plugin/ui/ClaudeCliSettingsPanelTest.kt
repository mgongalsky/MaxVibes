package com.maxvibes.plugin.ui

import com.maxvibes.domain.model.chat.CodingAgentProvider
import com.maxvibes.domain.model.interaction.CodingAgentCapabilities
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Component
import java.awt.Container
import java.util.concurrent.FutureTask
import javax.swing.JComboBox
import javax.swing.SwingUtilities

class ClaudeCliSettingsPanelTest {

    private class FakeSettings(
        override var provider: CodingAgentProvider = CodingAgentProvider.CLAUDE_CODE,
        override var model: String = "",
        override var effortLevel: String = ""
    ) : ClaudeCliSettings

    @Test
    fun `initial sync displays stored settings without reporting changes`() = onEdt {
        val settings = FakeSettings(model = "opus", effortLevel = "high")
        val statuses = mutableListOf<String>()

        val panel = ClaudeCliSettingsPanel(settings, { statuses += it }) {}

        assertEquals(CodingAgentProvider.CLAUDE_CODE, panel.agentCombo().selectedItem)
        assertEquals("opus", panel.modelCombo().selectedItem)
        assertEquals("high", panel.effortCombo().selectedItem)
        assertTrue(statuses.isEmpty())
        assertFalse(panel.isVisible)
    }

    @Test
    fun `combo selections are committed through the binder`() = onEdt {
        val settings = FakeSettings()
        val statuses = mutableListOf<String>()
        val panel = ClaudeCliSettingsPanel(settings, { statuses += it }) {}

        panel.modelCombo().selectedItem = "sonnet"
        panel.effortCombo().selectedItem = "xhigh"

        assertEquals("sonnet", settings.model)
        assertEquals("xhigh", settings.effortLevel)
        assertEquals(
            listOf(
                "Claude Code model: sonnet \u2014 applies on next send",
                "Claude Code effort: xhigh \u2014 applies on next send"
            ),
            statuses
        )
    }

    @Test
    fun `Auto selections clear stored overrides`() = onEdt {
        val settings = FakeSettings(model = "opus", effortLevel = "high")
        val panel = ClaudeCliSettingsPanel(settings, {}) {}

        panel.modelCombo().selectedItem = "Auto"
        panel.effortCombo().selectedItem = "Auto"

        assertEquals("", settings.model)
        assertEquals("", settings.effortLevel)
    }

    @Test
    fun `the offered values come from the selected agent`() = onEdt {
        val panel = ClaudeCliSettingsPanel(FakeSettings(), {}) {}
        val claude = CodingAgentCapabilities.of(CodingAgentProvider.CLAUDE_CODE)

        assertEquals(claude.models, panel.modelCombo().items().drop(1))
        assertEquals(claude.reasoningLevels, panel.effortCombo().items().drop(1))
    }

    @Test
    fun `switching the agent swaps the offered values and notifies the owner`() = onEdt {
        val settings = FakeSettings()
        val statuses = mutableListOf<String>()
        val switched = mutableListOf<CodingAgentProvider>()
        val panel = ClaudeCliSettingsPanel(settings, { statuses += it }) { switched += it }

        panel.agentCombo().selectedItem = CodingAgentProvider.CODEX

        val codex = CodingAgentCapabilities.of(CodingAgentProvider.CODEX)
        assertEquals(CodingAgentProvider.CODEX, settings.provider)
        assertEquals(listOf(CodingAgentProvider.CODEX), switched)
        assertEquals(codex.models, panel.modelCombo().items().drop(1))
        assertEquals(codex.reasoningLevels, panel.effortCombo().items().drop(1))
        assertTrue(statuses.isEmpty())
    }

    @Test
    fun `re-selecting the current agent is not reported as a switch`() = onEdt {
        val switched = mutableListOf<CodingAgentProvider>()
        val panel = ClaudeCliSettingsPanel(FakeSettings(), {}) { switched += it }

        panel.agentCombo().selectedItem = CodingAgentProvider.CLAUDE_CODE

        assertTrue(switched.isEmpty())
    }

    @Test
    fun `visibility and enablement are controlled as one component`() = onEdt {
        val panel = ClaudeCliSettingsPanel(FakeSettings(), {}) {}

        panel.setClaudeCodeVisible(true)
        panel.setControlsEnabled(false)

        assertTrue(panel.isVisible)
        assertTrue(panel.findAll(JComboBox::class.java).all { !it.isEnabled })
    }

    private fun ClaudeCliSettingsPanel.agentCombo(): JComboBox<*> =
        findAll(JComboBox::class.java).single { it.getItemAt(0) is CodingAgentProvider }

    private fun ClaudeCliSettingsPanel.modelCombo(): JComboBox<*> =
        findAll(JComboBox::class.java).single { it.isEditable }

    private fun ClaudeCliSettingsPanel.effortCombo(): JComboBox<*> =
        findAll(JComboBox::class.java).single {
            it.toolTipText?.startsWith("Reasoning effort") == true
        }

    private fun JComboBox<*>.items(): List<Any?> = (0 until itemCount).map { getItemAt(it) }

    private fun <T : Component> Container.findAll(type: Class<T>): List<T> {
        val result = mutableListOf<T>()
        fun visit(component: Component) {
            if (type.isInstance(component)) result += type.cast(component)
            if (component is Container) component.components.forEach(::visit)
        }
        visit(this)
        return result
    }

    private fun <T> onEdt(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        val task = FutureTask<T> { block() }
        SwingUtilities.invokeAndWait(task)
        return task.get()
    }
}
