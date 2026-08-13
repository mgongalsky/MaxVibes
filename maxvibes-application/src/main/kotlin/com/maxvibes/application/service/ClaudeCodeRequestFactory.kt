package com.maxvibes.application.service

import com.maxvibes.domain.model.chat.CodingAgentProvider
import com.maxvibes.domain.model.interaction.AttachedImage
import com.maxvibes.domain.model.interaction.ClipboardRequest
import com.maxvibes.domain.model.planning.TaskPlan

/** Builds provider-neutral MaxVibes protocol requests for coding-agent transports. */
internal object CodingAgentRequestFactory {
    private val PLAN_ONLY_SUFFIX = buildString {
        appendLine()
        appendLine()
        appendLine("## PLAN-ONLY MODE — DISCUSSION REQUIRED")
        appendLine()
        appendLine("DO NOT generate any code changes in the modifications array.")
        appendLine("Keep modifications and commands empty.")
        appendLine("Your goal is to DISCUSS the plan with the user before any code is written.")
        appendLine()
        appendLine("Instead of code, you must:")
        appendLine("1. Briefly explain what you understand from the task")
        appendLine("2. List which files you plan to touch and what changes you'll make in each")
        appendLine("3. Mention any architectural decisions or trade-offs")
        appendLine("4. Ask the user to confirm or suggest corrections")
        appendLine()
        append("Always output the JSON with empty modifications and put your discussion in message.")
    }

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

/** Compatibility wrapper for the pre-generalization application name. */
internal object ClaudeCodeRequestFactory {
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
    ): ClipboardRequest = CodingAgentRequestFactory.create(
        provider = provider,
        state = state,
        freshFiles = freshFiles,
        fullContext = fullContext,
        attachedContext = attachedContext,
        ideErrors = ideErrors,
        specificPromptContent = specificPromptContent,
        commandResults = commandResults,
        attachedImages = attachedImages,
        currentPlan = currentPlan
    )
}
