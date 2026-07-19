package com.maxvibes.application.service

import com.maxvibes.application.port.output.ChatSessionRepository
import com.maxvibes.domain.model.chat.ChatSession
import com.maxvibes.domain.model.interaction.ClipboardSessionStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

// ---------------------------------------------------------------------------
// Minimal in-memory ChatSessionRepository for unit tests.
// Keeps the tests free of IntelliJ SDK / persistence dependencies.
// ---------------------------------------------------------------------------
private class InMemoryChatSessionRepository : ChatSessionRepository {
    private val sessions = mutableMapOf<String, ChatSession>()
    private var activeId: String? = null
    private var globalFiles: List<String> = emptyList()

    override fun getAllSessions(): List<ChatSession> = sessions.values.toList()
    override fun getSessionById(id: String): ChatSession? = sessions[id]
    override fun getActiveSessionId(): String? = activeId
    override fun setActiveSessionId(sessionId: String) {
        activeId = sessionId
    }

    override fun saveSession(session: ChatSession) {
        sessions[session.id] = session
    }

    override fun deleteSession(sessionId: String) {
        sessions.remove(sessionId)
    }

    override fun getGlobalContextFiles(): List<String> = globalFiles
    override fun setGlobalContextFiles(files: List<String>) {
        globalFiles = files
    }
}

/**
 * Unit tests for [ClipboardSessionManager].
 *
 * Covers all valid transitions, all invalid transitions, edge cases,
 * and a parameterized test verifying that Reset works from every state.
 */
class ClipboardSessionManagerTest {

    private lateinit var repository: InMemoryChatSessionRepository
    private lateinit var manager: ClipboardSessionManager

    @BeforeEach
    fun setUp() {
        repository = InMemoryChatSessionRepository()
        manager = ClipboardSessionManager(repository, logger = null)
    }

    /** Creates a session with the given clipboard status, persists it, and returns it. */
    private fun sessionWithStatus(status: ClipboardSessionStatus): ChatSession {
        val session = ChatSession()
        return session.withClipboardStatus(status).also { repository.saveSession(it) }
    }

    // -----------------------------------------------------------------------
    // Valid transitions — must return true and update the status
    // -----------------------------------------------------------------------

    @Test
    fun `IDLE + StartSession transitions to SESSION_ACTIVE`() {
        val session = sessionWithStatus(ClipboardSessionStatus.IDLE)
        assertTrue(manager.transition(session.id, ClipboardEvent.StartSession))
        assertEquals(ClipboardSessionStatus.SESSION_ACTIVE, manager.statusFor(session.id))
    }

    @Test
    fun `SESSION_ACTIVE + JsonCopied transitions to AWAITING_PASTE`() {
        val session = sessionWithStatus(ClipboardSessionStatus.SESSION_ACTIVE)
        assertTrue(manager.transition(session.id, ClipboardEvent.JsonCopied))
        assertEquals(ClipboardSessionStatus.AWAITING_PASTE, manager.statusFor(session.id))
    }

    @Test
    fun `AWAITING_PASTE + JsonCopied stays AWAITING_PASTE (re-send allowed)`() {
        val session = sessionWithStatus(ClipboardSessionStatus.AWAITING_PASTE)
        assertTrue(manager.transition(session.id, ClipboardEvent.JsonCopied))
        assertEquals(ClipboardSessionStatus.AWAITING_PASTE, manager.statusFor(session.id))
    }

    @Test
    fun `AWAITING_PASTE + ResponsePasted transitions to SESSION_ACTIVE`() {
        val session = sessionWithStatus(ClipboardSessionStatus.AWAITING_PASTE)
        assertTrue(manager.transition(session.id, ClipboardEvent.ResponsePasted))
        assertEquals(ClipboardSessionStatus.SESSION_ACTIVE, manager.statusFor(session.id))
    }

    @Test
    fun `SESSION_ACTIVE + Reset transitions to IDLE`() {
        val session = sessionWithStatus(ClipboardSessionStatus.SESSION_ACTIVE)
        assertTrue(manager.transition(session.id, ClipboardEvent.Reset))
        assertEquals(ClipboardSessionStatus.IDLE, manager.statusFor(session.id))
    }

    @Test
    fun `AWAITING_PASTE + Reset transitions to IDLE`() {
        val session = sessionWithStatus(ClipboardSessionStatus.AWAITING_PASTE)
        assertTrue(manager.transition(session.id, ClipboardEvent.Reset))
        assertEquals(ClipboardSessionStatus.IDLE, manager.statusFor(session.id))
    }

    @Test
    fun `IDLE + Reset is a no-op and returns true`() {
        val session = sessionWithStatus(ClipboardSessionStatus.IDLE)
        assertTrue(manager.transition(session.id, ClipboardEvent.Reset))
        assertEquals(ClipboardSessionStatus.IDLE, manager.statusFor(session.id))
    }

    // -----------------------------------------------------------------------
    // Invalid transitions — must return false and leave the status unchanged
    // -----------------------------------------------------------------------

    @Test
    fun `IDLE + JsonCopied returns false and status stays IDLE`() {
        val session = sessionWithStatus(ClipboardSessionStatus.IDLE)
        assertFalse(manager.transition(session.id, ClipboardEvent.JsonCopied))
        assertEquals(ClipboardSessionStatus.IDLE, manager.statusFor(session.id))
    }

    @Test
    fun `IDLE + ResponsePasted returns false and status stays IDLE`() {
        val session = sessionWithStatus(ClipboardSessionStatus.IDLE)
        assertFalse(manager.transition(session.id, ClipboardEvent.ResponsePasted))
        assertEquals(ClipboardSessionStatus.IDLE, manager.statusFor(session.id))
    }

    @Test
    fun `SESSION_ACTIVE + StartSession returns false and status stays SESSION_ACTIVE`() {
        val session = sessionWithStatus(ClipboardSessionStatus.SESSION_ACTIVE)
        assertFalse(manager.transition(session.id, ClipboardEvent.StartSession))
        assertEquals(ClipboardSessionStatus.SESSION_ACTIVE, manager.statusFor(session.id))
    }

    @Test
    fun `SESSION_ACTIVE + ResponsePasted returns false and status stays SESSION_ACTIVE`() {
        val session = sessionWithStatus(ClipboardSessionStatus.SESSION_ACTIVE)
        assertFalse(manager.transition(session.id, ClipboardEvent.ResponsePasted))
        assertEquals(ClipboardSessionStatus.SESSION_ACTIVE, manager.statusFor(session.id))
    }

    @Test
    fun `AWAITING_PASTE + StartSession returns false and status stays AWAITING_PASTE`() {
        val session = sessionWithStatus(ClipboardSessionStatus.AWAITING_PASTE)
        assertFalse(manager.transition(session.id, ClipboardEvent.StartSession))
        assertEquals(ClipboardSessionStatus.AWAITING_PASTE, manager.statusFor(session.id))
    }

    // -----------------------------------------------------------------------
    // Edge cases
    // -----------------------------------------------------------------------

    @Test
    fun `statusFor unknown session returns IDLE`() {
        assertEquals(ClipboardSessionStatus.IDLE, manager.statusFor("non-existent-id"))
    }

    @Test
    fun `transition for unknown session returns false`() {
        assertFalse(manager.transition("non-existent-id", ClipboardEvent.StartSession))
    }

    /** Parameterized: Reset from any state must always result in IDLE. */
    @ParameterizedTest(name = "Reset from {0} results in IDLE")
    @EnumSource(ClipboardSessionStatus::class)
    fun `after Reset from any state, status is guaranteed IDLE`(initialStatus: ClipboardSessionStatus) {
        val session = sessionWithStatus(initialStatus)
        manager.transition(session.id, ClipboardEvent.Reset)
        assertEquals(ClipboardSessionStatus.IDLE, manager.statusFor(session.id))
    }
}
