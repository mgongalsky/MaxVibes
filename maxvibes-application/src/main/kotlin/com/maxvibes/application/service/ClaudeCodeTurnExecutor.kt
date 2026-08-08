package com.maxvibes.application.service

import com.maxvibes.application.port.output.ChatSessionRepository
import com.maxvibes.application.port.output.ClaudeCodeError
import com.maxvibes.application.port.output.ClaudeCodeSessionLogPort
import com.maxvibes.application.port.output.LoggerPort
import com.maxvibes.application.port.output.NotificationPort
import com.maxvibes.shared.result.Result
import com.maxvibes.application.port.output.CodingAgentCliPort
import com.maxvibes.application.port.output.CodingAgentCliSendResult
import com.maxvibes.domain.model.chat.CodingAgentProvider
import com.maxvibes.domain.model.chat.CodingAgentSessionRef

internal class ClaudeCodeTurnExecutor(
    private val claudeCodePort: CodingAgentCliPort,
    private val chatSessionRepository: ChatSessionRepository,
    private val notificationPort: NotificationPort,
    private val sessionLog: ClaudeCodeSessionLogPort? = null,
    private val streamHub: AgentStreamHub? = null,
    private val logger: LoggerPort? = null
) {
    suspend fun execute(
        command: CodingAgentTurnCommand,
        state: ClipboardSessionState
    ): CodingAgentTurnExecutionResult {
        val sessionId = command.sessionId
        streamHub?.begin(sessionId)
        var session = chatSessionRepository.getSessionById(sessionId)
            ?: return CodingAgentTurnExecutionResult.Failure(
                ClaudeCodeStepResult.Error("Session not found: $sessionId")
            )

        var sessionRef = session.resolvedCodingAgentSession(CodingAgentProvider.CLAUDE_CODE)
        val needsFull = command.firstMessage || sessionRef?.needsFullContext != false
        var request = ClaudeCodeRequestFactory.create(
            state = state,
            freshFiles = command.freshFiles,
            fullContext = needsFull,
            attachedContext = command.attachedContext,
            ideErrors = command.ideErrors,
            specificPromptContent = command.specificPromptContent,
            commandResults = command.commandResults,
            attachedImages = command.attachedImages,
            currentPlan = session.plan
        )

        var ensureResult = claudeCodePort.ensureStarted(
            resumeSessionId = sessionRef?.remoteSessionId,
            systemPrompt = state.prompts.chatSystem
        )

        if (
            ensureResult is Result.Failure &&
            ensureResult.error is ClaudeCodeError.ResumeFailed
        ) {
            val resumeFailure = ensureResult.error as ClaudeCodeError.ResumeFailed
            log("Resume failed for sessionId=${resumeFailure.sessionId}; falling back to fresh start.")
            sessionLog?.event(
                "resume failed — falling back to fresh start",
                mapOf("claudeSessionId" to resumeFailure.sessionId)
            )

            sessionRef = CodingAgentSessionRef(
                provider = CodingAgentProvider.CLAUDE_CODE,
                remoteSessionId = null,
                needsFullContext = true
            )
            session = session.withCodingAgentSession(sessionRef)
            chatSessionRepository.saveSession(session)

            request = ClaudeCodeRequestFactory.create(
                state = state,
                freshFiles = command.freshFiles,
                fullContext = true,
                attachedContext = command.attachedContext,
                ideErrors = command.ideErrors,
                specificPromptContent = command.specificPromptContent,
                commandResults = command.commandResults,
                attachedImages = command.attachedImages,
                currentPlan = session.plan
            )
            ensureResult = claudeCodePort.ensureStarted(
                resumeSessionId = null,
                systemPrompt = state.prompts.chatSystem
            )
        }

        if (ensureResult is Result.Failure) {
            return CodingAgentTurnExecutionResult.Failure(
                ClaudeCodeStepResult.TransportError(
                    transportErrorMessage(ensureResult.error)
                )
            )
        }

        val estimatedInputTokens = TokenEstimator.estimateTokens(request)
        state.lastInputTokens = estimatedInputTokens

        log(
            "Sending: tokens≈$estimatedInputTokens, freshFiles=${command.freshFiles.size}, " +
                    "history=${request.chatHistory.size}, fullCtx=$needsFull"
        )
        sessionLog?.event(
            "sending request",
            mapOf(
                "tokensApprox" to estimatedInputTokens,
                "freshFiles" to command.freshFiles.size,
                "history" to request.chatHistory.size,
                "fullContext" to needsFull
            )
        )
        notificationPort.showProgress("Sending to Claude Code...", 0.5)

        val sendStartedAt = System.currentTimeMillis()
        val sendResult = claudeCodePort.send(request)
        val measuredDurationMs = System.currentTimeMillis() - sendStartedAt

        return when (sendResult) {
            is Result.Success -> {
                val payload: CodingAgentCliSendResult = sendResult.value
                persistObservedSession(payload, session)
                val stats = payload.stats

                CodingAgentTurnExecutionResult.Success(
                    ReceivedCodingAgentTurn(
                        response = payload.response,
                        inputTokens = stats?.inputTokens?.takeIf { it > 0 }
                            ?: estimatedInputTokens,
                        outputTokens = stats?.outputTokens?.takeIf { it > 0 }
                            ?: TokenEstimator.estimateOutputTokens(payload.response),
                        thinkingText = payload.thinkingText,
                        durationMs = stats?.durationMs?.takeIf { it > 0 }
                            ?: measuredDurationMs,
                        costUsd = stats?.costUsd?.takeIf { it > 0.0 },
                        numTurns = stats?.numTurns?.takeIf { it > 0 }
                    )
                )
            }

            is Result.Failure -> {
                log("Send failed: ${sendResult.error} (after ${measuredDurationMs}ms)")
                sessionLog?.event(
                    "send failed",
                    mapOf(
                        "error" to sendResult.error.toString(),
                        "elapsedMs" to measuredDurationMs
                    )
                )
                CodingAgentTurnExecutionResult.Failure(
                    ClaudeCodeStepResult.TransportError(
                        transportErrorMessage(sendResult.error)
                    )
                )
            }
        }
    }

    fun shutdown() {
        try {
            claudeCodePort.shutdown()
        } catch (exception: Exception) {
            log(
                "Warning: shutdown raised ${exception.javaClass.simpleName}: ${exception.message}"
            )
        }
    }

    private fun persistObservedSession(
        payload: CodingAgentCliSendResult,
        session: com.maxvibes.domain.model.chat.ChatSession
    ) {
        val current = session.resolvedCodingAgentSession(CodingAgentProvider.CLAUDE_CODE)
        val observedId = payload.observedSessionId
        if (observedId != null || current == null || current.needsFullContext) {
            chatSessionRepository.saveSession(
                session.withCodingAgentSession(
                    CodingAgentSessionRef(
                        provider = CodingAgentProvider.CLAUDE_CODE,
                        remoteSessionId = observedId ?: current?.remoteSessionId,
                        needsFullContext = false
                    )
                )
            )
        }
    }

    private fun transportErrorMessage(error: ClaudeCodeError): String = when (error) {
        is ClaudeCodeError.BinaryNotFound ->
            "Claude Code binary not found. Check the path in MaxVibes settings."

        is ClaudeCodeError.Timeout ->
            "Claude Code did not respond in time."

        is ClaudeCodeError.Crashed ->
            "Claude Code process crashed: ${error.message}"

        is ClaudeCodeError.ProcessFailed ->
            "Claude Code exited with code ${error.exitCode}: ${error.stderr.take(200)}"

        is ClaudeCodeError.ResumeFailed ->
            "Failed to resume claude session ${error.sessionId}: ${error.stderr.take(200)}"

        is ClaudeCodeError.ParseFailed ->
            "Failed to parse Claude Code response: ${error.message}"

        is ClaudeCodeError.Aborted ->
            "Claude Code turn was aborted." +
                    (error.partialText?.let {
                        " Partial output preserved (${it.length} chars)."
                    } ?: "")
    }

    private fun log(message: String) {
        println("[MaxVibes ClaudeCode] $message")
        logger?.info("ClaudeCode", message)
    }
}

internal sealed interface CodingAgentTurnExecutionResult {
    data class Success(
        val turn: ReceivedCodingAgentTurn
    ) : CodingAgentTurnExecutionResult

    data class Failure(
        val result: ClaudeCodeStepResult
    ) : CodingAgentTurnExecutionResult
}
