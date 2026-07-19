package com.maxvibes.domain.model.chat

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ChatSessionTest {

    @Test
    fun `new ChatSession has default values`() {
        val session = ChatSession()
        assertEquals("New Chat", session.title)
        assertNull(session.parentId)
        assertEquals(0, session.depth)
        assertTrue(session.messages.isEmpty())
        assertTrue(session.isRoot)
        assertTrue(session.tokenUsage.isEmpty())
    }

    @Test
    fun `withMessage adds message to session`() {
        val session = ChatSession()
        val msg = ChatMessage(role = MessageRole.USER, content = "Hello")
        val updated = session.withMessage(msg)
        assertEquals(1, updated.messages.size)
        assertEquals(msg, updated.messages[0])
    }

    @Test
    fun `withMessage auto-sets title from first USER message`() {
        val session = ChatSession()
        val msg = ChatMessage(role = MessageRole.USER, content = "Fix the login bug")
        val updated = session.withMessage(msg)
        assertEquals("Fix the login bug", updated.title)
    }

    @Test
    fun `withMessage does not change title after it is set`() {
        val session = ChatSession()
        val first = session.withMessage(ChatMessage(role = MessageRole.USER, content = "First message"))
        val second = first.withMessage(ChatMessage(role = MessageRole.USER, content = "Second message"))
        assertEquals("First message", second.title)
    }

    @Test
    fun `withMessage truncates long title to 40 chars with ellipsis`() {
        val session = ChatSession()
        val longContent = "A".repeat(50)
        val updated = session.withMessage(ChatMessage(role = MessageRole.USER, content = longContent))
        assertEquals(43, updated.title.length)
        assertTrue(updated.title.endsWith("..."))
    }

    @Test
    fun `withMessage does not auto-title for ASSISTANT messages`() {
        val session = ChatSession()
        val msg = ChatMessage(role = MessageRole.ASSISTANT, content = "I can help with that")
        val updated = session.withMessage(msg)
        assertEquals("New Chat", updated.title)
    }

    @Test
    fun `withTitle renames session`() {
        val session = ChatSession(title = "Old")
        val updated = session.withTitle("New Name")
        assertEquals("New Name", updated.title)
    }

    @Test
    fun `withTitle blank becomes Untitled`() {
        val session = ChatSession(title = "Old")
        val updated = session.withTitle("   ")
        assertEquals("Untitled", updated.title)
    }

    @Test
    fun `isRoot is true when parentId is null`() {
        val root = ChatSession(parentId = null)
        val child = ChatSession(parentId = "parent-id")
        assertTrue(root.isRoot)
        assertFalse(child.isRoot)
    }

    @Test
    fun `addPlanningTokens accumulates in tokenUsage`() {
        val session = ChatSession()
        val updated = session.addPlanningTokens(100, 50)
        assertEquals(100, updated.tokenUsage.planningInput)
        assertEquals(50, updated.tokenUsage.planningOutput)
    }

    @Test
    fun `addChatTokens accumulates in tokenUsage`() {
        val session = ChatSession()
        val updated = session.addChatTokens(200, 80)
        assertEquals(200, updated.tokenUsage.chatInput)
        assertEquals(80, updated.tokenUsage.chatOutput)
    }

    @Test
    fun `cleared removes all messages`() {
        val session = ChatSession().withMessage(ChatMessage(role = MessageRole.USER, content = "test"))
        assertEquals(1, session.messages.size)
        val cleared = session.cleared()
        assertTrue(cleared.messages.isEmpty())
    }

    @Test
    fun `ChatSession is immutable - withMessage returns new instance`() {
        val original = ChatSession()
        val updated = original.withMessage(ChatMessage(role = MessageRole.USER, content = "test"))
        assertTrue(original.messages.isEmpty())
        assertEquals(1, updated.messages.size)
    }

    @Test
    fun `withParent updates parentId and depth`() {
        val session = ChatSession()
        val updated = session.withParent("parent-123", 2)
        assertEquals("parent-123", updated.parentId)
        assertEquals(2, updated.depth)
        assertFalse(updated.isRoot)
    }
}
