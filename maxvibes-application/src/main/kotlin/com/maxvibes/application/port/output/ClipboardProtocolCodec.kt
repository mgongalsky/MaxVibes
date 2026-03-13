package com.maxvibes.application.port.output

import com.maxvibes.domain.model.interaction.ClipboardRequest
import com.maxvibes.domain.model.interaction.ClipboardResponse

/**
 * Port for serializing and deserializing the Clipboard mode JSON protocol.
 *
 * Lives in the application layer — no IDE or system clipboard dependencies.
 * Implemented in the plugin layer by `JsonClipboardProtocolCodec`, which
 * lets unit tests exercise the full encode/decode cycle without an IDE mock.
 *
 * Field name constants are defined in [ClipboardRequestSchema].
 */
interface ClipboardProtocolCodec {

    /**
     * Encodes a [ClipboardRequest] into a JSON string ready to be placed
     * on the system clipboard for the LLM to consume.
     *
     * The resulting JSON is pretty-printed and includes the meta-fields
     * [ClipboardRequestSchema.META_PROTOCOL] and
     * [ClipboardRequestSchema.META_RESPONSE_FORMAT] at the top level.
     *
     * @param request domain request object to serialize
     * @return pretty-printed JSON string
     */
    fun encode(request: ClipboardRequest): String

    /**
     * Decodes a raw LLM response text into a [ClipboardResponse].
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
    fun decode(rawText: String): ClipboardResponse?
}
