package com.maxvibes.plugin.clipboard

import com.maxvibes.application.port.output.ClipboardPort
import com.maxvibes.application.port.output.InteractionProtocolCodec
import com.maxvibes.domain.model.interaction.ClipboardRequest
import com.maxvibes.domain.model.interaction.InteractionResponse
import com.maxvibes.plugin.service.MaxVibesLogger
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

class ClipboardAdapter(
    private val codec: InteractionProtocolCodec = JsonInteractionProtocolCodec()
) : ClipboardPort {

    // ==================== ClipboardPort ====================

    /**
     * Encodes [request] via [codec] and places the resulting JSON string
     * on the system clipboard.
     *
     * @return true if the clipboard write succeeded; false on any exception.
     */
    override fun copyRequestToClipboard(request: ClipboardRequest): Boolean {
        return try {
            // Serialize request to JSON via codec, then push to AWT clipboard
            val jsonText = codec.encode(request)
            val selection = StringSelection(jsonText)
            Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, null)

            MaxVibesLogger.debug(
                "Clipboard", "copied request", mapOf(
                    "currentMessage" to request.currentMessage.take(60),
                    "freshFiles" to request.freshFiles.size,
                    "planOnly" to request.planOnly
                )
            )
            true
        } catch (e: Exception) {
            MaxVibesLogger.error("Clipboard", "copyRequestToClipboard failed", e)
            false
        }
    }

    /**
     * Places a pre-built [text] string directly on the system clipboard.
     *
     * Used for diagnostic error JSON payloads — bypasses codec encoding.
     *
     * @return true if the clipboard write succeeded; false on any exception.
     */
    override fun copyRawText(text: String): Boolean {
        return try {
            val selection = StringSelection(text)
            Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, null)
            MaxVibesLogger.debug("Clipboard", "copyRawText", mapOf("length" to text.length))
            true
        } catch (e: Exception) {
            MaxVibesLogger.error("Clipboard", "copyRawText failed", e)
            false
        }
    }

    /**
     * Decodes [rawText] (raw LLM output) into a [InteractionResponse] via [codec].
     *
     * Logs a debug entry on success and a warning when the codec returns null
     * (i.e. the input contained no recognisable JSON).
     *
     * @return parsed response, or null if the text is not a valid protocol reply.
     */
    override fun parseResponse(rawText: String): InteractionResponse? {
        val result = codec.decode(rawText)

        // Log outcome — success path includes message/files/mods counts
        if (result != null) {
            MaxVibesLogger.debug(
                "Clipboard", "parsed response", mapOf(
                    "msg" to result.message.take(60),
                    "files" to result.requestedFiles.size,
                    "mods" to result.modifications.size
                )
            )
        } else {
            MaxVibesLogger.warn(
                "Clipboard", "failed to parse response",
                data = mapOf("preview" to rawText.take(120))
            )
        }

        return result
    }
}
