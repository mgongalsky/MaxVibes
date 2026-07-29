package com.maxvibes.plugin.ui

import com.maxvibes.application.service.ChatTreeService
import com.maxvibes.domain.model.chat.ChatSession
import com.maxvibes.domain.model.interaction.InteractionMode
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class CommandResultRouterTest {
    private val chatTreeService = mockk<ChatTreeService>(relaxed = true)
    private val session = ChatSession()
    private val clipboardCalls = mutableListOf<Pair<ChatSession, String>>()
    private val claudeCodeCalls = mutableListOf<Pair<ChatSession, String>>()
    private val apiCalls = mutableListOf<Pair<ChatSession, String>>()
    private var missingSessionReported = false
    private lateinit var router: CommandResultRouter

    @BeforeEach
    fun setUp() {
        clipboardCalls.clear()
        claudeCodeCalls.clear()
        apiCalls.clear()
        missingSessionReported = false
        every { chatTreeService.getSessionById("session-id") } returns session
        router = CommandResultRouter(
            chatTreeService = chatTreeService,
            submitClipboard = { value, formatted -> clipboardCalls.add(value to formatted) },
            submitClaudeCode = { value, formatted -> claudeCodeCalls.add(value to formatted) },
            submitApi = { value, formatted -> apiCalls.add(value to formatted) },
            onMissingSession = { missingSessionReported = true }
        )
    }

    @Test
    fun `Clipboard result returns through Clipboard continuation`() {
        router.route("session-id", InteractionMode.CLIPBOARD, "result")

        assertEquals(listOf(session to "result"), clipboardCalls)
        assertTrue(claudeCodeCalls.isEmpty())
        assertTrue(apiCalls.isEmpty())
    }

    @Test
    fun `Claude Code result returns through Claude continuation`() {
        router.route("session-id", InteractionMode.CLAUDE_CODE, "result")

        assertEquals(listOf(session to "result"), claudeCodeCalls)
        assertTrue(clipboardCalls.isEmpty())
        assertTrue(apiCalls.isEmpty())
    }

    @Test
    fun `API result returns through API continuation`() {
        router.route("session-id", InteractionMode.API, "result")

        assertEquals(listOf(session to "result"), apiCalls)
    }

    @Test
    fun `Cheap API result uses regular API continuation`() {
        router.route("session-id", InteractionMode.CHEAP_API, "result")

        assertEquals(listOf(session to "result"), apiCalls)
    }

    @Test
    fun `missing session restores interaction without submitting result`() {
        every { chatTreeService.getSessionById("missing") } returns null

        router.route("missing", InteractionMode.CLAUDE_CODE, "result")

        assertTrue(missingSessionReported)
        assertTrue(clipboardCalls.isEmpty())
        assertTrue(claudeCodeCalls.isEmpty())
        assertTrue(apiCalls.isEmpty())
    }

    @Test
    fun `route resolves the requested session id exactly once`() {
        router.route("session-id", InteractionMode.CLIPBOARD, "result")

        io.mockk.verify(exactly = 1) {
            chatTreeService.getSessionById("session-id")
        }
        assertEquals(listOf(session to "result"), clipboardCalls)
    }

    @Test
    fun `missing Cheap API session restores interaction without API continuation`() {
        every { chatTreeService.getSessionById("missing-cheap") } returns null

        router.route("missing-cheap", InteractionMode.CHEAP_API, "result")

        assertTrue(missingSessionReported)
        assertTrue(apiCalls.isEmpty())
        assertTrue(clipboardCalls.isEmpty())
        assertTrue(claudeCodeCalls.isEmpty())
    }

    @Test
    fun `empty formatted API result is forwarded unchanged`() {
        router.route("session-id", InteractionMode.API, "")

        assertEquals(listOf(session to ""), apiCalls)
        assertTrue(clipboardCalls.isEmpty())
        assertTrue(claudeCodeCalls.isEmpty())
    }
}
