package com.maxvibes.domain.model.chat

import java.time.Instant
import java.util.UUID

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: MessageRole,
    val content: String,
    val timestamp: Long = Instant.now().toEpochMilli()
)
