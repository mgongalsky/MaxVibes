package com.maxvibes.application.service

import com.maxvibes.application.port.output.*
import com.maxvibes.domain.model.interaction.ClipboardSessionStatus
import com.maxvibes.shared.result.Result
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Unit tests for [ClipboardInteractionService.handleUserInput] dispatch logic.
 *
 * Verifies that [handleUserInput] routes to the correct internal method based on the
 * session status returned by a mocked [ClipboardSessionManager], without exercising
 * the full execution path of each branch.
 *
 * Port interfaces use relaxed mocks (safe no-op defaults); only the behaviours that
 * are strictly needed to observe routing are explicitly stubbed.
 */
class ClipboardInteractionServiceDispatchTest {

    private val SESSION_ID = "test-session-001"

    // Relaxed mocks — unmocked methods return safe defaults so branches can be entered
    private val contextProvider = mockk<ProjectContextPort>(relaxed = true)
    private val clipboardPort = mockk<ClipboardPort>(relaxed = true)
    private val codeRepository = mockk<CodeRepository>(relaxed = true)
    private val notificationPort = mockk<NotificationPort>(relaxed = true)

    /** Constructs a service with the given manager mock and shared port stubs. */
    private fun serviceWith(manager: ClipboardSessionManager) = ClipboardInteractionService(
        contextProvider = contextProvider,
        clipboardPort = clipboardPort,
        codeRepository = codeRepository,
        notificationPort = notificationPort,
        sessionManager = manager,
        chatSessionRepository = mockk(relaxed = true)
    )

    // ==================== Dispatch tests ====================

    /**
     * When status is IDLE, [handleUserInput] must route to [startTask].
     *
     * Verified by observing that [ClipboardEvent.StartSession] is fired on the manager,
     * which happens only inside [startTask]. The context provider is stubbed to return a
     * failure so the method exits early — we care only about the transition, not the result.
     */
    @Test
    fun `IDLE status routes to startTask and fires StartSession transition`() = runBlocking {
        val manager = mockk<ClipboardSessionManager>()
        every { manager.statusFor(SESSION_ID) } returns ClipboardSessionStatus.IDLE
        every { manager.transition(SESSION_ID, any()) } returns true
        coEvery { contextProvider.getProjectContext() } returns
                Result.Failure(ContextError.FileReadError("project", "stub"))

        serviceWith(manager).handleUserInput(SESSION_ID, "Hello")

        // StartSession transition is the fingerprint of the startTask branch
        verify { manager.transition(SESSION_ID, ClipboardEvent.StartSession) }
    }

    /**
     * When status is AWAITING_PASTE, [handleUserInput] must route to [handlePastedResponse].
     *
     * Verified by observing that [ClipboardEvent.ResponsePasted] is fired on the manager
     * (only happens inside [handlePastedResponseInternal]). Session state is null so the
     * branch returns Error after the transition — we only care that routing was correct.
     */
    @Test
    fun `AWAITING_PASTE status routes to handlePastedResponse and fires ResponsePasted transition`() = runBlocking {
        val manager = mockk<ClipboardSessionManager>()
        every { manager.statusFor(SESSION_ID) } returns ClipboardSessionStatus.AWAITING_PASTE
        every { manager.transition(SESSION_ID, ClipboardEvent.ResponsePasted) } returns true

        val result = serviceWith(manager).handleUserInput(SESSION_ID, "{\"message\":\"hi\"}")

        // ResponsePasted transition is the fingerprint of the handlePastedResponse branch
        verify { manager.transition(SESSION_ID, ClipboardEvent.ResponsePasted) }
        assertTrue(result is ClipboardStepResult.Error)
    }

    /**
     * When status is SESSION_ACTIVE, [handleUserInput] must route to [continueDialog].
     *
     * Since session state is null (service was never started), [continueDialog] returns
     * Error("No active clipboard session"). No state-machine transition is expected here.
     */
    @Test
    fun `SESSION_ACTIVE status routes to continueDialog`() = runBlocking {
        val manager = mockk<ClipboardSessionManager>()
        every { manager.statusFor(SESSION_ID) } returns ClipboardSessionStatus.SESSION_ACTIVE

        val result = serviceWith(manager).handleUserInput(SESSION_ID, "continue doing X")

        assertTrue(result is ClipboardStepResult.Error)
        assertTrue(
            (result as ClipboardStepResult.Error).message.contains("No active clipboard session"),
            "Expected session-missing error, got: ${result.message}"
        )
        // continueDialog must NOT fire any state-machine transition
        verify(exactly = 0) { manager.transition(any(), any()) }
    }

    /**
     * When [handlePastedResponse] is called but the state machine rejects the
     * [ClipboardEvent.ResponsePasted] transition (returns false), the method must return
     * [ClipboardStepResult.Error] immediately without attempting to parse the response.
     */
    @Test
    fun `handlePastedResponse returns Error immediately when transition is rejected`() = runBlocking {
        val manager = mockk<ClipboardSessionManager>()
        // Simulate an invalid transition: ResponsePasted is rejected (e.g. called from IDLE)
        every { manager.transition(SESSION_ID, ClipboardEvent.ResponsePasted) } returns false

        val result = serviceWith(manager).handlePastedResponse(SESSION_ID, "{\"message\":\"oops\"}")

        assertTrue(result is ClipboardStepResult.Error)
        val errorMsg = (result as ClipboardStepResult.Error).message
        assertTrue(
            errorMsg.contains("AWAITING_PASTE"),
            "Error message should mention AWAITING_PASTE, got: $errorMsg"
        )
        // clipboardPort.parseResponse must NOT be called — we bail before parsing
        coVerify(exactly = 0) { clipboardPort.parseResponse(any()) }
    }
}
