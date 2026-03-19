package com.maxvibes.application.service

import com.maxvibes.application.port.output.ChatMessageDTO
import com.maxvibes.application.port.output.ChatRole
import com.maxvibes.application.port.output.PromptTemplates
import com.maxvibes.domain.model.context.FileNode
import com.maxvibes.domain.model.context.FileTree
import com.maxvibes.domain.model.context.ProjectContext
import com.maxvibes.domain.model.interaction.ClipboardPhase
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ClipboardRequestBuilderTest {

    // ── Fixture helpers ───────────────────────────────────────────────

    /** Minimal valid [FileTree] with an empty root directory. */
    private fun emptyFileTree() = FileTree(
        root = FileNode(name = "TestProject", path = "", isDirectory = true),
        totalFiles = 0,
        totalDirectories = 0
    )

    /**
     * Creates a [ClipboardSessionState] with sensible defaults for testing.
     * All parameters are optional so each test only specifies what it cares about.
     *
     * Note: [attachedContext] and [ideErrors] are no longer stored in session state —
     * they are one-shot per-message values passed directly to [ClipboardRequestBuilder.build].
     */
    private fun makeState(
        currentMessage: String = "fix the bug",
        gatheredFiles: Map<String, String> = emptyMap(),
        history: List<ChatMessageDTO> = emptyList(),
        planOnly: Boolean = false
    ) = ClipboardSessionState(
        currentMessage = currentMessage,
        projectContext = ProjectContext(
            name = "TestProject",
            rootPath = "/tmp/test",
            fileTree = emptyFileTree()
        ),
        dialogHistory = history.toMutableList(),
        prompts = PromptTemplates(
            planningSystem = "PLANNING_PROMPT",
            chatSystem = "CHAT_PROMPT"
        ),
        allGatheredFiles = gatheredFiles.toMutableMap(),
        planOnly = planOnly
    )

    // ── Phase resolution ──────────────────────────────────────────────

    @Test
    fun `phase is PLANNING when no gathered files and no fresh files`() {
        val req = ClipboardRequestBuilder.build(
            state = makeState(),
            freshFiles = emptyMap(),
            isFirstMessage = true
        )
        assertEquals(ClipboardPhase.PLANNING, req.phase)
    }

    @Test
    fun `phase is CHAT when fresh files are present`() {
        val req = ClipboardRequestBuilder.build(
            state = makeState(),
            freshFiles = mapOf("src/Foo.kt" to "class Foo"),
            isFirstMessage = true
        )
        assertEquals(ClipboardPhase.CHAT, req.phase)
    }

    @Test
    fun `phase is CHAT when session already gathered files`() {
        val req = ClipboardRequestBuilder.build(
            state = makeState(gatheredFiles = mapOf("src/Bar.kt" to "class Bar")),
            freshFiles = emptyMap(),
            isFirstMessage = false
        )
        assertEquals(ClipboardPhase.CHAT, req.phase)
    }

    // ── Minimal mode ──────────────────────────────────────────────────

    @Test
    fun `minimal mode omits systemInstruction fileTree chatHistory`() {
        val history = listOf(
            ChatMessageDTO(ChatRole.USER, "first message"),
            ChatMessageDTO(ChatRole.ASSISTANT, "response")
        )
        val req = ClipboardRequestBuilder.build(
            state = makeState(history = history),
            freshFiles = emptyMap(),
            isFirstMessage = false,
            addHistory = false
        )
        assertTrue(req.systemInstruction.isBlank())
        assertTrue(req.fileTree.isBlank())
        assertTrue(req.chatHistory.isEmpty())
    }

    @Test
    fun `minimal mode uses last user message as task content`() {
        val history = listOf(
            ChatMessageDTO(ChatRole.USER, "first"),
            ChatMessageDTO(ChatRole.ASSISTANT, "reply"),
            ChatMessageDTO(ChatRole.USER, "second")
        )
        val req = ClipboardRequestBuilder.build(
            state = makeState(currentMessage = "original", history = history),
            freshFiles = emptyMap(),
            isFirstMessage = false,
            addHistory = false
        )
        assertEquals("second", req.currentMessage)
    }

    @Test
    fun `minimal mode omits attachedContext`() {
        // attachedContext is passed directly to build() — in minimal mode it must be dropped.
        val req = ClipboardRequestBuilder.build(
            state = makeState(),
            freshFiles = emptyMap(),
            isFirstMessage = false,
            addHistory = false,
            attachedContext = "some trace"
        )
        assertNull(req.attachedContext)
    }

    @Test
    fun `minimal mode sets planOnly to false`() {
        val req = ClipboardRequestBuilder.build(
            state = makeState(planOnly = true),
            freshFiles = emptyMap(),
            isFirstMessage = false,
            addHistory = false
        )
        assertFalse(req.planOnly)
    }

    // ── Full context mode ─────────────────────────────────────────────

    @Test
    fun `full mode includes chatHistory`() {
        val history = listOf(
            ChatMessageDTO(ChatRole.USER, "hi"),
            ChatMessageDTO(ChatRole.ASSISTANT, "hello")
        )
        val req = ClipboardRequestBuilder.build(
            state = makeState(history = history),
            freshFiles = emptyMap(),
            isFirstMessage = false,
            addHistory = true
        )
        assertEquals(2, req.chatHistory.size)
        assertEquals("user", req.chatHistory[0].role)
        assertEquals("assistant", req.chatHistory[1].role)
    }

    @Test
    fun `full mode includes previouslyGatheredPaths`() {
        val req = ClipboardRequestBuilder.build(
            state = makeState(gatheredFiles = mapOf("a.kt" to "A", "b.kt" to "B")),
            freshFiles = emptyMap(),
            isFirstMessage = false,
            addHistory = true
        )
        assertEquals(2, req.previouslyGatheredPaths.size)
        assertTrue(req.previouslyGatheredPaths.contains("a.kt"))
    }

    @Test
    fun `first message uses planningSystem prompt when no gathered files`() {
        val req = ClipboardRequestBuilder.build(
            state = makeState(),
            freshFiles = emptyMap(),
            isFirstMessage = true
        )
        assertEquals("PLANNING_PROMPT", req.systemInstruction)
    }

    @Test
    fun `chat system prompt used after files gathered`() {
        val req = ClipboardRequestBuilder.build(
            state = makeState(gatheredFiles = mapOf("x.kt" to "")),
            freshFiles = emptyMap(),
            isFirstMessage = false,
            addHistory = true
        )
        assertTrue(req.systemInstruction.startsWith("CHAT_PROMPT"))
    }

    @Test
    fun `planOnly appends suffix to chat system prompt`() {
        val req = ClipboardRequestBuilder.build(
            state = makeState(planOnly = true, gatheredFiles = mapOf("x.kt" to "")),
            freshFiles = emptyMap(),
            isFirstMessage = false,
            addHistory = true,
            planOnlySuffix = "\n\n## PLAN ONLY"
        )
        assertTrue(req.systemInstruction.endsWith("## PLAN ONLY"))
    }

    // ── IDE errors ────────────────────────────────────────────────────

    @Test
    fun `ideErrors is present in full mode`() {
        // ideErrors is now passed directly to build(), not stored in session state.
        val req = ClipboardRequestBuilder.build(
            state = makeState(),
            freshFiles = emptyMap(),
            isFirstMessage = true,
            ideErrors = "error: unresolved reference"
        )
        assertEquals("error: unresolved reference", req.ideErrors)
    }

    @Test
    fun `ideErrors is present in minimal mode`() {
        // IDE errors are always forwarded — they are per-turn diagnostics, not accumulated context.
        val req = ClipboardRequestBuilder.build(
            state = makeState(),
            freshFiles = emptyMap(),
            isFirstMessage = false,
            addHistory = false,
            ideErrors = "error: type mismatch"
        )
        assertEquals("error: type mismatch", req.ideErrors)
    }

    @Test
    fun `ideErrors is null when not passed`() {
        // Confirms that without explicit attachment the field stays null — the core bug fix.
        val req = ClipboardRequestBuilder.build(
            state = makeState(),
            freshFiles = emptyMap(),
            isFirstMessage = false,
            addHistory = false
        )
        assertNull(req.ideErrors)
    }
}
