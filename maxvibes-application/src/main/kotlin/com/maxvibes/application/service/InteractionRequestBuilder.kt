package com.maxvibes.application.service

import com.maxvibes.application.port.output.ChatRole
import com.maxvibes.domain.model.interaction.InteractionHistoryEntry
import com.maxvibes.domain.model.interaction.InteractionPhase
import com.maxvibes.domain.model.interaction.ClipboardRequest

/**
 * Pure builder for [ClipboardRequest].
 *
 * Contains the complete token-saving policy and field-population logic.
 * Zero I/O, zero IntelliJ SDK dependencies — directly unit-testable via Gradle.
 *
 * Single source of truth for how a [ClipboardRequest] is assembled;
 * both the Generate and Copy JSON flows delegate here via
 * [ClipboardInteractionService.generateAndCopyJson], and the Claude Code
 * flow via [ClaudeCodeInteractionService.doSend].
 */
internal object InteractionRequestBuilder {

    /**
     * @param omitSystemInstruction when true, [ClipboardRequest.systemInstruction]
     *        is forced to an empty string regardless of [state]. Used by the
     *        Claude Code transport, which delivers the system instruction through
     *        the CLI's `--append-system-prompt` flag rather than embedding it in
     *        every user-event JSON payload.
     * @param commandResults formatted outcomes of the previous turn's shell commands
     *        (execution output or user declines). One-shot per-message context like
     *        [ideErrors] — always forwarded when provided, never stored in state.
     */
    fun build(
        state: ClipboardSessionState,
        freshFiles: Map<String, String>,
        isFirstMessage: Boolean,
        addHistory: Boolean = false,
        planOnlySuffix: String = "",
        ideErrors: String? = null,
        attachedContext: String? = null,
        specificPromptContent: String? = null,
        omitSystemInstruction: Boolean = false,
        commandResults: String? = null
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

        // System prompt resolution:
        //  - omitSystemInstruction wins unconditionally (Claude Code transport).
        //  - else: omitted in minimal mode (LLM already has it in its chat window).
        //  - else: full system instruction with template variables substituted.
        val systemInstruction = when {
            omitSystemInstruction -> ""
            isMinimal -> ""
            else -> applyPromptVariables(buildSystemInstruction(state, planOnlySuffix), state)
        }

        return ClipboardRequest(
            // Phase is PLANNING only when neither session nor turn has gathered any files yet.
            phase = if (state.allGatheredFiles.isEmpty() && freshFiles.isEmpty())
                InteractionPhase.PLANNING else InteractionPhase.CHAT,
            currentMessage = taskContent,
            projectName = state.projectContext.name,
            systemInstruction = systemInstruction,
            fileTree = if (isMinimal) "" else state.projectContext.fileTree.toCompactString(maxDepth = 4),
            freshFiles = freshFiles,
            previouslyGatheredPaths = previousPaths,
            // Chat history: fully serialized in full mode; empty in minimal mode.
            chatHistory = if (isMinimal) emptyList() else state.dialogHistory.map { msg ->
                InteractionHistoryEntry(
                    role = when (msg.role) {
                        ChatRole.USER -> "user"
                        ChatRole.ASSISTANT -> "assistant"
                        ChatRole.SYSTEM -> "system"
                    },
                    content = msg.content
                )
            },
            // attachedContext: one-shot per-message context — NOT stored in session state.
            attachedContext = if (isMinimal) null else attachedContext,
            // ideErrors: one-shot per-message diagnostics — always forwarded when provided.
            ideErrors = ideErrors,
            // commandResults: one-shot outcomes of the previous turn's commands —
            // always forwarded, even in minimal mode (they ARE the payload of this turn).
            commandResults = commandResults,
            planOnly = if (isMinimal) false else state.planOnly,
            // specificPromptContent is passed through unconditionally — even in minimal mode.
            specificPrompt = specificPromptContent
        )
    }

    // ── Private helpers ───────────────────────────────────────────────

    /**
     * Resolves the system instruction string for this turn.
     */
    private fun buildSystemInstruction(state: ClipboardSessionState, planOnlySuffix: String): String {
        return if (state.allGatheredFiles.isEmpty()) {
            state.prompts.planningSystem
        } else {
            buildString {
                append(state.prompts.chatSystem)
                if (state.planOnly && planOnlySuffix.isNotBlank()) append(planOnlySuffix)
            }
        }
    }

    /**
     * Substitutes {{template}} variables in the system prompt.
     * Mirrors LangChainLLMService.applyPromptVariables for the clipboard path —
     * previously the clipboard mode sent prompts with raw {{placeholders}}.
     * Pure JVM: OS detection via system property, no IntelliJ SDK.
     */
    private fun applyPromptVariables(template: String, state: ClipboardSessionState): String {
        val ctx = state.projectContext
        return template
            .replace("{{projectName}}", ctx.name)
            .replace("{{language}}", ctx.techStack.language)
            .replace("{{buildTool}}", ctx.techStack.buildTool ?: "unknown")
            .replace("{{frameworks}}", ctx.techStack.frameworks.joinToString(", ").ifEmpty { "none" })
            .replace("{{os}}", osDescriptor())
    }

    private fun osDescriptor(): String {
        val os = System.getProperty("os.name")?.lowercase() ?: ""
        return when {
            "windows" in os -> "Windows (PowerShell)"
            "mac" in os -> "macOS (sh)"
            else -> "Linux (sh)"
        }
    }
}