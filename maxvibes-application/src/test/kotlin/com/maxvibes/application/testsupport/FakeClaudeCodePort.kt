package com.maxvibes.application.testsupport

import com.maxvibes.application.port.output.ClaudeCodeError
import com.maxvibes.application.port.output.ClaudeCodePort
import com.maxvibes.application.port.output.ClaudeCodeSendResult
import com.maxvibes.domain.model.interaction.ClipboardRequest
import com.maxvibes.domain.model.interaction.InteractionResponse
import com.maxvibes.shared.result.Result

/**
 * Recording fake of [ClaudeCodePort].
 *
 * [send] pops the next scripted result from a FIFO queue and records the request;
 * a send without a scripted result fails the test with an explicit message.
 * [ensureStarted] and [shutdown] are recorded so tests can assert lifecycle calls.
 */
class FakeClaudeCodePort : ClaudeCodePort {

    /** Every request passed to [send], in order. */
    val sentRequests = mutableListOf<ClipboardRequest>()

    /** Every (resumeSessionId, systemPrompt) pair passed to [ensureStarted], in order. */
    val ensureStartedCalls = mutableListOf<Pair<String?, String?>>()

    var shutdownCount = 0
        private set

    /** Result returned by every [ensureStarted] call. */
    var ensureStartedResult: Result<Unit, ClaudeCodeError> = Result.Success(Unit)

    private val scriptedResults = ArrayDeque<Result<ClaudeCodeSendResult, ClaudeCodeError>>()

    fun enqueueResponse(
        response: InteractionResponse,
        observedSessionId: String? = "claude-sid",
        thinkingText: String? = null
    ) {
        scriptedResults.addLast(
            Result.Success(ClaudeCodeSendResult(response, observedSessionId, thinkingText))
        )
    }

    fun enqueueFailure(error: ClaudeCodeError) {
        scriptedResults.addLast(Result.Failure(error))
    }

    override fun isAvailable(): Boolean = true

    override suspend fun ensureStarted(
        resumeSessionId: String?,
        systemPrompt: String?
    ): Result<Unit, ClaudeCodeError> {
        ensureStartedCalls += resumeSessionId to systemPrompt
        return ensureStartedResult
    }

    override suspend fun send(request: ClipboardRequest): Result<ClaudeCodeSendResult, ClaudeCodeError> {
        sentRequests += request
        check(scriptedResults.isNotEmpty()) {
            "FakeClaudeCodePort: no scripted result for send #${sentRequests.size} " +
                    "(currentMessage=${request.currentMessage.take(60)})"
        }
        return scriptedResults.removeFirst()
    }

    override fun shutdown() {
        shutdownCount++
    }
}
