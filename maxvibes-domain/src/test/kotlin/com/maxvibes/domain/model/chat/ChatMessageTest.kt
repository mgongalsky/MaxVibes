package com.maxvibes.domain.model.chat

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ChatMessageTest {

    @Test
    fun `ChatMessage created with correct fields`() {
        val msg = ChatMessage(id = "123", role = MessageRole.USER, content = "Hello", timestamp = 1000L)
        assertEquals("123", msg.id)
        assertEquals(MessageRole.USER, msg.role)
        assertEquals("Hello", msg.content)
        assertEquals(1000L, msg.timestamp)
    }

    @Test
    fun `ChatMessage default id is non-blank UUID`() {
        val msg = ChatMessage(role = MessageRole.USER, content = "test")
        assertTrue(msg.id.isNotBlank())
        assertEquals(36, msg.id.length)
    }

    @Test
    fun `ChatMessage default timestamp is recent`() {
        val before = System.currentTimeMillis()
        val msg = ChatMessage(role = MessageRole.ASSISTANT, content = "response")
        val after = System.currentTimeMillis()
        assertTrue(msg.timestamp in before..after)
    }

    @Test
    fun `ChatMessage is immutable data class`() {
        val msg1 = ChatMessage(id = "1", role = MessageRole.USER, content = "Hello", timestamp = 1000L)
        val msg2 = msg1.copy(content = "World")
        assertEquals("Hello", msg1.content)
        assertEquals("World", msg2.content)
        assertEquals(msg1.id, msg2.id)
    }

    @Test
    fun `two ChatMessages with same fields are equal`() {
        val msg1 = ChatMessage(id = "1", role = MessageRole.USER, content = "Hello", timestamp = 1000L)
        val msg2 = ChatMessage(id = "1", role = MessageRole.USER, content = "Hello", timestamp = 1000L)
        assertEquals(msg1, msg2)
    }

    @Test
    fun `ChatMessage supports all roles`() {
        val userMsg = ChatMessage(role = MessageRole.USER, content = "q")
        val assistantMsg = ChatMessage(role = MessageRole.ASSISTANT, content = "a")
        val systemMsg = ChatMessage(role = MessageRole.SYSTEM, content = "s")
        assertEquals(MessageRole.USER, userMsg.role)
        assertEquals(MessageRole.ASSISTANT, assistantMsg.role)
        assertEquals(MessageRole.SYSTEM, systemMsg.role)
    }
}
