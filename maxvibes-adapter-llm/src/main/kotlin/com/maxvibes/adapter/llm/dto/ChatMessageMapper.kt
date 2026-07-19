package com.maxvibes.adapter.llm.dto

import com.maxvibes.application.port.output.ChatMessageDTO
import com.maxvibes.application.port.output.ChatRole
import com.maxvibes.domain.model.chat.ChatMessage
import com.maxvibes.domain.model.chat.MessageRole

/**
 * Маппинг между доменным ChatMessage и ChatMessageDTO для LLM адаптера.
 * Единственное место, где живёт эта конвертация.
 */
fun ChatMessage.toChatMessageDTO(): ChatMessageDTO = ChatMessageDTO(
    role = when (role) {
        MessageRole.USER -> ChatRole.USER
        MessageRole.ASSISTANT -> ChatRole.ASSISTANT
        MessageRole.SYSTEM -> ChatRole.SYSTEM
    },
    content = content
)

fun List<ChatMessage>.toChatMessageDTOs(): List<ChatMessageDTO> =
    map { it.toChatMessageDTO() }
