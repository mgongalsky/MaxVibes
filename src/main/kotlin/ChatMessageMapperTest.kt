package com.maxvibes.adapter.llm.dto

import com.maxvibes.application.port.output.ChatRole
import com.maxvibes.domain.model.chat.ChatMessage
import com.maxvibes.domain.model.chat.MessageRole
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ChatMessageMapperTest {

    @Test
    fun `toChatMessageDTO maps USER role correctly`() {
        val message = ChatMessage(role = MessageRole.USER, content = "Hello")
        val dto = message.toChatMessageDTO()
        assertEquals(ChatRole.USER, dto.role)
        assertEquals("Hello", dto.content)
    }

    @Test
    fun `toChatMessageDTO maps ASSISTANT role correctly`() {
        val message = ChatMessage(role = MessageRole.ASSISTANT, content = "Hi")
        val dto = message.toChatMessageDTO()
        assertEquals(ChatRole.ASSISTANT, dto.role)
    }

    @Test
    fun `toChatMessageDTO maps SYSTEM role correctly`() {
        val message = ChatMessage(role = MessageRole.SYSTEM, content = "System prompt")
        val dto = message.toChatMessageDTO()
        assertEquals(ChatRole.SYSTEM, dto.role)
    }

    @Test
    fun `toChatMessageDTOs converts list correctly`() {
        val messages = listOf(
            ChatMessage(role = MessageRole.USER, content = "Q"),
            ChatMessage(role = MessageRole.ASSISTANT, content = "A")
        )
        val dtos = messages.toChatMessageDTOs()
        assertEquals(2, dtos.size)
        assertEquals(ChatRole.USER, dtos[0].role)
        assertEquals(ChatRole.ASSISTANT, dtos[1].role)
    }

    @Test
    fun `toChatMessageDTOs returns empty list for empty input`() {
        val dtos = emptyList<ChatMessage>().toChatMessageDTOs()
        assertTrue(dtos.isEmpty())
    }

    @Test
    fun `toChatMessageDTO preserves content exactly`() {
        val content = "Multi\nline\ncontent with special chars: <>\"'"
        val message = ChatMessage(role = MessageRole.USER, content = content)
        val dto = message.toChatMessageDTO()
        assertEquals(content, dto.content)
    }
}
