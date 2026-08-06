package com.maxvibes.plugin.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Component
import java.awt.Container
import java.util.concurrent.FutureTask
import javax.swing.JButton
import javax.swing.SwingUtilities

class SpecificPromptPanelTest {

    @Test
    fun `render shows default selection and disables prompt actions`() = onEdt {
        val panel = createPanel()

        panel.render(listOf("Refactor"), null)

        val buttons = panel.findAll(JButton::class.java)
        val selector = buttons.single { it.text == "Just Code ▾" }
        assertEquals("No specific prompt active — click to select", selector.toolTipText)
        assertFalse(buttons.single { it.toolTipText == "Open current prompt file for editing" }.isEnabled)
        assertFalse(buttons.single { it.toolTipText == "Delete current prompt file" }.isEnabled)
    }

    @Test
    fun `render shows active prompt and enables prompt actions`() = onEdt {
        val panel = createPanel()

        panel.render(listOf("Refactor"), "Refactor")

        val buttons = panel.findAll(JButton::class.java)
        val selector = buttons.single { it.text == "Refactor ▾" }
        assertEquals("Active prompt: Refactor — click to change", selector.toolTipText)
        assertTrue(buttons.single { it.toolTipText == "Open current prompt file for editing" }.isEnabled)
        assertTrue(buttons.single { it.toolTipText == "Delete current prompt file" }.isEnabled)
    }

    @Test
    fun `action buttons delegate to callbacks`() = onEdt {
        val calls = mutableListOf<String>()
        val panel = createPanel(
            onCreate = { calls += "create" },
            onEdit = { calls += "edit" },
            onDelete = { calls += "delete" },
            onManage = { calls += "manage" }
        )
        panel.render(listOf("Refactor"), "Refactor")

        val buttons = panel.findAll(JButton::class.java)
        buttons.single { it.toolTipText == "Create new task prompt file in .maxvibes/prompts/specific/" }.doClick()
        buttons.single { it.toolTipText == "Open current prompt file for editing" }.doClick()
        buttons.single { it.toolTipText == "Delete current prompt file" }.doClick()
        buttons.single { it.toolTipText == "Manage skills & prompts" }.doClick()

        assertEquals(listOf("create", "edit", "delete", "manage"), calls)
    }

    @Test
    fun `setControlsEnabled updates every prompt control`() = onEdt {
        val panel = createPanel()
        panel.render(listOf("Refactor"), "Refactor")

        panel.setControlsEnabled(false)

        assertTrue(panel.findAll(JButton::class.java).all { !it.isEnabled })
    }

    private fun createPanel(
        onCreate: () -> Unit = {},
        onEdit: () -> Unit = {},
        onDelete: () -> Unit = {},
        onManage: () -> Unit = {}
    ): SpecificPromptPanel = SpecificPromptPanel(
        onSelectPrompt = {},
        onCreatePrompt = onCreate,
        onEditPrompt = onEdit,
        onDeletePrompt = onDelete,
        onManagePrompts = onManage
    )

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
