package com.maxvibes.application.service

import com.maxvibes.application.port.output.ChatMessageDTO
import com.maxvibes.application.port.output.ChatRole
import com.maxvibes.application.port.output.ChatSessionRepository
import com.maxvibes.application.port.output.ClaudeCodeSessionLogPort
import com.maxvibes.application.port.output.LoggerPort
import com.maxvibes.domain.model.chat.MessageRole
import com.maxvibes.domain.model.code.CodeViewRequest
import com.maxvibes.domain.model.code.RequestedViewInfo

internal class CodingAgentResponseHandler(
    private val chatSessionRepository: ChatSessionRepository,
    private val sessionManager: ClipboardSessionManager,
    private val pendingStore: PendingModificationsStore,
    private val sessionLog: ClaudeCodeSessionLogPort? = null,
    private val logger: LoggerPort? = null
) {
    fun handle(
        sessionId: String,
        turn: ReceivedCodingAgentTurn,
        state: ClipboardSessionState
    ): ClaudeCodeStepResult {
        val response = turn.response
        log(
            "Processing response: hasViews=${response.codeViewRequests.isNotEmpty()}, " +
                    "hasMods=${response.modifications.isNotEmpty()}, hasQuestions=${response.questions.isNotEmpty()}, " +
                    "commands=${response.commands.size}, msg=${response.message.take(60)}"
        )
        sessionLog?.event(
            "response",
            mapOf(
                "hasViews" to response.codeViewRequests.isNotEmpty(),
                "hasMods" to response.modifications.isNotEmpty(),
                "questions" to response.questions.size,
                "commands" to response.commands.size,
                "msgLen" to response.message.length,
                "thinkingLen" to (turn.thinkingText?.length ?: 0)
            )
        )

        val outcome = CodingAgentResponseProcessor.process(
            response,
            CodingAgentResponseProcessor.Context(
                planOnly = state.planOnly,
                inputTokens = turn.inputTokens,
                outputTokens = turn.outputTokens,
                thinkingText = turn.thinkingText,
                durationMs = turn.durationMs,
                costUsd = turn.costUsd,
                numTurns = turn.numTurns
            )
        )

        outcome.intents.forEach { intent ->
            when (intent) {
                is CodingAgentResponseProcessor.Intent.SavePlan ->
                    chatSessionRepository.getSessionById(sessionId)?.let { current ->
                        chatSessionRepository.saveSession(current.withPlan(intent.plan))
                        log(
                            if (intent.plan != null) {
                                "Plan updated: ${intent.plan.steps.size} step(s), done=${intent.plan.doneCount}"
                            } else {
                                "Plan cleared"
                            }
                        )
                        sessionLog?.event(
                            "plan updated",
                            mapOf("steps" to (intent.plan?.steps?.size ?: 0))
                        )
                    }

                is CodingAgentResponseProcessor.Intent.AppendAssistantHistory ->
                    state.dialogHistory.add(
                        ChatMessageDTO(
                            role = ChatRole.ASSISTANT,
                            content = intent.message
                        )
                    )

                is CodingAgentResponseProcessor.Intent.PersistRequestedViews ->
                    persistRequestedViews(sessionId, intent.views)

                is CodingAgentResponseProcessor.Intent.Transition ->
                    sessionManager.transition(
                        sessionId,
                        ClipboardEvent.ResponseReceived(
                            hasRequestedViews = intent.hasRequestedViews
                        )
                    )

                is CodingAgentResponseProcessor.Intent.HoldPending -> {
                    pendingStore.hold(
                        sessionId = sessionId,
                        modifications = intent.modifications,
                        commands = intent.commands,
                        commitMessage = intent.commitMessage
                    )
                    log(
                        "Holding ${intent.modifications.size} modification(s) and " +
                                "${intent.commands.size} command(s) for user approval"
                    )
                    sessionLog?.event(
                        "modifications held for approval",
                        mapOf(
                            "mods" to intent.modifications.size,
                            "commands" to intent.commands.size
                        )
                    )
                }
            }
        }

        when (val result = outcome.result) {
            is ClaudeCodeStepResult.AwaitingModApprove ->
                if (result.skippedViews > 0) {
                    log(
                        "WARN: response mixed modifications with ${result.skippedViews} " +
                                "view request(s) — views skipped per protocol"
                    )
                    sessionLog?.event(
                        "views skipped (mixed with modifications)",
                        mapOf("count" to result.skippedViews)
                    )
                }

            is ClaudeCodeStepResult.WaitingForApprove ->
                if (result.skippedCommands > 0) {
                    log(
                        "WARN: response mixed requestedViews with ${result.skippedCommands} " +
                                "command(s) — commands skipped per protocol"
                    )
                    sessionLog?.event(
                        "commands skipped (mixed with requestedViews)",
                        mapOf("count" to result.skippedCommands)
                    )
                }

            is ClaudeCodeStepResult.AwaitingQuestions -> {
                log("LLM asked ${result.questions.size} question(s) - awaiting user answer")
                sessionLog?.event(
                    "questions received",
                    mapOf("count" to result.questions.size)
                )
            }

            else -> Unit
        }

        return outcome.result
    }

    private fun persistRequestedViews(
        sessionId: String,
        requests: List<CodeViewRequest>
    ) {
        val session = chatSessionRepository.getSessionById(sessionId) ?: return
        val messages = session.messages.toMutableList()
        val lastAssistantIndex = messages.indexOfLast {
            it.role == MessageRole.ASSISTANT
        }
        if (lastAssistantIndex < 0) return

        val requestedViews = requests.map { request ->
            RequestedViewInfo(
                path = request.filePath,
                granularity = request.granularity,
                elementPath = request.elementPath
            )
        }
        messages[lastAssistantIndex] = messages[lastAssistantIndex].copy(
            requestedViews = requestedViews
        )
        chatSessionRepository.saveSession(
            session.copy(messages = messages)
        )
        log(
            "Persisted ${requestedViews.size} requestedViews into domain message " +
                    "for session $sessionId"
        )
    }

    private fun log(message: String) {
        println("[MaxVibes ClaudeCode] $message")
        logger?.info("ClaudeCode", message)
    }
}
