package com.maxvibes.plugin.ui

import com.maxvibes.domain.model.interaction.ClipboardSessionStatus
import com.maxvibes.domain.model.interaction.InteractionMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChatModeCoordinatorTest {

    @Test
    fun `initialize synchronizes and selects the stored mode`() {
        val fixture = Fixture(initialMode = InteractionMode.CLAUDE_CODE)

        fixture.coordinator.initialize()

        assertEquals(1, fixture.modeState.syncCount)
        assertEquals(listOf(InteractionMode.CLAUDE_CODE), fixture.selectedModes)
    }

    @Test
    fun `cancelled clipboard reset restores the previous selection`() {
        val fixture = Fixture(initialMode = InteractionMode.CLIPBOARD)
        fixture.status = ClipboardSessionStatus.AWAITING_PASTE
        fixture.dialogs.confirmed = false

        fixture.coordinator.handleSelection(InteractionMode.API)

        assertEquals(InteractionMode.CLIPBOARD, fixture.modeState.currentMode)
        assertEquals(listOf(InteractionMode.CLIPBOARD), fixture.selectedModes)
        assertTrue(fixture.resetSessions.isEmpty())
    }

    @Test
    fun `confirmed clipboard reset switches mode and reports it`() {
        val fixture = Fixture(initialMode = InteractionMode.CLIPBOARD)
        fixture.status = ClipboardSessionStatus.AWAITING_PASTE
        fixture.dialogs.confirmed = true

        fixture.coordinator.handleSelection(InteractionMode.CLAUDE_CODE)

        assertEquals(InteractionMode.CLAUDE_CODE, fixture.modeState.currentMode)
        assertEquals(listOf("session"), fixture.resetSessions)
        assertEquals(1, fixture.statuses.size)
        assertEquals(1, fixture.systemMessages.size)
    }

    @Test
    fun `indicator action delegates and refreshes`() {
        val fixture = Fixture()

        fixture.coordinator.handleIndicatorAction(IndicatorAction.FORCE_ACTIVATE)

        assertEquals(listOf("session"), fixture.activatedSessions)
        assertEquals(1, fixture.refreshCount)
    }

    private class FakeModeState(
        override var currentMode: InteractionMode
    ) : InteractionModeState {
        var syncCount = 0

        override fun switchMode(newMode: InteractionMode) {
            currentMode = newMode
        }

        override fun syncFromSettings() {
            syncCount++
        }
    }

    private class FakeDialogs : ChatModeDialogs {
        var confirmed = true
        override fun confirmClipboardReset(): Boolean = confirmed
    }

    private class Fixture(initialMode: InteractionMode = InteractionMode.API) {
        val modeState = FakeModeState(initialMode)
        val dialogs = FakeDialogs()
        var status = ClipboardSessionStatus.IDLE
        val selectedModes = mutableListOf<InteractionMode>()
        val resetSessions = mutableListOf<String>()
        val activatedSessions = mutableListOf<String>()
        val awaitedSessions = mutableListOf<String>()
        val statuses = mutableListOf<String>()
        val systemMessages = mutableListOf<String>()
        var refreshCount = 0

        val coordinator = ChatModeCoordinator(
            modeState = modeState,
            dialogs = dialogs,
            clipboardStatus = { status },
            activeSessionId = { "session" },
            resetClipboard = { resetSessions += it },
            forceActivate = { activatedSessions += it },
            forceAwaitPaste = { awaitedSessions += it },
            onSelectMode = { selectedModes += it },
            onApplyDecision = {},
            onStatus = { statuses += it },
            onSystemMessage = { systemMessages += it },
            onRefresh = { refreshCount++ }
        )
    }
}
