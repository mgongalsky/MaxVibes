package com.maxvibes.application.port.output

import com.maxvibes.domain.model.interaction.ClipboardRequest
import com.maxvibes.domain.model.interaction.ClipboardResponse

/**
 * Port for clipboard-based LLM interaction.
 * Implemented in the plugin layer by [ClipboardAdapter].
 */
interface ClipboardPort {

    /**
     * Encodes [request] and places the resulting JSON on the system clipboard.
     * @return true if the clipboard write succeeded
     */
    fun copyRequestToClipboard(request: ClipboardRequest): Boolean

    /**
     * Copies a pre-built [text] string directly to the system clipboard,
     * bypassing the normal [ClipboardRequest] encoding pipeline.
     *
     * Used for diagnostic error JSON payloads produced by [ClipboardResponseValidator]
     * when an LLM response fails to parse.
     * @return true if the clipboard write succeeded
     */
    fun copyRawText(text: String): Boolean

    /**
     * Parses [rawText] (raw LLM output) into a [ClipboardResponse].
     *
     * Supports raw JSON, ```json``` fenced blocks, and JSON embedded in free-form text.
     * @return parsed response, or null if no recognisable JSON was found
     */
    fun parseResponse(rawText: String): ClipboardResponse?
}
