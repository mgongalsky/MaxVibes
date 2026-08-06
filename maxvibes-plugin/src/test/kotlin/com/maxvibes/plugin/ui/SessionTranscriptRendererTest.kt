package com.maxvibes.plugin.ui

import com.maxvibes.domain.model.chat.ChatMessage
import com.maxvibes.domain.model.chat.ChatSession
import com.maxvibes.domain.model.chat.MessageRole
import com.maxvibes.domain.model.modification.ModificationResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SessionTranscriptRendererTest {

    private val renderer = SessionTranscriptRenderer()

    @Test
    fun `empty session clears view and requests welcome state`() {
        val view = RecordingView()

        val rendered = renderer.render(
            session = ChatSession(messages = emptyList()),
            sessionPath = emptyList(),
            view = view,
            onModificationsRestored = {}
        )

        assertFalse(rendered)
        assertEquals(listOf("clear"), view.events)
    }

    @Test
    fun `branch banner and messages preserve display order`() {
        val root = ChatSession(id = "root", title = "Root session")
        val child = ChatSession(
            id = "child",
            title = "Child session",
            parentId = root.id,
            depth = 1,
            messages = listOf(
                ChatMessage(role = MessageRole.USER, content = "Hello"),
                ChatMessage(role = MessageRole.SYSTEM, content = "System note"),
                ChatMessage(role = MessageRole.ASSISTANT, content = "Hi")
            )
        )
        val view = RecordingView()

        val rendered = renderer.render(
            session = child,
            sessionPath = listOf(root, child),
            view = view,
            onModificationsRestored = {}
        )

        assertTrue(rendered)
        assertEquals(
            listOf(
                "clear",
                "system:└ Branch of: Root session",
                "user:Hello",
                "system:System note",
                "assistant:Hi"
            ),
            view.events
        )
    }

    @Test
    fun `assistant metadata and persisted modifications are restored`() {
        val assistant = ChatMessage(
            role = MessageRole.ASSISTANT,
            content = "Done",
            tokenInfo = "10 tokens",
            attachedFiles = listOf("src/Foo.kt"),
            reasoning = "Because",
            appliedModificationPaths = listOf(
                "file:src/Foo.kt/class[Foo]"
            )
        )
        val view = RecordingView()
        val restored = mutableListOf<List<ModificationResult>>()

        renderer.render(
            session = ChatSession(messages = listOf(assistant)),
            sessionPath = emptyList(),
            view = view,
            onModificationsRestored = { restored += it }
        )

        val renderedMessage = view.assistantMessages.single()
        assertEquals("Done", renderedMessage.first.content)
        assertEquals("10 tokens", renderedMessage.first.tokenInfo)
        assertEquals(listOf("src/Foo.kt"), renderedMessage.first.attachedFiles)
        assertEquals("Because", renderedMessage.first.reasoning)
        assertEquals(1, renderedMessage.second.size)
        assertTrue(renderedMessage.second.single() is ModificationResult.Success)
        assertEquals(renderedMessage.second, restored.single())
    }

    private class RecordingView : SessionTranscriptView {
        val events = mutableListOf<String>()
        val assistantMessages = mutableListOf<Pair<DisplayMessage, List<ModificationResult>>>()

        override fun clear() {
            events += "clear"
        }

        override fun addUser(text: String) {
            events += "user:$text"
        }

        override fun addAssistant(
            message: DisplayMessage,
            modifications: List<ModificationResult>
        ) {
            events += "assistant:${message.content}"
            assistantMessages += message to modifications
        }

        override fun addSystem(text: String) {
            events += "system:$text"
        }
    }
}
