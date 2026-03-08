package com.maxvibes.plugin.ui

import com.maxvibes.domain.model.chat.ChatMessage
import com.maxvibes.domain.model.chat.MessageRole
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Unit tests for ConversationRenderer.
 *
 * ConversationRenderer has no IntelliJ dependencies — all tests run without a full IDE environment.
 * This makes them fast and reliable as pure unit tests.
 */
class ConversationRendererTest {

    private val renderer = ConversationRenderer()

    // ===== render() tests =====

    @Test
    fun `render returns empty list for empty input`() {
        val result = renderer.render(emptyList())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `render includes USER and ASSISTANT messages`() {
        val messages = listOf(
            ChatMessage(role = MessageRole.USER, content = "Hello"),
            ChatMessage(role = MessageRole.ASSISTANT, content = "Hi there")
        )
        val result = renderer.render(messages)
        assertEquals(2, result.size)
        assertEquals(MessageRole.USER, result[0].role)
        assertEquals(MessageRole.ASSISTANT, result[1].role)
    }

    @Test
    fun `render filters out Pasted LLM response messages`() {
        val messages = listOf(
            ChatMessage(role = MessageRole.USER, content = "Real message"),
            ChatMessage(role = MessageRole.ASSISTANT, content = "[Pasted LLM response]"),
            ChatMessage(role = MessageRole.ASSISTANT, content = "Real response")
        )
        val result = renderer.render(messages)
        assertEquals(2, result.size)
        assertEquals("Real message", result[0].content)
        assertEquals("Real response", result[1].content)
    }

    @Test
    fun `render filters out blank messages`() {
        val messages = listOf(
            ChatMessage(role = MessageRole.USER, content = "Hello"),
            ChatMessage(role = MessageRole.ASSISTANT, content = "   "),
            ChatMessage(role = MessageRole.ASSISTANT, content = "")
        )
        val result = renderer.render(messages)
        assertEquals(1, result.size)
    }

    @Test
    fun `render preserves message order`() {
        val messages = listOf(
            ChatMessage(role = MessageRole.USER, content = "First"),
            ChatMessage(role = MessageRole.ASSISTANT, content = "Second"),
            ChatMessage(role = MessageRole.USER, content = "Third")
        )
        val result = renderer.render(messages)
        assertEquals("First", result[0].content)
        assertEquals("Second", result[1].content)
        assertEquals("Third", result[2].content)
    }

    @Test
    fun `render strips trace annotation from USER message`() {
        val messages = listOf(
            ChatMessage(role = MessageRole.USER, content = "Fix this bug\n[trace: 42 lines]")
        )
        val result = renderer.render(messages)
        assertEquals(1, result.size)
        assertEquals("Fix this bug", result[0].content)
    }

    @Test
    fun `render strips ide errors annotation from USER message`() {
        val messages = listOf(
            ChatMessage(role = MessageRole.USER, content = "Help me\n[attached ide errors]")
        )
        val result = renderer.render(messages)
        assertEquals("Help me", result[0].content)
    }

    @Test
    fun `render strips plan-only annotation from USER message`() {
        val messages = listOf(
            ChatMessage(role = MessageRole.USER, content = "Plan this\n[plan-only]")
        )
        val result = renderer.render(messages)
        assertEquals("Plan this", result[0].content)
    }

    @Test
    fun `render does not modify ASSISTANT message content`() {
        // ASSISTANT messages are stored and displayed verbatim — no annotations to strip.
        val rawContent = "Here is the plan\n[plan-only] is not an annotation in ASSISTANT messages"
        val messages = listOf(
            ChatMessage(role = MessageRole.ASSISTANT, content = rawContent)
        )
        val result = renderer.render(messages)
        // ASSISTANT content should be returned as-is (not stripped)
        assertEquals(rawContent, result[0].content)
    }

    @Test
    fun `render skips USER message that becomes blank after formatting`() {
        // A message that was only annotations with no real content
        val messages = listOf(
            ChatMessage(role = MessageRole.USER, content = "\n[trace: 5 lines]")
        )
        val result = renderer.render(messages)
        assertTrue(result.isEmpty())
    }

    // ===== shouldDisplay() tests =====

    @Test
    fun `shouldDisplay returns false for Pasted LLM response`() {
        val message = ChatMessage(role = MessageRole.ASSISTANT, content = "[Pasted LLM response]")
        assertFalse(renderer.shouldDisplay(message))
    }

    @Test
    fun `shouldDisplay returns false for blank content`() {
        val message = ChatMessage(role = MessageRole.USER, content = "  ")
        assertFalse(renderer.shouldDisplay(message))
    }

    @Test
    fun `shouldDisplay returns true for normal message`() {
        val message = ChatMessage(role = MessageRole.USER, content = "Normal message")
        assertTrue(renderer.shouldDisplay(message))
    }

    // ===== formatContent() tests =====

    @Test
    fun `formatContent removes Pasted LLM response artifact`() {
        val content = "[Pasted LLM response]\nActual content here"
        val result = renderer.formatContent(content)
        assertEquals("Actual content here", result)
    }

    @Test
    fun `formatContent trims whitespace`() {
        val content = "  Hello world  "
        val result = renderer.formatContent(content)
        assertEquals("Hello world", result)
    }

    @Test
    fun `formatContent removes trace annotation`() {
        val result = renderer.formatContent("task description\n[trace: 10 lines]")
        assertEquals("task description", result)
    }

    @Test
    fun `formatContent removes all annotations in one call`() {
        val content = "do something\n[trace: 3 lines]\n[attached ide errors]\n[plan-only]"
        val result = renderer.formatContent(content)
        assertEquals("do something", result)
    }

    @Test
    fun `formatContent leaves content unchanged when no annotations present`() {
        val content = "Simple task with no annotations"
        val result = renderer.formatContent(content)
        assertEquals(content, result)
    }
}
