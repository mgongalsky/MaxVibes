package com.maxvibes.application.service

import com.maxvibes.application.port.output.VoiceTranscript
import com.maxvibes.application.port.output.VoiceTranscriptionError
import com.maxvibes.application.port.output.VoiceTranscriptionPort
import com.maxvibes.application.port.output.VoiceTranscriptionRequest
import com.maxvibes.shared.result.Result
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VoiceTranscriptionServiceTest {
    @Test
    fun `rejects empty audio without calling port`() = runBlocking {
        val port = RecordingPort()

        val result = VoiceTranscriptionService(port).transcribe(
            audio = byteArrayOf(),
            model = "whisper-1"
        )

        assertIs<Result.Failure<VoiceTranscriptionError.InvalidRequest>>(result)
        assertNull(port.request)
    }

    @Test
    fun `rejects blank model without calling port`() = runBlocking {
        val port = RecordingPort()

        val result = VoiceTranscriptionService(port).transcribe(
            audio = byteArrayOf(1),
            model = "  "
        )

        assertIs<Result.Failure<VoiceTranscriptionError.InvalidRequest>>(result)
        assertNull(port.request)
    }

    @Test
    fun `normalizes options and sends bounded deduplicated prompt`() = runBlocking {
        val port = RecordingPort()
        val longTail = "x".repeat(VoiceTranscriptionService.MAX_PROMPT_LENGTH)

        val result = VoiceTranscriptionService(port).transcribe(
            audio = byteArrayOf(1, 2, 3),
            model = " whisper-1 ",
            language = " ru ",
            contextTerms = listOf(" Kotlin ", "kotlin", "IntelliJ   IDEA", longTail)
        )

        assertEquals(Result.Success(VoiceTranscript("готово")), result)
        val request = requireNotNull(port.request)
        assertEquals("whisper-1", request.model)
        assertEquals("ru", request.language)
        assertEquals("Kotlin, IntelliJ IDEA", request.prompt)
        assertTrue(requireNotNull(request.prompt).length <= VoiceTranscriptionService.MAX_PROMPT_LENGTH)
    }

    @Test
    fun `omits blank language and empty prompt`() = runBlocking {
        val port = RecordingPort()

        VoiceTranscriptionService(port).transcribe(
            audio = byteArrayOf(1),
            model = "whisper-1",
            language = " ",
            contextTerms = listOf("", "  ")
        )

        assertNull(requireNotNull(port.request).language)
        assertNull(requireNotNull(port.request).prompt)
    }

    @Test
    fun `propagates typed provider failure`() = runBlocking {
        val expected = VoiceTranscriptionError.RateLimit()
        val port = RecordingPort(Result.Failure(expected))

        val result = VoiceTranscriptionService(port).transcribe(
            audio = byteArrayOf(1),
            model = "whisper-1"
        )

        assertIs<Result.Failure<VoiceTranscriptionError.RateLimit>>(result)
        assertEquals(expected, result.error)
    }

    private class RecordingPort(
        private val result: Result<VoiceTranscript, VoiceTranscriptionError> =
            Result.Success(VoiceTranscript("готово"))
    ) : VoiceTranscriptionPort {
        var request: VoiceTranscriptionRequest? = null

        override suspend fun transcribe(
            request: VoiceTranscriptionRequest
        ): Result<VoiceTranscript, VoiceTranscriptionError> {
            this.request = request
            return result
        }
    }
}
