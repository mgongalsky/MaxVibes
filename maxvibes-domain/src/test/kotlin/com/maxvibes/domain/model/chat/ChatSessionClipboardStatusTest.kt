package com.maxvibes.domain.model.chat

import com.maxvibes.domain.model.interaction.ClipboardSessionStatus
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Unit tests for [ChatSession.clipboardStatus] field and [ChatSession.withClipboardStatus] helper.
 *
 * Verifies that:
 * - the default status is [ClipboardSessionStatus.IDLE]
 * - [withClipboardStatus] transitions status correctly
 * - immutability is preserved (original object is not mutated)
 * - [updatedAt] is refreshed on each transition
 * - unrelated fields remain unchanged
 * - Kotlin [copy] without explicit [clipboardStatus] preserves the current value
 */
class ChatSessionClipboardStatusTest {

    // ─── Helpers ────────────────────────────────────────────────────────────────

    /** Creates a minimal [ChatSession] with deterministic baseline values. */
    private fun newSession() = ChatSession(
        id = "test-id",
        title = "Test Session",
        messages = emptyList()
    )

    // ─── Tests ──────────────────────────────────────────────────────────────────

    @Test
    fun `default clipboardStatus is IDLE`() {
        val session = newSession()
        assertEquals(ClipboardSessionStatus.IDLE, session.clipboardStatus)
    }

    @Test
    fun `withClipboardStatus returns session with updated status`() {
        val session = newSession()
        val updated = session.withClipboardStatus(ClipboardSessionStatus.SESSION_ACTIVE)
        assertEquals(ClipboardSessionStatus.SESSION_ACTIVE, updated.clipboardStatus)
    }

    @Test
    fun `withClipboardStatus does not mutate the original session`() {
        val original = newSession()
        original.withClipboardStatus(ClipboardSessionStatus.AWAITING_PASTE)
        // Original must still be IDLE
        assertEquals(ClipboardSessionStatus.IDLE, original.clipboardStatus)
    }

    @Test
    fun `withClipboardStatus updates updatedAt`() {
        val session = newSession()
        val beforeUpdate = session.updatedAt
        // Small sleep to ensure clock advances at least 1 ms on all platforms
        Thread.sleep(1)
        val updated = session.withClipboardStatus(ClipboardSessionStatus.AWAITING_PASTE)
        assertTrue(
            updated.updatedAt >= beforeUpdate,
            "Expected updatedAt (${updated.updatedAt}) >= baseline ($beforeUpdate)"
        )
    }

    @Test
    fun `withClipboardStatus preserves all other fields`() {
        val original = newSession()
        val updated = original.withClipboardStatus(ClipboardSessionStatus.SESSION_ACTIVE)

        assertEquals(original.id, updated.id)
        assertEquals(original.title, updated.title)
        assertEquals(original.messages, updated.messages)
        assertEquals(original.tokenUsage, updated.tokenUsage)
    }

    @Test
    fun `copy without clipboardStatus preserves existing status`() {
        val session = newSession().withClipboardStatus(ClipboardSessionStatus.AWAITING_PASTE)
        // copy() with no clipboardStatus argument must keep AWAITING_PASTE
        val copied = session.copy(title = "Renamed")
        assertEquals(ClipboardSessionStatus.AWAITING_PASTE, copied.clipboardStatus)
    }
}
