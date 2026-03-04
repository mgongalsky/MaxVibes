package com.maxvibes.domain.model.chat

import java.time.Instant
import java.util.UUID

data class ChatSession(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "New Chat",
    val parentId: String? = null,
    val depth: Int = 0,
    val messages: List<ChatMessage> = emptyList(),
    val tokenUsage: TokenUsage = TokenUsage.EMPTY,
    val createdAt: Long = Instant.now().toEpochMilli(),
    val updatedAt: Long = Instant.now().toEpochMilli()
) {
    val isRoot: Boolean get() = parentId == null

    fun withMessage(message: ChatMessage): ChatSession {
        val newTitle = if (title == "New Chat" && message.role == MessageRole.USER)
            message.content.take(40) + if (message.content.length > 40) "..." else ""
        else title
        return copy(
            messages = messages + message,
            title = newTitle,
            updatedAt = Instant.now().toEpochMilli()
        )
    }

    fun withTitle(newTitle: String): ChatSession =
        copy(title = newTitle.trim().ifBlank { "Untitled" }, updatedAt = Instant.now().toEpochMilli())

    fun withDepth(newDepth: Int): ChatSession = copy(depth = newDepth)

    fun withParent(newParentId: String?, newDepth: Int): ChatSession =
        copy(parentId = newParentId, depth = newDepth, updatedAt = Instant.now().toEpochMilli())

    fun addPlanningTokens(input: Int, output: Int): ChatSession =
        copy(tokenUsage = tokenUsage.addPlanning(input, output))

    fun addChatTokens(input: Int, output: Int): ChatSession =
        copy(tokenUsage = tokenUsage.addChat(input, output))

    fun touch(): ChatSession = copy(updatedAt = Instant.now().toEpochMilli())

    fun cleared(): ChatSession = copy(messages = emptyList(), updatedAt = Instant.now().toEpochMilli())
}
