package com.maxvibes.application.service

import com.maxvibes.domain.model.interaction.ClipboardResponse
import com.maxvibes.domain.model.interaction.ClipboardModification
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Unit tests for [ClipboardResponseValidator].
 *
 * The [StubClipboardPort] simulates [ClipboardPort] responses without touching the AWT clipboard,
 * so all tests run cleanly in a headless Gradle environment.
 *
 * Test categories:
 * - Happy path: valid JSON objects and wrapped variants
 * - Parse failures: empty input, no JSON, malformed JSON, markdown wrapper
 * - Error payload: [buildErrorFeedbackJson] produces valid, informative JSON
 * - Real-world examples: inputs observed from LLM misbehaviour in production
 */
class ClipboardResponseValidatorTest {

    private val validator = ClipboardResponseValidator()

    // ── Happy path ────────────────────────────────────────────────────

    @Test
    fun `valid minimal JSON returns Valid`() {
        val port = StubClipboardPort(ClipboardResponse(message = "Hello"))
        val result = validator.validate("{\"message\":\"Hello\"}", port)
        assertTrue(result is ValidationResult.Valid)
        assertEquals("Hello", (result as ValidationResult.Valid).response.message)
    }

    @Test
    fun `valid JSON with modifications and requestedFiles returns Valid`() {
        val response = ClipboardResponse(
            message = "Done",
            requestedFiles = listOf("src/main/Foo.kt"),
            modifications = listOf(
                ClipboardModification(type = "REPLACE_FILE", path = "src/main/Foo.kt", content = "...")
            )
        )
        val port = StubClipboardPort(response)
        val result = validator.validate(
            "{\"message\":\"Done\",\"requestedFiles\":[\"src/main/Foo.kt\"],\"modifications\":[]}",
            port
        )
        assertTrue(result is ValidationResult.Valid)
    }

    // ── Empty / blank input ───────────────────────────────────────────

    @Test
    fun `blank input returns EmptyInput`() {
        val port = StubClipboardPort(null)
        val result = validator.validate("   \n  ", port)
        assertTrue(result is ValidationResult.EmptyInput)
    }

    @Test
    fun `empty string returns EmptyInput`() {
        val port = StubClipboardPort(null)
        val result = validator.validate("", port)
        assertTrue(result is ValidationResult.EmptyInput)
    }

    // ── No JSON found ─────────────────────────────────────────────────

    @Test
    fun `plain text without any braces returns NoJsonFound`() {
        val port = StubClipboardPort(null)
        val result = validator.validate("Sure, here is my answer. Let me explain it.", port)
        assertTrue(result is ValidationResult.ParseFailure)
        val details = (result as ValidationResult.ParseFailure).details
        assertTrue(details is ParseFailureDetails.NoJsonFound)
        assertEquals("NO_JSON_FOUND", details.reasonCode())
    }

    @Test
    fun `only a heading line returns NoJsonFound`() {
        val port = StubClipboardPort(null)
        val result = validator.validate("# My plan\nHere are the changes.", port)
        assertTrue(result is ValidationResult.ParseFailure)
        assertTrue((result as ValidationResult.ParseFailure).details is ParseFailureDetails.NoJsonFound)
    }

    // ── Markdown wrapper ──────────────────────────────────────────────

    @Test
    fun `response wrapped in json fence returns WrappedInMarkdown when codec fails`() {
        // Simulate a codec that cannot strip the fence (returns null)
        val port = StubClipboardPort(null)
        val input = "```json\n{\"message\":\"Hi\"}\n```"
        val result = validator.validate(input, port)
        // If codec returned null, validator diagnoses it
        assertTrue(result is ValidationResult.ParseFailure)
        val details = (result as ValidationResult.ParseFailure).details
        assertTrue(details is ParseFailureDetails.WrappedInMarkdown, "Expected WrappedInMarkdown but got details")
        assertEquals("WRAPPED_IN_MARKDOWN", details.reasonCode())
    }

    @Test
    fun `response wrapped in plain fence returns WrappedInMarkdown when codec fails`() {
        val port = StubClipboardPort(null)
        val input = "```\n{\"message\":\"test\"}\n```"
        val result = validator.validate(input, port)
        assertTrue(result is ValidationResult.ParseFailure)
        assertTrue((result as ValidationResult.ParseFailure).details is ParseFailureDetails.WrappedInMarkdown)
    }

    // ── Malformed JSON ────────────────────────────────────────────────

    @Test
    fun `JSON with unbalanced braces returns MalformedJson`() {
        val port = StubClipboardPort(null)
        // More opening braces than closing
        val result = validator.validate("{\"message\":{\"nested\":\"value\"}", port)
        assertTrue(result is ValidationResult.ParseFailure)
        val details = (result as ValidationResult.ParseFailure).details
        assertTrue(details is ParseFailureDetails.MalformedJson, "Expected MalformedJson but got $details")
        assertEquals("MALFORMED_JSON", details.reasonCode())
    }

    @Test
    fun `JSON with extra closing brace returns MalformedJson`() {
        val port = StubClipboardPort(null)
        val result = validator.validate("{\"message\":\"ok\"}}", port)
        assertTrue(result is ValidationResult.ParseFailure)
        assertTrue((result as ValidationResult.ParseFailure).details is ParseFailureDetails.MalformedJson)
    }

    @Test
    fun `port throwing exception returns MalformedJson`() {
        val port = ThrowingClipboardPort(RuntimeException("Unexpected token at position 42"))
        val result = validator.validate("{\"message\":\"test\"}", port)
        assertTrue(result is ValidationResult.ParseFailure)
        val details = (result as ValidationResult.ParseFailure).details
        assertTrue(details is ParseFailureDetails.MalformedJson)
        assertTrue(details.humanDescription().contains("Unexpected token"))
    }

    // ── buildErrorFeedbackJson ────────────────────────────────────────

    @Test
    fun `buildErrorFeedbackJson produces valid JSON string`() {
        val details = ParseFailureDetails.MalformedJson(
            exceptionMessage = "Unexpected '}' at position 87",
            snippet = "{\"message\":\"hi\"}"
        )
        val json = validator.buildErrorFeedbackJson(details, originalPreview = "{\"message\":\"hi\"}")
        // Must be parseable — just check structural indicators without a full JSON parser
        assertTrue(json.contains("\"_protocol\""), "Should include _protocol field")
        assertTrue(json.contains("\"parseError\""), "Should include parseError field")
        assertTrue(json.contains("\"reason\""), "Should include reason field")
        assertTrue(json.contains("MALFORMED_JSON"), "Should include reason code")
        assertTrue(json.contains("\"hint\""), "Should include hint field")
        assertTrue(json.contains("\"expectedFormat\""), "Should include expectedFormat field")
    }

    @Test
    fun `buildErrorFeedbackJson escapes special characters in preview`() {
        val details = ParseFailureDetails.NoJsonFound(hint = "No braces found")
        // Preview contains quotes and backslash that must be escaped
        val preview = "He said \"hello\" and used C:\\path"
        val json = validator.buildErrorFeedbackJson(details, originalPreview = preview)
        // The resulting string must not break JSON structure by containing unescaped quotes
        assertFalse(json.contains("\"He said \"hello\""), "Unescaped quote in preview would break JSON")
    }

    @Test
    fun `buildErrorFeedbackJson truncates long preview to PREVIEW_LENGTH`() {
        val details = ParseFailureDetails.NoJsonFound(hint = "nothing")
        val longPreview = "x".repeat(1000)
        val json = validator.buildErrorFeedbackJson(details, originalPreview = longPreview)
        // The raw payload must not contain more than PREVIEW_LENGTH x-chars in a row
        val maxChunkOfX = Regex("x+").findAll(json).maxOfOrNull { it.value.length } ?: 0
        assertTrue(maxChunkOfX <= ClipboardResponseValidator.PREVIEW_LENGTH, "Preview was not truncated")
    }

    // ── Real-world examples ───────────────────────────────────────────

    @Test
    fun `real LLM response with preamble text and JSON returns Valid when codec handles it`() {
        val response = ClipboardResponse(message = "Here are the changes")
        val port = StubClipboardPort(response)
        // Simulates: LLM writes explanation text, then a JSON block
        val input = "Sure! Here are my changes:\n\n{\"message\":\"Here are the changes\",\"modifications\":[]}"
        val result = validator.validate(input, port)
        assertTrue(result is ValidationResult.Valid)
    }

    @Test
    fun `real LLM response that is only prose with no JSON returns NoJsonFound`() {
        val port = StubClipboardPort(null)
        val input = """
            I would suggest the following approach:
            First, refactor the service layer to extract...
            Then, update the tests.
            Let me know if you want me to proceed.
        """.trimIndent()
        val result = validator.validate(input, port)
        assertTrue(result is ValidationResult.ParseFailure)
        assertTrue((result as ValidationResult.ParseFailure).details is ParseFailureDetails.NoJsonFound)
    }

    @Test
    fun `real LLM response truncated mid-JSON returns MalformedJson`() {
        val port = StubClipboardPort(null)
        // Simulates copy-paste cut off before closing brace
        val input =
            "{\"message\":\"I will now apply the following changes:\",\"modifications\":[{\"type\":\"REPLACE_FILE\""
        val result = validator.validate(input, port)
        assertTrue(result is ValidationResult.ParseFailure)
        val details = (result as ValidationResult.ParseFailure).details
        assertTrue(details is ParseFailureDetails.MalformedJson)
    }

    // ── ParseFailureDetails API ───────────────────────────────────────

    @Test
    fun `all ParseFailureDetails subtypes have non-blank reasonCode, description and hint`() {
        val cases = listOf(
            ParseFailureDetails.NoJsonFound(hint = "test hint"),
            ParseFailureDetails.MalformedJson(exceptionMessage = "parse error", snippet = "{..."),
            ParseFailureDetails.MissingRequiredField(fieldName = "message"),
            ParseFailureDetails.WrappedInMarkdown(detected = "```json{...}```")
        )
        for (details in cases) {
            val name = details::class.simpleName
            assertTrue(details.reasonCode().isNotBlank(), "$name has blank reasonCode")
            assertTrue(details.humanDescription().isNotBlank(), "$name has blank humanDescription")
            assertTrue(details.correctionHint().isNotBlank(), "$name has blank correctionHint")
        }
    }
}

// ── Test doubles ─────────────────────────────────────────────────────

/** Stub [ClipboardPort] that always returns [response] from [parseResponse] and does nothing for clipboard writes. */
private class StubClipboardPort(
    private val response: ClipboardResponse?
) : com.maxvibes.application.port.output.ClipboardPort {
    override fun copyRequestToClipboard(request: com.maxvibes.domain.model.interaction.ClipboardRequest) = true
    override fun copyRawText(text: String) = true
    override fun parseResponse(rawText: String): ClipboardResponse? = response
}

/** Stub [ClipboardPort] whose [parseResponse] always throws [exception]. */
private class ThrowingClipboardPort(
    private val exception: Exception
) : com.maxvibes.application.port.output.ClipboardPort {
    override fun copyRequestToClipboard(request: com.maxvibes.domain.model.interaction.ClipboardRequest) = true
    override fun copyRawText(text: String) = true
    override fun parseResponse(rawText: String): ClipboardResponse? = throw exception
}
