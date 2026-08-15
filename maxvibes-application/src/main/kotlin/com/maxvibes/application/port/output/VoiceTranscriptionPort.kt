package com.maxvibes.application.port.output

import com.maxvibes.shared.result.Result

/** Transport-neutral request sent to a speech-to-text provider. */
data class VoiceTranscriptionRequest(
    val audio: ByteArray,
    val fileName: String,
    val mimeType: String,
    val model: String,
    val language: String? = null,
    val prompt: String? = null
)

/** Successful speech-to-text result. */
data class VoiceTranscript(val text: String)

/** Typed failures surfaced by a voice transcription provider. */
sealed class VoiceTranscriptionError(val message: String) {
    class InvalidRequest(details: String) : VoiceTranscriptionError(details)
    class Authentication(details: String = "Voice transcription authentication failed") :
        VoiceTranscriptionError(details)

    class RateLimit(details: String = "Voice transcription rate limit exceeded") :
        VoiceTranscriptionError(details)

    class Network(details: String) : VoiceTranscriptionError("Network error: $details")
    class Provider(details: String) : VoiceTranscriptionError("Provider error: $details")
    class InvalidResponse(details: String) : VoiceTranscriptionError("Invalid response: $details")
}

/** Output port implemented by a cloud or local speech-to-text adapter. */
interface VoiceTranscriptionPort {
    suspend fun transcribe(
        request: VoiceTranscriptionRequest
    ): Result<VoiceTranscript, VoiceTranscriptionError>
}
