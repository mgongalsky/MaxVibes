package com.maxvibes.plugin.ui

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
        override var model: String = "",
        override var effortLevel: String = ""
    ) : ClaudeCliSettings

    @Test
    fun `initial sync displays stored settings without reporting changes`() = onEdt {
        val settings = FakeSettings(model = "opus", effortLevel = "high")
        val statuses = mutableListOf<String>()

        val panel = ClaudeCliSettingsPanel(settings) { statuses += it }

        assertEquals("opus", panel.modelCombo().selectedItem)
        assertEquals("high", panel.effortCombo().selectedItem)
        assertTrue(statuses.isEmpty())
        assertFalse(panel.isVisible)
    }

    @Test
    fun `combo selections are committed through the binder`() = onEdt {
        val settings = FakeSettings()
        val statuses = mutableListOf<String>()
        val panel = ClaudeCliSettingsPanel(settings) { statuses += it }

        panel.modelCombo().selectedItem = "sonnet"
        panel.effortCombo().selectedItem = "xhigh"

        assertEquals("sonnet", settings.model)
        assertEquals("xhigh", settings.effortLevel)
        assertEquals(
            listOf(
                "CLI model: sonnet — applies on next send",
                "CLI effort: xhigh — applies on next send"
            ),
            statuses
        )
    }

    @Test
    fun `Auto selections clear stored overrides`() = onEdt {
        val settings = FakeSettings(model = "opus", effortLevel = "high")
        val panel = ClaudeCliSettingsPanel(settings) {}

        panel.modelCombo().selectedItem = "Auto"
        panel.effortCombo().selectedItem = "Auto"

        assertEquals("", settings.model)
        assertEquals("", settings.effortLevel)
    }

    @Test
    fun `visibility and enablement are controlled as one component`() = onEdt {
        val panel = ClaudeCliSettingsPanel(FakeSettings()) {}

        panel.setClaudeCodeVisible(true)
        panel.setControlsEnabled(false)

        assertTrue(panel.isVisible)
        assertTrue(panel.findAll(JComboBox::class.java).all { !it.isEnabled })
    }

    private fun ClaudeCliSettingsPanel.modelCombo(): JComboBox<*> =
        findAll(JComboBox::class.java).single {
            it.toolTipText?.startsWith("Claude Code CLI model") == true
        }

    private fun ClaudeCliSettingsPanel.effortCombo(): JComboBox<*> =
        findAll(JComboBox::class.java).single {
            it.toolTipText?.startsWith("Reasoning effort") == true
        }

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
