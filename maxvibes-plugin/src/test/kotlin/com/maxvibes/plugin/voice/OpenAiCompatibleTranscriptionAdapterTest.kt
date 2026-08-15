package com.maxvibes.plugin.voice

import com.maxvibes.application.port.output.VoiceTranscript
import com.maxvibes.application.port.output.VoiceTranscriptionError
import com.maxvibes.application.port.output.VoiceTranscriptionRequest
import com.maxvibes.shared.result.Result
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OpenAiCompatibleTranscriptionAdapterTest {
    private var server: HttpServer? = null

    @AfterTest
    fun stopServer() {
        server?.stop(0)
    }

    @Test
    fun sendsAuthenticatedMultipartRequestAndParsesTranscript() {
        runBlocking {
            val capturedAuthorization = AtomicReference<String>()
            val capturedContentType = AtomicReference<String>()
            val capturedBody = AtomicReference<String>()
            val endpoint = startServer { exchange ->
                capturedAuthorization.set(exchange.requestHeaders.getFirst("Authorization"))
                capturedContentType.set(exchange.requestHeaders.getFirst("Content-Type"))
                capturedBody.set(exchange.requestBody.readBytes().toString(Charsets.ISO_8859_1))
                respond(exchange, 200, "{\"text\":\"  Привет, Kotlin  \"}")
            }

            val result = OpenAiCompatibleTranscriptionAdapter(endpoint, "voice-secret").transcribe(
                request(prompt = "MaxVibes, Kotlin", language = "ru")
            )

            assertEquals(Result.Success(VoiceTranscript("Привет, Kotlin")), result)
            assertEquals("Bearer voice-secret", capturedAuthorization.get())
            assertTrue(capturedContentType.get().startsWith("multipart/form-data; boundary="))
            val body = capturedBody.get()
            assertTrue(body.contains("name=\"model\"\r\n\r\nwhisper-1"))
            assertTrue(body.contains("name=\"language\"\r\n\r\nru"))
            assertTrue(body.contains("name=\"prompt\"\r\n\r\nMaxVibes, Kotlin"))
            assertTrue(body.contains("name=\"file\"; filename=\"recording.wav\""))
            assertTrue(body.contains("Content-Type: audio/wav"))
        }
    }

    @Test
    fun mapsAuthenticationFailure() {
        runBlocking {
            val endpoint = startServer { respond(it, 401, "unauthorized") }

            val result = OpenAiCompatibleTranscriptionAdapter(endpoint, "bad-key")
                .transcribe(request())

            assertIs<Result.Failure<VoiceTranscriptionError.Authentication>>(result)
        }
    }

    @Test
    fun mapsRateLimitFailure() {
        runBlocking {
            val endpoint = startServer { respond(it, 429, "slow down") }

            val result = OpenAiCompatibleTranscriptionAdapter(endpoint, "key")
                .transcribe(request())

            assertIs<Result.Failure<VoiceTranscriptionError.RateLimit>>(result)
        }
    }

    @Test
    fun rejectsSuccessfulResponseWithoutText() {
        runBlocking {
            val endpoint = startServer { respond(it, 200, "{\"duration\":1}") }

            val result = OpenAiCompatibleTranscriptionAdapter(endpoint, "key")
                .transcribe(request())

            assertIs<Result.Failure<VoiceTranscriptionError.InvalidResponse>>(result)
        }
    }

    @Test
    fun rejectsMissingAdapterConfigurationWithoutNetworkCall() {
        runBlocking {
            val result = OpenAiCompatibleTranscriptionAdapter("", "")
                .transcribe(request())

            assertIs<Result.Failure<VoiceTranscriptionError.InvalidRequest>>(result)
        }
    }

    private fun request(
        prompt: String? = null,
        language: String? = null
    ) = VoiceTranscriptionRequest(
        audio = byteArrayOf(1, 2, 3),
        fileName = "recording.wav",
        mimeType = "audio/wav",
        model = "whisper-1",
        language = language,
        prompt = prompt
    )

    private fun startServer(handler: (HttpExchange) -> Unit): String {
        val created = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        created.createContext("/audio/transcriptions", handler)
        created.start()
        server = created
        return "http://127.0.0.1:${created.address.port}/audio/transcriptions"
    }

    private fun respond(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
