package com.maxvibes.application.service

import com.maxvibes.application.port.output.ChatMessageDTO
import com.maxvibes.application.port.output.PromptTemplates
import com.maxvibes.domain.model.context.ProjectContext

internal data class ClipboardSessionState(
    /** The user message that started or is continuing the session. */
    val currentMessage: String,
    /** Snapshot of the project taken when the session started. */
    val projectContext: ProjectContext,
    /** Full dialog history for this session (mutated in place). */
    val dialogHistory: MutableList<ChatMessageDTO>,
    /** System prompts resolved at session start. */
    val prompts: PromptTemplates,
    /** All file contents gathered so far, keyed by path. */
    val allGatheredFiles: MutableMap<String, String>,
    /** Estimated input tokens for the last request — used for token display. */
    var lastInputTokens: Int = 0,
    /**
     * When true, LLM is instructed to plan only without generating code changes.
     * Persisted in session state because plan-only is a dialog-level mode, not per-message.
     */
    val planOnly: Boolean = false
)
