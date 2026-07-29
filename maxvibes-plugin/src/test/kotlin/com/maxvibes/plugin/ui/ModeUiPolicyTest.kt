package com.maxvibes.plugin.ui

import com.maxvibes.domain.model.interaction.ClipboardSessionStatus
import com.maxvibes.domain.model.interaction.InteractionMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ModeUiPolicyTest {

    private fun decide(mode: InteractionMode, status: ClipboardSessionStatus = ClipboardSessionStatus.IDLE) =
        ModeUiPolicy.decide(mode, status)

    @Test
    fun `API hides the indicator and keeps dry run available`() {
        val d = decide(InteractionMode.API)

        assertEquals("Send", d.sendButtonText)
        assertFalse(d.indicatorVisible)
        assertTrue(d.dryRunVisible)
        assertFalse(d.copyJsonVisible)
        assertFalse(d.addHistoryVisible)
    }

    @Test
    fun `API leaves indicator text and decoration untouched`() {
        val d = decide(InteractionMode.API)

        assertNull(d.indicatorText)
        assertNull(d.indicatorDecoration)
    }

    @Test
    fun `cheap API shows its own indicator and leaves decoration untouched`() {
        val d = decide(InteractionMode.CHEAP_API)

        assertEquals("Send", d.sendButtonText)
        assertTrue(d.indicatorVisible)
        assertEquals("\uD83D\uDCB0", d.indicatorText)
        assertTrue(d.dryRunVisible)
        assertNull(d.indicatorDecoration)
    }

    @Test
    fun `clipboard awaiting paste offers force activate`() {
        val d = decide(InteractionMode.CLIPBOARD, ClipboardSessionStatus.AWAITING_PASTE)

        assertEquals("Paste", d.sendButtonText)
        assertEquals("\u23F3 Paste response", d.indicatorText)
        assertTrue(d.copyJsonVisible)
        assertEquals(IndicatorAction.FORCE_ACTIVATE, d.indicatorDecoration?.action)
        assertTrue(d.indicatorDecoration?.handCursor == true)
        assertEquals("Click to skip paste and continue dialog", d.indicatorDecoration?.tooltip)
    }

    @Test
    fun `clipboard session active offers force await paste`() {
        val d = decide(InteractionMode.CLIPBOARD, ClipboardSessionStatus.SESSION_ACTIVE)

        assertEquals("Send / Paste", d.sendButtonText)
        assertEquals("\uD83D\uDCCB Active", d.indicatorText)
        assertFalse(d.copyJsonVisible)
        assertEquals(IndicatorAction.FORCE_AWAIT_PASTE, d.indicatorDecoration?.action)
        assertEquals("Click to go back to paste mode", d.indicatorDecoration?.tooltip)
    }

    @Test
    fun `clipboard idle clears the decoration`() {
        val d = decide(InteractionMode.CLIPBOARD, ClipboardSessionStatus.IDLE)

        assertEquals("Generate", d.sendButtonText)
        assertEquals("\uD83D\uDCCB", d.indicatorText)
        val deco = d.indicatorDecoration
        assertFalse(deco?.handCursor ?: true)
        assertNull(deco?.tooltip)
        assertNull(deco?.action)
    }

    @Test
    fun `clipboard awaiting approve falls back to idle visuals`() {
        val idle = decide(InteractionMode.CLIPBOARD, ClipboardSessionStatus.IDLE)
        val approve = decide(InteractionMode.CLIPBOARD, ClipboardSessionStatus.AWAITING_APPROVE)

        assertEquals(idle, approve)
    }

    @Test
    fun `clipboard never shows dry run`() {
        ClipboardSessionStatus.values().forEach { status ->
            assertFalse(decide(InteractionMode.CLIPBOARD, status).dryRunVisible, "status=$status")
        }
    }

    @Test
    fun `claude code labels the indicator by status`() {
        assertEquals(
            "\uD83E\uDD16 Awaiting Approve",
            decide(InteractionMode.CLAUDE_CODE, ClipboardSessionStatus.AWAITING_APPROVE).indicatorText
        )
        assertEquals(
            "\uD83E\uDD16 Active",
            decide(InteractionMode.CLAUDE_CODE, ClipboardSessionStatus.SESSION_ACTIVE).indicatorText
        )
        assertEquals(
            "\uD83E\uDD16 Claude Code",
            decide(InteractionMode.CLAUDE_CODE, ClipboardSessionStatus.IDLE).indicatorText
        )
        assertEquals(
            "\uD83E\uDD16 Claude Code",
            decide(InteractionMode.CLAUDE_CODE, ClipboardSessionStatus.AWAITING_PASTE).indicatorText
        )
    }

    @Test
    fun `claude code hides clipboard specific controls`() {
        ClipboardSessionStatus.values().forEach { status ->
            val d = decide(InteractionMode.CLAUDE_CODE, status)
            assertFalse(d.addHistoryVisible, "status=$status")
            assertFalse(d.copyJsonVisible, "status=$status")
            assertFalse(d.dryRunVisible, "status=$status")
            assertEquals("Send", d.sendButtonText, "status=$status")
        }
    }

    @Test
    fun `cc log link is visible only in claude code mode`() {
        InteractionMode.values().forEach { mode ->
            ClipboardSessionStatus.values().forEach { status ->
                val expected = mode == InteractionMode.CLAUDE_CODE
                assertEquals(expected, decide(mode, status).ccLogLinkVisible, "mode=$mode status=$status")
            }
        }
    }

    @Test
    fun `add history is visible only in clipboard mode`() {
        InteractionMode.values().forEach { mode ->
            ClipboardSessionStatus.values().forEach { status ->
                val expected = mode == InteractionMode.CLIPBOARD
                assertEquals(expected, decide(mode, status).addHistoryVisible, "mode=$mode status=$status")
            }
        }
    }

    @Test
    fun `copy json is visible only while a clipboard paste is pending`() {
        InteractionMode.values().forEach { mode ->
            ClipboardSessionStatus.values().forEach { status ->
                val expected = mode == InteractionMode.CLIPBOARD && status == ClipboardSessionStatus.AWAITING_PASTE
                assertEquals(expected, decide(mode, status).copyJsonVisible, "mode=$mode status=$status")
            }
        }
    }

    @Test
    fun `a click action is offered only in the interactive clipboard states`() {
        InteractionMode.values().forEach { mode ->
            ClipboardSessionStatus.values().forEach { status ->
                val interactive = mode == InteractionMode.CLIPBOARD &&
                        (status == ClipboardSessionStatus.AWAITING_PASTE || status == ClipboardSessionStatus.SESSION_ACTIVE)
                val action = decide(mode, status).indicatorDecoration?.action
                assertEquals(interactive, action != null, "mode=$mode status=$status")
            }
        }
    }
}
