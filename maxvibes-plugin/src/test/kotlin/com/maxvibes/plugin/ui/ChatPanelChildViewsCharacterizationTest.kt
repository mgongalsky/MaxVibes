package com.maxvibes.plugin.ui

import com.maxvibes.domain.model.interaction.InteractionMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import javax.swing.JPanel

class ChatPanelChildViewsCharacterizationTest {

    @Test
    fun `header delegates mode selection`() {
        val selectedModes = mutableListOf<InteractionMode>()
        val panel = ChatHeaderPanel(
            onModeSelected = { selectedModes += it },
            onIndicatorAction = {},
            onOpenCcLog = {},
            onShowSessions = {},
            onNewChat = {},
            onBranch = {},
            onDeleteChat = {},
            onOpenPrompts = {},
            onContextFiles = {},
            onClaudeInstructions = {},
            onToggleMaximize = {},
            onToggleWindowed = {},
            onSelectSession = {},
            onRenameSession = { _, _ -> }
        )

        panel.selectMode(InteractionMode.CLIPBOARD)

        assertEquals(listOf(InteractionMode.CLIPBOARD), selectedModes)
    }

    @Test
    fun `input submission trims text and captures defaults`() {
        val panel = createInputPanel()
        panel.setText("  explain this code  ")

        val submission = panel.takeSubmission()

        assertEquals(
            InputSubmission(
                text = "explain this code",
                planOnly = false,
                dryRun = false,
                addHistory = false
            ),
            submission
        )
    }

    @Test
    fun `blank input does not create submission`() {
        val panel = createInputPanel()
        panel.setText("   ")

        assertNull(panel.takeSubmission())
    }

    private fun createInputPanel(): ChatInputPanel = ChatInputPanel(
        promptBar = JPanel(),
        usageBar = JPanel(),
        onSend = {},
        onApprove = {},
        onCopyJson = {},
        onAttachTrace = {},
        onClearTrace = {},
        onAttachErrors = {},
        onClearErrors = {},
        onImagePasted = {},
        onClearImages = {},
        onClearOneShot = {}
    )
}
