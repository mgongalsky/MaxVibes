package com.maxvibes.application.service

import com.maxvibes.domain.model.check.CheckRequest
import com.maxvibes.domain.model.code.RequestedViewInfo
import com.maxvibes.domain.model.command.CommandRequest
import com.maxvibes.domain.model.interaction.InteractionModification
import com.maxvibes.domain.model.interaction.InteractionQuestion
import com.maxvibes.domain.model.modification.ModificationResult
import com.maxvibes.domain.model.planning.PlanDiagram
import com.maxvibes.domain.model.turn.TurnIntent

sealed class ClaudeCodeStepResult {

    data class WaitingForApprove(
        val assistantMessage: String,
        val requestedViews: List<RequestedViewInfo>,
        val inputTokens: Int = 0,
        val outputTokens: Int = 0,
        val llmReasoning: String? = null,
        val durationMs: Long = 0L,
        val skippedCommands: Int = 0,
        val costUsd: Double? = null,
        val numTurns: Int? = null,
        val diagram: PlanDiagram? = null
    ) : ClaudeCodeStepResult()

    data class AwaitingModApprove(
        val assistantMessage: String,
        val proposedModifications: List<InteractionModification>,
        val heldCommands: Int = 0,
        val skippedViews: Int = 0,
        val inputTokens: Int = 0,
        val outputTokens: Int = 0,
        val llmReasoning: String? = null,
        val durationMs: Long = 0L,
        val costUsd: Double? = null,
        val numTurns: Int? = null,
        val diagram: PlanDiagram? = null
    ) : ClaudeCodeStepResult()

    data class AwaitingQuestions(
        val assistantMessage: String,
        val questions: List<InteractionQuestion>,
        val inputTokens: Int = 0,
        val outputTokens: Int = 0,
        val llmReasoning: String? = null,
        val durationMs: Long = 0L,
        val costUsd: Double? = null,
        val numTurns: Int? = null,
        val diagram: PlanDiagram? = null
    ) : ClaudeCodeStepResult()

    data class Completed(
        val message: String,
        val modifications: List<ModificationResult>,
        val success: Boolean,
        val inputTokens: Int = 0,
        val outputTokens: Int = 0,
        val llmReasoning: String? = null,
        val commitMessage: String? = null,
        val durationMs: Long = 0L,
        val commands: List<CommandRequest> = emptyList(),
        /** Проверки средствами IDE — сборка и тесты. Отдельно от [commands]: другой канал, другое разрешение. */
        val checks: List<CheckRequest> = emptyList(),
        val costUsd: Double? = null,
        val numTurns: Int? = null,
        val diagram: PlanDiagram? = null,
        /**
         * Сказал ли агент, что ещё не закончил. Null — не сказал ничего, и это
         * трактуется как «закончил»: безостановочный ход включается только явно.
         */
        val turnIntent: TurnIntent? = null
    ) : ClaudeCodeStepResult()

    data class Error(val message: String) : ClaudeCodeStepResult()

    data class TransportError(val detail: String) : ClaudeCodeStepResult()
}

/**
 * Provider-neutral type name for application-level coding-agent results.
 *
 * Nested variants are still physically owned by [ClaudeCodeStepResult] during
 * the incremental migration. Use this alias in signatures only; construct and
 * match variants through `ClaudeCodeStepResult.*` until all consumers are
 * migrated together.
 */
typealias CodingAgentStepResult = ClaudeCodeStepResult
