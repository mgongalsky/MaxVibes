package com.maxvibes.application.service

import com.maxvibes.application.port.output.ClipboardPort
import com.maxvibes.application.port.output.ClipboardRequestSchema
import com.maxvibes.domain.model.interaction.ClipboardResponse

/**
 * Validates raw LLM responses and produces diagnostic error payloads on parse failure.
 *
 * Wraps [ClipboardPort.parseResponse] with structured diagnosis so the plugin can copy
 * a detailed error JSON back to the LLM instead of showing a generic "invalid JSON" message.
 *
 * Stateless — safe to use as a private field. No IntelliJ SDK dependencies,
 * fully testable via Gradle in the application module.
 */
class ClipboardResponseValidator {

    // ── Public API ────────────────────────────────────────────────────

    /**
     * Attempts to parse [rawText] via [port] and diagnoses the failure if parsing returns null.
     *
     * @param rawText raw string pasted by the user from an external LLM
     * @param port    clipboard port whose [ClipboardPort.parseResponse] performs actual decoding
     * @return [ValidationResult.Valid] on success; [ValidationResult.ParseFailure] or
     *         [ValidationResult.EmptyInput] when parsing cannot produce a response
     */
    fun validate(rawText: String, port: ClipboardPort): ValidationResult {
        if (rawText.isBlank()) return ValidationResult.EmptyInput

        // Happy path: port handles fenced blocks, embedded JSON, plain text, etc.
        val response = try {
            port.parseResponse(rawText)
        } catch (e: Exception) {
            // Port threw unexpectedly — treat as malformed JSON with exception detail
            return ValidationResult.ParseFailure(
                ParseFailureDetails.MalformedJson(
                    exceptionMessage = "${e.javaClass.simpleName}: ${e.message}",
                    snippet = rawText.trimStart().take(200)
                )
            )
        }

        if (response != null) return ValidationResult.Valid(response)

        // Port returned null — run heuristic diagnosis to explain why
        return ValidationResult.ParseFailure(diagnose(rawText))
    }

    /**
     * Builds a structured JSON error payload to paste back to the LLM.
     *
     * Instructs the LLM to resend its response in the correct format and includes
     * the specific reason the previous response was rejected.
     *
     * @param details         structured description of the failure
     * @param originalPreview first [PREVIEW_LENGTH] characters of the raw input for context
     * @return pretty-printed JSON string ready to be placed on the system clipboard
     */
    fun buildErrorFeedbackJson(details: ParseFailureDetails, originalPreview: String): String {
        val protocol = ClipboardRequestSchema.META_PROTOCOL
        val protocolMarker = ClipboardRequestSchema.PROTOCOL_MARKER
        val task =
            "Your previous response could not be parsed by the MaxVibes IDE plugin. Please resend it as a corrected JSON object."
        val reason = details.reasonCode()
        val description = jsonEscape(details.humanDescription())
        val hint = jsonEscape(details.correctionHint())
        val preview = jsonEscape(originalPreview.take(PREVIEW_LENGTH))
        return buildString {
            appendLine("{")
            appendLine("  \"$protocol\": \"$protocolMarker\",")
            appendLine("  \"task\": \"$task\",")
            appendLine("  \"parseError\": {")
            appendLine("    \"reason\": \"$reason\",")
            appendLine("    \"description\": \"$description\",")
            appendLine("    \"hint\": \"$hint\",")
            appendLine("    \"originalPreview\": \"$preview\"")
            appendLine("  },")
            appendLine("  \"expectedFormat\": {")
            appendLine("    \"message\": \"Your explanation here\",")
            appendLine("    \"requestedFiles\": [],")
            appendLine("    \"modifications\": []")
            append("  }\n}")
        }
    }

    // ── Diagnosis ─────────────────────────────────────────────────────

    /**
     * Heuristically analyses [text] to determine the most likely cause of a parse failure.
     * Checks are ordered from most specific to least specific.
     */
    private fun diagnose(text: String): ParseFailureDetails {
        val trimmed = text.trim()

        // Case 1: response starts with a markdown fence — codec should handle this, but
        // if it still failed the JSON inside the block is likely malformed
        val fenceMatch = Regex("^`{3}\\w*").find(trimmed)
        if (fenceMatch != null) {
            return ParseFailureDetails.WrappedInMarkdown(detected = trimmed.take(80))
        }

        // Case 2: no opening brace at all — not JSON
        if (!trimmed.contains('{')) {
            return ParseFailureDetails.NoJsonFound(
                hint = "The response contains no JSON object. Make sure the LLM outputs a raw JSON starting with '{' without any markdown or preamble."
            )
        }

        // Case 3: naive bracket-balance check to detect truncation / encoding issues
        val openCount = trimmed.count { it == '{' }
        val closeCount = trimmed.count { it == '}' }
        if (openCount != closeCount) {
            return ParseFailureDetails.MalformedJson(
                exceptionMessage = "Unbalanced braces: $openCount opening vs $closeCount closing",
                snippet = trimmed.take(200)
            )
        }

        // Case 4: braces balance but JSON is malformed for another reason
        return ParseFailureDetails.MalformedJson(
            exceptionMessage = "JSON structure appears invalid",
            snippet = trimmed.take(200)
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────

    /** Escapes a string for safe embedding inside a JSON string value. */
    private fun jsonEscape(s: String): String =
        s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")

    // ── Constants ────────────────────────────────────────────────────

    companion object {
        /** Maximum number of characters from the raw input to include in the error payload. */
        const val PREVIEW_LENGTH = 300
    }
}

// ── Result types ─────────────────────────────────────────────────────

/** Outcome of [ClipboardResponseValidator.validate]. */
sealed class ValidationResult {
    /** The response was successfully decoded. */
    data class Valid(val response: ClipboardResponse) : ValidationResult()

    /** Parsing failed with a structured diagnosis. */
    data class ParseFailure(val details: ParseFailureDetails) : ValidationResult()

    /** The input was blank or whitespace-only. */
    object EmptyInput : ValidationResult()
}

/** Specific reasons why a clipboard response failed to parse. */
sealed class ParseFailureDetails {

    /** Returns a short machine-readable code for the JSON `reason` field. */
    abstract fun reasonCode(): String

    /** Returns a human-readable description of what went wrong. */
    abstract fun humanDescription(): String

    /** Returns a concise instruction for the LLM explaining how to fix the problem. */
    abstract fun correctionHint(): String

    /** No JSON object was found in the response at all. */
    data class NoJsonFound(val hint: String) : ParseFailureDetails() {
        override fun reasonCode() = "NO_JSON_FOUND"
        override fun humanDescription() = "No JSON object was detected in the response. $hint"
        override fun correctionHint() =
            "Respond with a raw JSON object starting with '{'. Do not include any explanation text outside the JSON."
    }

    /** A JSON-like structure was found but failed to parse (syntax error, truncation, etc.). */
    data class MalformedJson(val exceptionMessage: String, val snippet: String) : ParseFailureDetails() {
        override fun reasonCode() = "MALFORMED_JSON"
        override fun humanDescription() =
            "A JSON object was found but could not be parsed: $exceptionMessage. Snippet: ${snippet.take(120)}"

        override fun correctionHint() =
            "Ensure all strings are properly quoted, all brackets are balanced, and there are no trailing commas."
    }

    /** A required field (e.g. \"message\") is absent from the JSON object. */
    data class MissingRequiredField(val fieldName: String) : ParseFailureDetails() {
        override fun reasonCode() = "MISSING_REQUIRED_FIELD"
        override fun humanDescription() = "The JSON response is missing required field: \"$fieldName\"."
        override fun correctionHint() = "Include at minimum a \"$fieldName\" field in your JSON response."
    }

    /** Response is wrapped in a markdown code fence that the codec could not strip cleanly. */
    data class WrappedInMarkdown(val detected: String) : ParseFailureDetails() {
        override fun reasonCode() = "WRAPPED_IN_MARKDOWN"
        override fun humanDescription() =
            "Response appears to be wrapped in a markdown code fence. Detected: ${detected.take(80)}"

        override fun correctionHint() =
            "Do NOT wrap the JSON in ```json or ``` fences. Respond with a raw JSON object only."
    }
}
