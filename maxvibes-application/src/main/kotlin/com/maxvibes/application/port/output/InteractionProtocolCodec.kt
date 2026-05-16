package com.maxvibes.application.port.output

import com.maxvibes.domain.model.interaction.ClipboardRequest
import com.maxvibes.domain.model.interaction.InteractionResponse

/**
 * Port for serializing and deserializing the Clipboard mode JSON protocol.
 *
 * Lives in the application layer — no IDE or system clipboard dependencies.
 * Implemented in the plugin layer by `JsonClipboardProtocolCodec`, which
 * lets unit tests exercise the full encode/decode cycle without an IDE mock.
 *
 * Field name constants are defined in [InteractionRequestSchema].
 */
interface InteractionProtocolCodec {

    /**
     * Encodes a [ClipboardRequest] into a JSON string ready to be placed
     * on the system clipboard for the LLM to consume.
     *
     * The resulting JSON is pretty-printed and (by default) includes the meta-fields
     * [InteractionRequestSchema.META_PROTOCOL] and
     * [InteractionRequestSchema.META_RESPONSE_FORMAT] at the top level.
     *
     * @param request domain request object to serialize
     * @param omitMetaFields when true, suppress `_protocol`, `_responseFormat` and
     *        `systemInstruction` fields from the output. Used by the Claude Code
     *        transport, whose CLI runs a prompt-injection classifier over user-event
     *        payloads — any text that looks like "respond with JSON only" or an
     *        embedded system prompt trips it and causes the model to refuse the
     *        message. In Claude Code mode the role, formatting rules, and tool
     *        restrictions are delivered out-of-band via the CLI's
     *        `--append-system-prompt` flag at process spawn, so they have no business
     *        living inside the per-message user payload as well. Defaults to false to
     *        preserve the existing clipboard-mode contract.
     * @return pretty-printed JSON string
     */
    fun encode(request: ClipboardRequest, omitMetaFields: Boolean = false): String

    /**
     * Decodes a raw LLM response text into a [InteractionResponse].
     *
     * Handles the following response formats:
     * - Raw JSON object (`{...}`)
     * - JSON wrapped in a ` ```json ` … ` ``` ` fenced block
     * - JSON embedded anywhere inside free-form text
     * - Plain text without any JSON — returns `ClipboardResponse(message = rawText)`
     *
     * @param rawText raw string received from the LLM
     * @return parsed [ClipboardResponse], or `null` if the input is blank and contains no JSON
     */
    fun decode(rawText: String): InteractionResponse?
}
