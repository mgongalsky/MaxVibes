package com.maxvibes.domain.model.chat

import com.maxvibes.domain.model.interaction.ClipboardSessionStatus
import java.time.Instant
import java.util.UUID

/**
 * Immutable domain model representing a single chat session (branch in the session tree).
 *
 * A session holds the full message history, token usage statistics, tree position metadata,
 * and — since the per-session clipboard state refactor — the current [clipboardStatus].
 *
 * All mutation returns a new copy; the original object is never modified.
 *
 * @param id Unique session identifier (UUID)..
 * @param title Human-readable session title, auto-derived from the first user message.
 * @param parentId ID of the parent session, or null if this is a root session.
 * @param depth Depth in the session tree (0 = root).
 * @param messages Ordered list of messages in this session.
 * @param tokenUsage Accumulated token counters (planning + chat).
 * @param createdAt Creation timestamp (epoch millis).
 * @param updatedAt Last modification timestamp (epoch millis).
 * @param clipboardStatus Current state of the clipboard dialog for this session.
 */
data class ChatSession(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "New Chat",
    val parentId: String? = null,
    val depth: Int = 0,
    val messages: List<ChatMessage> = emptyList(),
    val tokenUsage: TokenUsage = TokenUsage.EMPTY,
    val createdAt: Long = Instant.now().toEpochMilli(),
    val updatedAt: Long = Instant.now().toEpochMilli(),
    // Last field — default value ensures backward compatibility with all existing call sites.
    val clipboardStatus: ClipboardSessionStatus = ClipboardSessionStatus.IDLE
) {
    /** True if this session has no parent (i.e., it is a root-level session). */
    val isRoot: Boolean get() = parentId == null

    /**
     * Returns a new session with [message] appended to [messages].
     * If the session title is still the default "New Chat" and the message is from the user,
     * the title is automatically derived from the first 40 characters of the message content.
     */
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

    /**
     * Returns a new session with the title set to [newTitle] (trimmed; "Untitled" if blank).
     */
    fun withTitle(newTitle: String): ChatSession =
        copy(title = newTitle.trim().ifBlank { "Untitled" }, updatedAt = Instant.now().toEpochMilli())

    /** Returns a new session with [depth] updated (no timestamp change — tree restructure only). */
    fun withDepth(newDepth: Int): ChatSession = copy(depth = newDepth)

    /**
     * Returns a new session re-attached to a different parent node.
     * Updates [updatedAt] to reflect the structural change.
     */
    fun withParent(newParentId: String?, newDepth: Int): ChatSession =
        copy(parentId = newParentId, depth = newDepth, updatedAt = Instant.now().toEpochMilli())

    /** Returns a new session with planning token counters incremented by [input] and [output]. */
    fun addPlanningTokens(input: Int, output: Int): ChatSession =
        copy(tokenUsage = tokenUsage.addPlanning(input, output))

    /** Returns a new session with chat token counters incremented by [input] and [output]. */
    fun addChatTokens(input: Int, output: Int): ChatSession =
        copy(tokenUsage = tokenUsage.addChat(input, output))

    /** Returns a new session with [updatedAt] refreshed to the current time. */
    fun touch(): ChatSession = copy(updatedAt = Instant.now().toEpochMilli())

    /** Returns a new session with all messages removed and [updatedAt] refreshed. */
    fun cleared(): ChatSession = copy(messages = emptyList(), updatedAt = Instant.now().toEpochMilli())

    /**
     * Returns a new session with [clipboardStatus] set to [status] and [updatedAt] refreshed.
     *
     * Use this instead of raw [copy] to ensure the timestamp is always kept in sync
     * with clipboard state transitions.
     *
     * @param status The new clipboard dialog status for this session.
     */
    fun withClipboardStatus(status: ClipboardSessionStatus): ChatSession =
        copy(clipboardStatus = status, updatedAt = Instant.now().toEpochMilli())
}
