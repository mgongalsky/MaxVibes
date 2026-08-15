package com.maxvibes.plugin.voice

import com.maxvibes.application.port.output.VoiceTranscript
import com.maxvibes.application.port.output.VoiceTranscriptionError
import com.maxvibes.application.port.output.VoiceTranscriptionPort
import com.maxvibes.application.port.output.VoiceTranscriptionRequest
import com.maxvibes.shared.result.Result
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID

/** Multipart adapter for OpenAI-compatible `/audio/transcriptions` endpoints. */
class OpenAiCompatibleTranscriptionAdapter(
    private val endpoint: String,
    private val apiKey: String,
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS
) : VoiceTranscriptionPort {
    override suspend fun transcribe(
        request: VoiceTranscriptionRequest
    ): Result<VoiceTranscript, VoiceTranscriptionError> {
        if (endpoint.isBlank() || apiKey.isBlank()) {
            return Result.Failure(
                VoiceTranscriptionError.InvalidRequest("Voice transcription API is not configured")
            )
        }

        val connection = try {
            URL(endpoint).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            return Result.Failure(
                VoiceTranscriptionError.InvalidRequest("Invalid transcription endpoint: ${e.message}")
            )
        }

        return try {
            val boundary = "MaxVibes-${UUID.randomUUID()}"
            val body = buildMultipartBody(boundary, request)
            connection.requestMethod = "POST"
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }

            val status = connection.responseCode
            val responseBody = readResponse(connection, status)
            when (status) {
                in 200..299 -> parseSuccess(responseBody)
                401, 403 -> Result.Failure(VoiceTranscriptionError.Authentication())
                429 -> Result.Failure(VoiceTranscriptionError.RateLimit())
                else -> Result.Failure(
                    VoiceTranscriptionError.Provider(
                        "HTTP $status${
                            responseBody.takeIf(String::isNotBlank)?.let { ": ${it.take(MAX_ERROR_LENGTH)}" }.orEmpty()
                        }"
                    )
                )
            }
        } catch (e: Exception) {
            Result.Failure(
                VoiceTranscriptionError.Network(e.message ?: e.javaClass.simpleName)
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun buildMultipartBody(
        boundary: String,
        request: VoiceTranscriptionRequest
    ): ByteArray = ByteArrayOutputStream().use { output ->
        fun write(value: String) {
            output.write(value.toByteArray(StandardCharsets.UTF_8))
        }

        fun textPart(name: String, value: String) {
            write("--$boundary\r\n")
            write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
            write(value)
            write("\r\n")
        }

        textPart("model", request.model)
        request.language?.let { textPart("language", it) }
        request.prompt?.let { textPart("prompt", it) }

        write("--$boundary\r\n")
        write(
            "Content-Disposition: form-data; name=\"file\"; filename=\"${safeFileName(request.fileName)}\"\r\n"
        )
        write("Content-Type: ${request.mimeType}\r\n\r\n")
        output.write(request.audio)
        write("\r\n--$boundary--\r\n")
        output.toByteArray()
    }

    private fun parseSuccess(body: String): Result<VoiceTranscript, VoiceTranscriptionError> = try {
        val text = Json.parseToJsonElement(body).jsonObject["text"]
            ?.jsonPrimitive?.contentOrNull?.trim()
        if (text.isNullOrEmpty()) {
            Result.Failure(
                VoiceTranscriptionError.InvalidResponse("Response does not contain transcript text")
            )
        } else {
            Result.Success(VoiceTranscript(text))
        }
    } catch (e: Exception) {
        Result.Failure(
            VoiceTranscriptionError.InvalidResponse(e.message ?: "Malformed JSON")
        )
    }

    private fun readResponse(connection: HttpURLConnection, status: Int): String {
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        return stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
    }

    private fun safeFileName(value: String): String = value
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .replace("\"", "_")
        .replace("\r", "_")
        .replace("\n", "_")

    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS = 15_000
        const val DEFAULT_READ_TIMEOUT_MS = 120_000
        private const val MAX_ERROR_LENGTH = 500
    }
}
