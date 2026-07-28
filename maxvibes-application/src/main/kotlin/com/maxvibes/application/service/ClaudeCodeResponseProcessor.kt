package com.maxvibes.application.service

import com.maxvibes.domain.model.code.CodeViewRequest
import com.maxvibes.domain.model.code.RequestedViewInfo
import com.maxvibes.domain.model.command.CommandRequest
import com.maxvibes.domain.model.interaction.InteractionModification
import com.maxvibes.domain.model.interaction.InteractionResponse
import com.maxvibes.domain.model.planning.TaskPlan

/**
 * Pure interpretation of an [InteractionResponse]: maps the response to a
 * [ClaudeCodeStepResult] plus an ordered list of side-effect [Intent]s.
 *
 * Contains ALL protocol rules of the Claude Code turn:
 *  - mixed-response precedence (modifications win over views; views win over commands;
 *    questions drop commands),
 *  - plan snapshot semantics (absent = unchanged, empty steps = clear),
 *  - reasoning combination (transport thinking + response reasoning),
 *  - blank-message fallback ("Done."),
 *  - plan-only mode (modifications are neither held nor applied).
 *
 * The caller (ClaudeCodeInteractionService) must execute [Outcome.intents]
 * strictly in list order — the order reproduces the side-effect order of the
 * pre-extraction implementation and is pinned by tests.
 */
object ClaudeCodeResponseProcessor {

    data class Context(
        val planOnly: Boolean = false,
        val inputTokens: Int = 0,
        val outputTokens: Int = 0,
        val thinkingText: String? = null,
        val durationMs: Long = 0L,
        val costUsd: Double? = null,
        val numTurns: Int? = null
    )

    /** A side effect the service must perform. Emitted in execution order. */
    sealed interface Intent {
        /** Replace the session plan; null clears it. Emitted only when the response carried a plan field. */
        data class SavePlan(val plan: TaskPlan?) : Intent

        /** Append the assistant message to the in-memory workspace history. */
        data class AppendAssistantHistory(val message: String) : Intent

        /** Persist typed view requests into the last ASSISTANT message of the domain session. */
        data class PersistRequestedViews(val views: List<CodeViewRequest>) : Intent

        /** Drive the session state machine with ResponseReceived. */
        data class Transition(val hasRequestedViews: Boolean) : Intent

        /** Hold proposed modifications (and commands arriving with them) until user approval. */
        data class HoldPending(
            val modifications: List<InteractionModification>,
            val commands: List<CommandRequest>,
            val commitMessage: String?
        ) : Intent
    }

    data class Outcome(val result: ClaudeCodeStepResult, val intents: List<Intent>)

    fun process(response: InteractionResponse, ctx: Context): Outcome {
        val hasViews = response.codeViewRequests.isNotEmpty()
        val hasMods = response.modifications.isNotEmpty()
        val hasQuestions = response.questions.isNotEmpty()
        val commands: List<CommandRequest> = response.commands.mapNotNull { ProtocolConverter.convertCommand(it) }
        val holdMods = hasMods && !ctx.planOnly

        val intents = mutableListOf<Intent>()

        response.plan?.let { snapshot ->
            intents += Intent.SavePlan(snapshot.takeIf { it.steps.isNotEmpty() })
        }
        if (response.message.isNotBlank()) {
            intents += Intent.AppendAssistantHistory(response.message)
        }
        if (hasViews && !holdMods) {
            intents += Intent.PersistRequestedViews(response.codeViewRequests)
        }
        intents += Intent.Transition(hasRequestedViews = hasViews || holdMods)

        val combinedReasoning = listOfNotNull(
            ctx.thinkingText?.takeIf { it.isNotBlank() },
            response.reasoning?.takeIf { it.isNotBlank() }
        ).joinToString("\n\n").takeIf { it.isNotBlank() }

        if (holdMods) {
            intents += Intent.HoldPending(
                modifications = response.modifications,
                commands = commands,
                commitMessage = response.commitMessage?.takeIf { it.isNotBlank() }
            )
            return Outcome(
                ClaudeCodeStepResult.AwaitingModApprove(
                    assistantMessage = response.message,
                    proposedModifications = response.modifications,
                    heldCommands = commands.size,
                    skippedViews = if (hasViews) response.codeViewRequests.size else 0,
                    inputTokens = ctx.inputTokens,
                    outputTokens = ctx.outputTokens,
                    llmReasoning = combinedReasoning,
                    durationMs = ctx.durationMs,
                    costUsd = ctx.costUsd,
                    numTurns = ctx.numTurns,
                    diagram = response.diagram
                ),
                intents
            )
        }

        if (hasViews) {
            val requestedViewInfos = response.codeViewRequests.map {
                RequestedViewInfo(
                    path = it.filePath,
                    granularity = it.granularity,
                    elementPath = it.elementPath
                )
            }
            return Outcome(
                ClaudeCodeStepResult.WaitingForApprove(
                    assistantMessage = response.message,
                    requestedViews = requestedViewInfos,
                    inputTokens = ctx.inputTokens,
                    outputTokens = ctx.outputTokens,
                    llmReasoning = combinedReasoning,
                    durationMs = ctx.durationMs,
                    skippedCommands = commands.size,
                    costUsd = ctx.costUsd,
                    numTurns = ctx.numTurns,
                    diagram = response.diagram
                ),
                intents
            )
        }

        if (hasQuestions) {
            return Outcome(
                ClaudeCodeStepResult.AwaitingQuestions(
                    assistantMessage = response.message,
                    questions = response.questions,
                    inputTokens = ctx.inputTokens,
                    outputTokens = ctx.outputTokens,
                    llmReasoning = combinedReasoning,
                    durationMs = ctx.durationMs,
                    costUsd = ctx.costUsd,
                    numTurns = ctx.numTurns,
                    diagram = response.diagram
                ),
                intents
            )
        }

        val messageText = buildString {
            if (response.message.isNotBlank()) append(response.message)
            if (isEmpty()) append("Done.")
        }
        return Outcome(
            ClaudeCodeStepResult.Completed(
                message = messageText.trim(),
                modifications = emptyList(),
                success = true,
                inputTokens = ctx.inputTokens,
                outputTokens = ctx.outputTokens,
                llmReasoning = combinedReasoning,
                commitMessage = response.commitMessage?.takeIf { it.isNotBlank() },
                durationMs = ctx.durationMs,
                commands = commands,
                costUsd = ctx.costUsd,
                numTurns = ctx.numTurns,
                diagram = response.diagram
            ),
            intents
        )
    }
}
