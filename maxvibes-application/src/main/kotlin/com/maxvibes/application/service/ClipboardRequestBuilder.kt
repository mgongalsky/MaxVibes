package com.maxvibes.application.service

import com.maxvibes.application.port.output.ChatRole
import com.maxvibes.domain.model.interaction.ClipboardHistoryEntry
import com.maxvibes.domain.model.interaction.ClipboardPhase
import com.maxvibes.domain.model.interaction.ClipboardRequest

/**
 * Pure builder for [ClipboardRequest].
 *
 * Contains the complete token-saving policy and field-population logic.
 * Zero I/O, zero IntelliJ SDK dependencies — directly unit-testable via Gradle.
 *
 * Single source of truth for how a [ClipboardRequest] is assembled;
 * both the Generate and Copy JSON flows delegate here via
 * [ClipboardInteractionService.generateAndCopyJson].
 */
internal object ClipboardRequestBuilder {

    fun build(
        state: ClipboardSessionState,
        freshFiles: Map<String, String>,
        isFirstMessage: Boolean,
        addHistory: Boolean = false,
        planOnlySuffix: String = "",
        ideErrors: String? = null,
        attachedContext: String? = null
    ): ClipboardRequest {
        // Minimal-mode: LLM already has full context in its chat window — send only the delta.
        val isMinimal = !isFirstMessage && !addHistory

        // Previously gathered paths — included only when replaying full history.
        val previousPaths: List<String> =
            if (addHistory) state.allGatheredFiles.keys.toList() else emptyList()

        // In minimal mode carry only the latest message (any role) to save tokens.
        // Using lastOrNull without role filter ensures that an LLM file-request reply
        // (role=ASSISTANT) is forwarded as current_message rather than the older USER task.
        val taskContent = if (isMinimal) {
            state.dialogHistory.lastOrNull()?.content
                ?: state.currentMessage
        } else {
            state.currentMessage
        }

        // System prompt: omitted in minimal mode — JsonClipboardProtocolCodec skips blank strings.
        val systemInstruction = if (isMinimal) "" else buildSystemInstruction(state, planOnlySuffix)

        return ClipboardRequest(
            // Phase is PLANNING only when neither session nor turn has gathered any files yet.
            phase = if (state.allGatheredFiles.isEmpty() && freshFiles.isEmpty())
                ClipboardPhase.PLANNING else ClipboardPhase.CHAT,
            currentMessage = taskContent,
            projectName = state.projectContext.name,
            systemInstruction = systemInstruction,
            fileTree = if (isMinimal) "" else state.projectContext.fileTree.toCompactString(maxDepth = 4),
            freshFiles = freshFiles,
            previouslyGatheredPaths = previousPaths,
            // Chat history: fully serialized in full mode; empty in minimal mode.
            chatHistory = if (isMinimal) emptyList() else state.dialogHistory.map { msg ->
                ClipboardHistoryEntry(
                    role = when (msg.role) {
                        ChatRole.USER -> "user"
                        ChatRole.ASSISTANT -> "assistant"
                        ChatRole.SYSTEM -> "system"
                    },
                    content = msg.content
                )
            },
            // attachedContext: one-shot per-message context — NOT stored in session state.
            // Passed directly from the UI layer for the turn it was attached.
            attachedContext = if (isMinimal) null else attachedContext,
            // ideErrors: one-shot per-message diagnostics — NOT stored in session state.
            // Always forwarded when provided; ignored in subsequent turns where null is passed.
            ideErrors = ideErrors,
            planOnly = if (isMinimal) false else state.planOnly
        )
    }

    // ── Private helpers ───────────────────────────────────────────────

    /**
     * Resolves the system instruction string for this turn.
     *
     * Uses the planning system prompt when no files have been gathered yet (first turn),
     * and the chat system prompt thereafter — optionally appending a plan-only suffix.
     */
    private fun buildSystemInstruction(state: ClipboardSessionState, planOnlySuffix: String): String {
        return if (state.allGatheredFiles.isEmpty()) {
            // First turn — no file context yet, use the planning prompt.
            state.prompts.planningSystem
        } else {
            // Subsequent turns — use chat prompt, optionally extended for plan-only mode.
            buildString {
                append(state.prompts.chatSystem)
                if (state.planOnly && planOnlySuffix.isNotBlank()) append(planOnlySuffix)
            }
        }
    }
}
