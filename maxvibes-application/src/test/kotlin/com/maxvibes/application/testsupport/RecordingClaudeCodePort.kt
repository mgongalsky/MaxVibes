package com.maxvibes.application.testsupport

import com.maxvibes.application.port.output.ClaudeCodeError
import com.maxvibes.application.port.output.ClaudeCodePort
import com.maxvibes.application.port.output.ClaudeCodeSendResult
import com.maxvibes.domain.model.interaction.ClipboardRequest
import com.maxvibes.domain.model.interaction.InteractionResponse
import com.maxvibes.shared.result.Result
import java.util.ArrayDeque

/** Recording fake for direct ClaudeCodeTurnExecutor and facade tests. */
class RecordingClaudeCodePort : ClaudeCodePort {
    data class EnsureCall(
        val resumeSessionId: String?,
        val systemPrompt: String?
    )

    var available: Boolean = true
    var shutdownFailure: RuntimeException? = null

    val ensureCalls = mutableListOf<EnsureCall>()
    val sentRequests = mutableListOf<ClipboardRequest>()

    var shutdownCalls: Int = 0
        private set

    var abortCalls: Int = 0
        private set

    private val ensureResults =
        ArrayDeque<Result<Unit, ClaudeCodeError>>()
    private val sendResults =
        ArrayDeque<Result<ClaudeCodeSendResult, ClaudeCodeError>>()

    override fun isAvailable(): Boolean = available

    fun enqueueEnsure(result: Result<Unit, ClaudeCodeError>) {
        ensureResults.addLast(result)
    }

    fun enqueueSend(
        result: Result<ClaudeCodeSendResult, ClaudeCodeError>
    ) {
        sendResults.addLast(result)
    }

    fun enqueueResponse(
        response: InteractionResponse,
        observedSessionId: String? = null
    ) {
        enqueueSend(
            Result.Success(
                ClaudeCodeSendResult(
                    response = response,
                    observedSessionId = observedSessionId
                )
            )
        )
    }

    override suspend fun ensureStarted(
        resumeSessionId: String?,
        systemPrompt: String?
    ): Result<Unit, ClaudeCodeError> {
        ensureCalls += EnsureCall(
            resumeSessionId = resumeSessionId,
            systemPrompt = systemPrompt
        )
        return if (ensureResults.isEmpty()) {
            Result.Success(Unit)
        } else {
            ensureResults.removeFirst()
        }
    }

    override suspend fun send(
        request: ClipboardRequest
    ): Result<ClaudeCodeSendResult, ClaudeCodeError> {
        sentRequests += request
        return if (sendResults.isEmpty()) {
            Result.Success(
                ClaudeCodeSendResult(
                    response = InteractionResponse(message = "Done."),
                    observedSessionId = null
                )
            )
        } else {
            sendResults.removeFirst()
        }
    }

    override fun shutdown() {
        shutdownCalls += 1
        shutdownFailure?.let { throw it }
    }

    override fun abort() {
        abortCalls += 1
    }
}
