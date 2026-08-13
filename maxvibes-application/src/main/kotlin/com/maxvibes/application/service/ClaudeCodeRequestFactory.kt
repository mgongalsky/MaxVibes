package com.maxvibes.application.service

import com.maxvibes.domain.model.interaction.AttachedImage
import com.maxvibes.domain.model.interaction.ClipboardRequest
import com.maxvibes.domain.model.planning.TaskPlan
import com.maxvibes.domain.model.chat.CodingAgentProvider

/**
 * Builds transport requests for the Claude Code backend.
 *
 * Encodes the context policy: [create]'s `fullContext` drives both `isFirstMessage`
 * and `addHistory` of [InteractionRequestBuilder] — a request can never be built with
 * these flags out of sync.
 */
internal object ClaudeCodeRequestFactory {

    // Duplicated from ClipboardInteractionService by design (see Step 5 "Что НЕ делать").
    // Will be unified in a future refactor once both services stabilise.
    private val PLAN_ONLY_SUFFIX = "\n\n" +
            "## PLAN-ONLY MODE — DISCUSSION REQUIRED\n\n" +
            "DO NOT generate any code changes in the modifications array.\n" +
            "Keep modifications and commands empty.\n" +
            "Your goal is to DISCUSS the plan with the user before any code is written.\n\n" +
            "Instead of code, you must:\n" +
            "1. Briefly explain what you understand from the task\n" +
            "2. List which files you plan to touch and what changes you'll make in each\n" +
            "3. Mention any architectural decisions or trade-offs\n" +
            "4. Ask the user to confirm or suggest corrections\n\n" +
            "Always output the JSON with empty modifications and put your discussion in message."

    fun create(
        provider: CodingAgentProvider,
        state: ClipboardSessionState,
        freshFiles: Map<String, String>,
        fullContext: Boolean,
        attachedContext: String?,
        ideErrors: String?,
        specificPromptContent: String?,
        commandResults: String? = null,
        attachedImages: List<AttachedImage> = emptyList(),
        currentPlan: TaskPlan? = null
    ): ClipboardRequest {
        val policy = CodingAgentProviderPolicy.forProvider(provider)
        return InteractionRequestBuilder.build(
            state = state,
            freshFiles = freshFiles,
            isFirstMessage = fullContext,
            addHistory = fullContext,
            planOnlySuffix = PLAN_ONLY_SUFFIX,
            ideErrors = ideErrors,
            attachedContext = attachedContext,
            specificPromptContent = specificPromptContent,
            omitSystemInstruction = policy.omitSystemInstructionFromRequest,
            commandResults = commandResults,
            attachedImages = attachedImages,
            currentPlan = currentPlan
        )
    }
}
