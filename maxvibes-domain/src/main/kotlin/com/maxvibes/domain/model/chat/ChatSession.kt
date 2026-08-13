package com.maxvibes.domain.model.chat

import com.maxvibes.domain.model.interaction.ClipboardSessionStatus
import com.maxvibes.domain.model.planning.TaskPlan
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
    val updatedAt: Long = Instant.now().toEpochMilli(),
    val clipboardStatus: ClipboardSessionStatus = ClipboardSessionStatus.IDLE,
    val selectedSpecificPromptName: String? = null,
    /**
     * Provider-aware remote coding-agent session metadata.
     *
     * Null for sessions that have not started a coding-agent exchange yet.
     * During migration, legacy Claude-specific fields below remain available
     * so existing XML sessions and runtime code continue to work unchanged.
     */
    val codingAgentSession: CodingAgentSessionRef? = null,
    /**
     * Legacy Claude Code session id returned by the CLI's first system event.
     * Kept temporarily for backward compatibility while persistence/runtime
     * migrate to [codingAgentSession].
     */
    val claudeCodeSessionId: String? = null,
    /**
     * Legacy Claude Code full-context flag.
     * Kept temporarily for backward compatibility while persistence/runtime
     * migrate to [codingAgentSession].
     */
    val claudeCodeNeedsFullContext: Boolean = true,
    /**
     * Task plan maintained by the LLM for this session (planner panel).
     * Snapshot-based: each `plan` field in an LLM response replaces it entirely.
     * Null when no plan is active.
     */
    val plan: TaskPlan? = null
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
     */
    fun withClipboardStatus(status: ClipboardSessionStatus): ChatSession =
        copy(clipboardStatus = status, updatedAt = Instant.now().toEpochMilli())

    /**
     * Returns a new session with [selectedSpecificPromptName] updated and [updatedAt] refreshed.
     */
    fun withSelectedPrompt(name: String?): ChatSession =
        copy(selectedSpecificPromptName = name, updatedAt = Instant.now().toEpochMilli())

    /**
     * Returns a new session with [plan] replaced (null clears the plan) and [updatedAt] refreshed.
     */
    fun withPlan(plan: TaskPlan?): ChatSession =
        copy(plan = plan, updatedAt = Instant.now().toEpochMilli())
    fun resolvedCodingAgentSession(provider: CodingAgentProvider): CodingAgentSessionRef? {
        codingAgentSession?.let { current ->
            return current.takeIf { it.provider == provider }
        }

        if (provider != CodingAgentProvider.CLAUDE_CODE) return null
        if (claudeCodeSessionId == null && claudeCodeNeedsFullContext) return null

        return CodingAgentSessionRef(
            provider = CodingAgentProvider.CLAUDE_CODE,
            remoteSessionId = claudeCodeSessionId,
            needsFullContext = claudeCodeNeedsFullContext
        )
    }
    fun withCodingAgentSession(sessionRef: CodingAgentSessionRef): ChatSession =
        if (sessionRef.provider == CodingAgentProvider.CLAUDE_CODE) {
            copy(
                codingAgentSession = sessionRef,
                claudeCodeSessionId = sessionRef.remoteSessionId,
                claudeCodeNeedsFullContext = sessionRef.needsFullContext
            )
        } else {
            copy(codingAgentSession = sessionRef)
        }
}
