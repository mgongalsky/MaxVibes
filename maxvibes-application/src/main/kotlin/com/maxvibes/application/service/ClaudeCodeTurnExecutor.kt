package com.maxvibes.application.service

import com.maxvibes.application.port.output.ChatSessionRepository
import com.maxvibes.application.port.output.ClaudeCodeError
import com.maxvibes.application.port.output.CodingAgentCliPort
import com.maxvibes.application.port.output.CodingAgentCliSendResult
import com.maxvibes.application.port.output.CodingAgentSessionLogPort
import com.maxvibes.application.port.output.LoggerPort
import com.maxvibes.application.port.output.NotificationPort
import com.maxvibes.domain.model.chat.ChatSession
import com.maxvibes.domain.model.chat.CodingAgentProvider
import com.maxvibes.domain.model.chat.CodingAgentSessionRef
import com.maxvibes.shared.result.Result

internal class CodingAgentTurnExecutor(
    private val claudeCodePort: CodingAgentCliPort,
    private val chatSessionRepository: ChatSessionRepository,
    private val notificationPort: NotificationPort,
    private val sessionLog: CodingAgentSessionLogPort? = null,
    private val streamHub: AgentStreamHub? = null,
    private val logger: LoggerPort? = null,
    private val provider: CodingAgentProvider = CodingAgentProvider.CLAUDE_CODE
) {
    private val policy = CodingAgentProviderPolicy.forProvider(provider)

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

        var agentSession = currentAgentSession(session)
        val needsFull = command.firstMessage || agentSession.needsFullContext
        var request = CodingAgentRequestFactory.create(
            provider = provider,
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
            resumeSessionId = agentSession.remoteSessionId,
            systemPrompt = state.prompts.chatSystem
        )

        if (ensureResult is Result.Failure && ensureResult.error is ClaudeCodeError.ResumeFailed) {
            val resumeFailure = ensureResult.error as ClaudeCodeError.ResumeFailed
            log("Resume failed for sessionId=${resumeFailure.sessionId}; falling back to fresh start.")
            sessionLog?.event(
                "resume failed — falling back to fresh start",
                mapOf("remoteSessionId" to resumeFailure.sessionId)
            )

            agentSession = CodingAgentSessionRef(
                provider = provider,
                remoteSessionId = null,
                needsFullContext = true
            )
            session = withAgentSession(session, agentSession)
            chatSessionRepository.saveSession(session)

            request = CodingAgentRequestFactory.create(
                provider = provider,
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
                ClaudeCodeStepResult.TransportError(transportErrorMessage(ensureResult.error))
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
        notificationPort.showProgress("Sending to ${policy.displayName}...", 0.5)

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
                        inputTokens = stats?.inputTokens?.takeIf { it > 0 } ?: estimatedInputTokens,
                        outputTokens = stats?.outputTokens?.takeIf { it > 0 }
                            ?: TokenEstimator.estimateOutputTokens(payload.response),
                        thinkingText = payload.thinkingText,
                        durationMs = stats?.durationMs?.takeIf { it > 0 } ?: measuredDurationMs,
                        costUsd = stats?.costUsd?.takeIf { it > 0.0 },
                        numTurns = stats?.numTurns?.takeIf { it > 0 }
                    )
                )
            }

            is Result.Failure -> {
                log("Send failed: ${sendResult.error} (after ${measuredDurationMs}ms)")
                sessionLog?.event(
                    "send failed",
                    mapOf("error" to sendResult.error.toString(), "elapsedMs" to measuredDurationMs)
                )
                CodingAgentTurnExecutionResult.Failure(
                    ClaudeCodeStepResult.TransportError(transportErrorMessage(sendResult.error))
                )
            }
        }
    }

    fun shutdown() {
        try {
            claudeCodePort.shutdown()
        } catch (exception: Exception) {
            log("Warning: shutdown raised ${exception.javaClass.simpleName}: ${exception.message}")
        }
    }

    private fun persistObservedSession(payload: CodingAgentCliSendResult, session: ChatSession) {
        val current = currentAgentSession(session)
        val observedId = payload.observedSessionId
        if (observedId != null || current.needsFullContext) {
            val updated = current.copy(
                remoteSessionId = observedId ?: current.remoteSessionId,
                needsFullContext = false
            )
            chatSessionRepository.saveSession(withAgentSession(session, updated))
        }
    }

    private fun currentAgentSession(session: ChatSession): CodingAgentSessionRef {
        val persisted = session.agentCliSession
        if (persisted?.provider == provider) return persisted

        return if (provider == CodingAgentProvider.CLAUDE_CODE) {
            CodingAgentSessionRef(
                provider = provider,
                remoteSessionId = session.claudeCodeSessionId,
                needsFullContext = session.claudeCodeNeedsFullContext
            )
        } else {
            CodingAgentSessionRef(provider = provider)
        }
    }

    private fun withAgentSession(session: ChatSession, state: CodingAgentSessionRef): ChatSession =
        if (state.provider == CodingAgentProvider.CLAUDE_CODE) {
            session.copy(
                agentCliSession = state,
                claudeCodeSessionId = state.remoteSessionId,
                claudeCodeNeedsFullContext = state.needsFullContext
            )
        } else {
            session.copy(agentCliSession = state)
        }

    private fun transportErrorMessage(error: ClaudeCodeError): String = when (error) {
        is ClaudeCodeError.BinaryNotFound ->
            "${policy.displayName} binary not found. Check the path in MaxVibes settings."

        is ClaudeCodeError.Timeout ->
            "${policy.displayName} did not respond in time."

        is ClaudeCodeError.Crashed ->
            "${policy.displayName} process crashed: ${error.message}"

        is ClaudeCodeError.ProcessFailed ->
            "${policy.displayName} exited with code ${error.exitCode}: ${error.stderr.take(200)}"

        is ClaudeCodeError.ResumeFailed ->
            "Failed to resume ${policy.displayName} session ${error.sessionId}: ${error.stderr.take(200)}"

        is ClaudeCodeError.ParseFailed ->
            "Failed to parse ${policy.displayName} response: ${error.message}"

        is ClaudeCodeError.Aborted ->
            "${policy.displayName} turn was aborted." +
                    (error.partialText?.let { " Partial output preserved (${it.length} chars)." } ?: "")
    }

    private fun log(message: String) {
        println("[MaxVibes ${policy.logTag}] $message")
        logger?.info(policy.logTag, message)
    }
}

internal typealias ClaudeCodeTurnExecutor = CodingAgentTurnExecutor

internal sealed interface CodingAgentTurnExecutionResult {
    data class Success(val turn: ReceivedCodingAgentTurn) : CodingAgentTurnExecutionResult
    data class Failure(val result: CodingAgentStepResult) : CodingAgentTurnExecutionResult
}
