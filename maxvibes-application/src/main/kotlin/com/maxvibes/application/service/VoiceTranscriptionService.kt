package com.maxvibes.application.service

import com.maxvibes.application.port.output.VoiceTranscript
import com.maxvibes.application.port.output.VoiceTranscriptionError
import com.maxvibes.application.port.output.VoiceTranscriptionPort
import com.maxvibes.application.port.output.VoiceTranscriptionRequest
import com.maxvibes.shared.result.Result

/**
 * Validates recorded audio and prepares a bounded terminology hint before delegating to
 * the configured speech-to-text adapter.
 */
class VoiceTranscriptionService(
    private val port: VoiceTranscriptionPort
) {
    suspend fun transcribe(
        audio: ByteArray,
        model: String,
        language: String? = null,
        contextTerms: List<String> = emptyList(),
        fileName: String = DEFAULT_FILE_NAME,
        mimeType: String = WAV_MIME_TYPE
    ): Result<VoiceTranscript, VoiceTranscriptionError> {
        if (audio.isEmpty()) {
            return Result.Failure(
                VoiceTranscriptionError.InvalidRequest("Recorded audio is empty")
            )
        }
        val normalizedModel = model.trim()
        if (normalizedModel.isEmpty()) {
            return Result.Failure(
                VoiceTranscriptionError.InvalidRequest("Voice transcription model is not configured")
            )
        }
        val normalizedFileName = fileName.trim()
        if (normalizedFileName.isEmpty()) {
            return Result.Failure(
                VoiceTranscriptionError.InvalidRequest("Audio file name is empty")
            )
        }
        val normalizedMimeType = mimeType.trim()
        if (normalizedMimeType.isEmpty()) {
            return Result.Failure(
                VoiceTranscriptionError.InvalidRequest("Audio MIME type is empty")
            )
        }

        return port.transcribe(
            VoiceTranscriptionRequest(
                audio = audio,
                fileName = normalizedFileName,
                mimeType = normalizedMimeType,
                model = normalizedModel,
                language = language?.trim()?.takeIf { it.isNotEmpty() },
                prompt = buildPrompt(contextTerms)
            )
        )
    }

    internal fun buildPrompt(terms: List<String>): String? {
        val uniqueTerms = LinkedHashMap<String, String>()
        terms.asSequence()
            .map { it.trim().replace(WHITESPACE, " ") }
            .filter { it.isNotEmpty() }
            .forEach { term -> uniqueTerms.putIfAbsent(term.lowercase(), term) }

        if (uniqueTerms.isEmpty()) return null

        val prompt = StringBuilder()
        for (term in uniqueTerms.values) {
            val separatorLength = if (prompt.isEmpty()) 0 else SEPARATOR.length
            if (prompt.length + separatorLength + term.length > MAX_PROMPT_LENGTH) break
            if (prompt.isNotEmpty()) prompt.append(SEPARATOR)
            prompt.append(term)
        }
        return prompt.toString().takeIf { it.isNotEmpty() }
    }

    companion object {
        const val MAX_PROMPT_LENGTH = 1_200
        const val DEFAULT_FILE_NAME = "maxvibes-recording.wav"
        const val WAV_MIME_TYPE = "audio/wav"
        private const val SEPARATOR = ", "
        private val WHITESPACE = Regex("\\s+")
    }
}
