package com.maxvibes.application.service

import com.maxvibes.application.port.output.*
import com.maxvibes.domain.model.chat.ChatSession
import com.maxvibes.domain.model.context.FileTree
import com.maxvibes.domain.model.context.GatheredContext
import com.maxvibes.domain.model.context.ProjectContext
import com.maxvibes.domain.model.interaction.ClipboardRequest
import com.maxvibes.domain.model.interaction.ClipboardSessionStatus
import com.maxvibes.shared.result.Result
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Regression test for the bug where IDE errors and trace text attached to one message
 * would bleed into subsequent messages even without the user pressing the button again.
 *
 * Verifies that [ideErrors] and [attachedContext] are strictly one-shot, per-turn fields:
 * present only in the [ClipboardRequest] for the turn the user explicitly attached them,
 * and absent in all subsequent turns.
 *
 * Uses a real [ClipboardSessionManager] backed by an in-memory [ChatSessionRepository]
 * so the full state-machine path is exercised without any IntelliJ SDK dependency.
 */
class IdeErrorsNotPersistedAcrossTurnsTest {

    // ── In-memory repository ──────────────────────────────────────────────────────

    /**
     * Minimal in-memory [ChatSessionRepository] for tests.
     * Avoids any XML serialisation / IntelliJ Platform dependency.
     */
    private class InMemorySessionRepository : ChatSessionRepository {
        private val sessions = mutableMapOf<String, ChatSession>()
        private var activeId: String? = null
        private var contextFiles: List<String> = emptyList()

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

        override fun getGlobalContextFiles(): List<String> = contextFiles
        override fun setGlobalContextFiles(files: List<String>) {
            contextFiles = files
        }
    }

    // ── Constants ─────────────────────────────────────────────────────────────────

    private val SESSION_ID = "test-session-ide-errors"

    /** Two fake IDE errors from two different files — simulates pressing the Errors button. */
    private val FAKE_IDE_ERRORS =
        "File: Foo.kt\nLine 5: Unresolved reference: bar\nFile: Bar.kt\nLine 12: Type mismatch: expected String, got Int"

    /** A fake trace/stacktrace attachment — simulates pressing the Trace button. */
    private val FAKE_TRACE =
        "java.lang.NullPointerException: Cannot invoke method on null\n\tat com.example.Foo.doSomething(Foo.kt:42)"

    // ── Test infrastructure ───────────────────────────────────────────────────────

    private lateinit var repository: InMemorySessionRepository
    private lateinit var contextProvider: ProjectContextPort
    private lateinit var clipboardPort: ClipboardPort
    private lateinit var notificationPort: NotificationPort
    private lateinit var codeRepository: CodeRepository
    private lateinit var service: ClipboardInteractionService

    /** Accumulates every [ClipboardRequest] passed to [ClipboardPort.copyRequestToClipboard]. */
    private val capturedRequests = mutableListOf<ClipboardRequest>()

    @BeforeEach
    fun setUp() {
        // Start with a fresh IDLE session in the in-memory store.
        repository = InMemorySessionRepository()
        repository.saveSession(ChatSession(id = SESSION_ID))

        contextProvider = mockk()
        clipboardPort = mockk()
        notificationPort = mockk(relaxed = true)   // progress calls are irrelevant here
        codeRepository = mockk(relaxed = true)      // no modifications in this flow

        // Stub a minimal project context so startTask/continueDialog don't fail.
        val fileTree = mockk<FileTree> {
            every { totalFiles } returns 5
            every { toCompactString(any()) } returns "📁 project\n  📄 Foo.kt\n  📄 Bar.kt"
        }
        val projectContext = mockk<ProjectContext> {
            every { name } returns "TestProject"
            every { this@mockk.fileTree } returns fileTree
        }
        coEvery { contextProvider.getProjectContext() } returns Result.Success(projectContext)
        coEvery { contextProvider.gatherFiles(any(), any()) } returns
                Result.Success(GatheredContext(files = emptyMap(), totalTokensEstimate = 0))

        // Capture every request the service tries to put on the clipboard.
        capturedRequests.clear()
        every { clipboardPort.copyRequestToClipboard(any()) } answers {
            capturedRequests.add(firstArg())
            true
        }

        // Build the service under test with a real state machine.
        val sessionManager = ClipboardSessionManager(repository, logger = null)
        service = ClipboardInteractionService(
            contextProvider = contextProvider,
            clipboardPort = clipboardPort,
            codeRepository = codeRepository,
            notificationPort = notificationPort,
            promptPort = null,
            logger = null,
            sessionManager = sessionManager,
            chatSessionRepository = repository
        )
    }

    // ── Tests ─────────────────────────────────────────────────────────────────────

    /**
     * Core regression test:
     * Turn 1 — user presses "Errors" button → errors appear in the JSON.
     * Turn 2 — user does NOT press "Errors" → errors must NOT appear in the JSON.
     */
    @Test
    fun `ide errors appear only in the turn they were attached and are absent in subsequent turns`() = runBlocking {

        // ── Turn 1: errors button was pressed ────────────────────────────────────
        val result1 = service.startTask(
            sessionId = SESSION_ID,
            currentMessage = "fix the compile errors please",
            ideErrors = FAKE_IDE_ERRORS
        )

        assertTrue(
            result1 is ClipboardStepResult.WaitingForResponse,
            "startTask must return WaitingForResponse, got: $result1"
        )
        assertEquals(1, capturedRequests.size, "exactly one clipboard write expected after startTask")

        val request1 = capturedRequests[0]
        assertNotNull(
            request1.ideErrors,
            "Turn 1: ideErrors must be present — user pressed the Errors button"
        )
        // Spot-check content rather than exact equality, so the test is not fragile to formatting.
        val errorCount = request1.ideErrors!!.split("File:").size - 1
        assertEquals(2, errorCount, "Turn 1: both fake errors must be forwarded to the LLM")

        // ── Simulate LLM response received: move session to SESSION_ACTIVE ────────
        // We bypass handlePastedResponse to keep the test focused on the attachment-persistence
        // bug. The state machine transition is enough — we update the repo directly.
        repository.saveSession(
            repository.getSessionById(SESSION_ID)!!.withClipboardStatus(ClipboardSessionStatus.SESSION_ACTIVE)
        )

        // ── Turn 2: user sends follow-up WITHOUT pressing "Errors" ───────────────
        val result2 = service.continueDialog(
            sessionId = SESSION_ID,
            message = "now rename the function to processItems",
            ideErrors = null   // button was NOT pressed this turn
        )

        assertTrue(
            result2 is ClipboardStepResult.WaitingForResponse,
            "continueDialog must return WaitingForResponse, got: $result2"
        )
        assertEquals(2, capturedRequests.size, "exactly two clipboard writes total")

        val request2 = capturedRequests[1]
        assertNull(
            request2.ideErrors,
            "Turn 2: ideErrors must be null — user did NOT press the Errors button"
        )
    }

    /**
     * Symmetric test for trace/stacktrace attachments:
     * Turn 1 — user attaches a trace → it appears in the JSON.
     * Turn 2 — no trace attached → it must NOT appear in the JSON.
     */
    @Test
    fun `attached trace appears only in the turn it was attached and is absent in subsequent turns`() = runBlocking {

        // ── Turn 1: trace attached ────────────────────────────────────────────────
        val result1 = service.startTask(
            sessionId = SESSION_ID,
            currentMessage = "help me fix this NPE",
            attachedContext = FAKE_TRACE
        )

        assertTrue(result1 is ClipboardStepResult.WaitingForResponse)
        assertEquals(1, capturedRequests.size)

        val request1 = capturedRequests[0]
        assertNotNull(request1.attachedContext, "Turn 1: attachedContext must be present — trace was attached")
        assertTrue(
            request1.attachedContext!!.contains("NullPointerException"),
            "Turn 1: attachedContext must contain the fake stacktrace"
        )

        // ── Simulate paste: advance to SESSION_ACTIVE ─────────────────────────────
        repository.saveSession(
            repository.getSessionById(SESSION_ID)!!.withClipboardStatus(ClipboardSessionStatus.SESSION_ACTIVE)
        )

        // ── Turn 2: no trace ──────────────────────────────────────────────────────
        val result2 = service.continueDialog(
            sessionId = SESSION_ID,
            message = "looks good, what else?",
            attachedContext = null   // no trace this turn
        )

        assertTrue(result2 is ClipboardStepResult.WaitingForResponse)
        assertEquals(2, capturedRequests.size)

        val request2 = capturedRequests[1]
        assertNull(
            request2.attachedContext,
            "Turn 2: attachedContext must be null — no trace was attached this turn"
        )
    }

    /**
     * Combined test: both errors AND trace are attached in turn 1.
     * Both must be absent in turn 2 when neither button is pressed.
     */
    @Test
    fun `both ide errors and trace are cleared between turns when neither button is pressed`() = runBlocking {

        // ── Turn 1: both attached ─────────────────────────────────────────────────
        service.startTask(
            sessionId = SESSION_ID,
            currentMessage = "this is crashing with errors",
            ideErrors = FAKE_IDE_ERRORS,
            attachedContext = FAKE_TRACE
        )

        val request1 = capturedRequests[0]
        assertNotNull(request1.ideErrors, "Turn 1: ideErrors must be set")
        assertNotNull(request1.attachedContext, "Turn 1: attachedContext must be set")

        // ── Advance state ─────────────────────────────────────────────────────────
        repository.saveSession(
            repository.getSessionById(SESSION_ID)!!.withClipboardStatus(ClipboardSessionStatus.SESSION_ACTIVE)
        )

        // ── Turn 2: nothing attached ──────────────────────────────────────────────
        service.continueDialog(
            sessionId = SESSION_ID,
            message = "ok now add a unit test for this",
            ideErrors = null,
            attachedContext = null
        )

        val request2 = capturedRequests[1]
        assertNull(request2.ideErrors, "Turn 2: ideErrors must be null")
        assertNull(request2.attachedContext, "Turn 2: attachedContext must be null")
    }
}
