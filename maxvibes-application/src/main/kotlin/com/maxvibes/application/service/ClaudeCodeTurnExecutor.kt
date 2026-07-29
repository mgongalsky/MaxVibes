package com.maxvibes.application.service

import com.maxvibes.application.port.output.ChatSessionRepository
import com.maxvibes.application.port.output.ClaudeCodeError
import com.maxvibes.application.port.output.ClaudeCodePort
import com.maxvibes.application.port.output.ClaudeCodeSendResult
import com.maxvibes.application.port.output.ClaudeCodeSessionLogPort
import com.maxvibes.application.port.output.LoggerPort
import com.maxvibes.application.port.output.NotificationPort
import com.maxvibes.shared.result.Result

/**
 * Executes one transport-level Claude Code turn.
 *
 * Owns request assembly, process startup/resume fallback, token accounting,
 * transport execution and persisted Claude session metadata. Response semantics
 * are intentionally delegated to ClaudeCodeResponseHandler.
 */
internal class ClaudeCodeTurnExecutor(
    private val claudeCodePort: ClaudeCodePort,
    private val chatSessionRepository: ChatSessionRepository,
    private val notificationPort: NotificationPort,
    private val sessionLog: ClaudeCodeSessionLogPort? = null,
    private val streamHub: AgentStreamHub? = null,
    private val logger: LoggerPort? = null
) {

    suspend fun execute(
        command: ClaudeCodeTurnCommand,
        state: ClipboardSessionState
    ): ClaudeCodeTurnExecutionResult {
        val sessionId = command.sessionId
        streamHub?.begin(sessionId)
        var session = chatSessionRepository.getSessionById(sessionId)
            ?: return ClaudeCodeTurnExecutionResult.Failure(
                ClaudeCodeStepResult.Error("Session not found: $sessionId")
            )

        val needsFull = command.firstMessage || session.claudeCodeNeedsFullContext
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
            resumeSessionId = session.claudeCodeSessionId,
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

            session = session.copy(
                claudeCodeSessionId = null,
                claudeCodeNeedsFullContext = true
            )
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
            return ClaudeCodeTurnExecutionResult.Failure(
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
                val payload: ClaudeCodeSendResult = sendResult.value
                persistObservedSession(payload, session)
                val stats = payload.stats

                ClaudeCodeTurnExecutionResult.Success(
                    ReceivedClaudeTurn(
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
                ClaudeCodeTurnExecutionResult.Failure(
                    ClaudeCodeStepResult.TransportError(
                        transportErrorMessage(sendResult.error)
                    )
                )
            }
        }
    }

    private fun persistObservedSession(
        payload: ClaudeCodeSendResult,
        session: com.maxvibes.domain.model.chat.ChatSession
    ) {
        val observedId = payload.observedSessionId
        if (observedId != null || session.claudeCodeNeedsFullContext) {
            chatSessionRepository.saveSession(
                session.copy(
                    claudeCodeSessionId = observedId ?: session.claudeCodeSessionId,
                    claudeCodeNeedsFullContext = false
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

internal sealed interface ClaudeCodeTurnExecutionResult {
    data class Success(
        val turn: ReceivedClaudeTurn
    ) : ClaudeCodeTurnExecutionResult

    data class Failure(
        val result: ClaudeCodeStepResult
    ) : ClaudeCodeTurnExecutionResult
}
