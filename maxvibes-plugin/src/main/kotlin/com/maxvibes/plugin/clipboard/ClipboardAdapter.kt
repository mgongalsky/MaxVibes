package com.maxvibes.plugin.clipboard

import com.maxvibes.application.port.output.ClipboardPort
import com.maxvibes.application.port.output.ClipboardProtocolCodec
import com.maxvibes.domain.model.interaction.ClipboardRequest
import com.maxvibes.domain.model.interaction.ClipboardResponse
import com.maxvibes.plugin.service.MaxVibesLogger
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/**
 * Implementation of [ClipboardPort] for the IntelliJ plugin layer.
 *
 * Thin wrapper: delegates all JSON encode/decode logic to [codec],
 * and is itself responsible only for AWT system-clipboard I/O.
 *
 * Protocol logic lives in [JsonClipboardProtocolCodec] and is unit-tested
 * independently — no IntelliJ mocks required.
 *
 * @param codec codec that handles protocol serialization; defaults to
 *              [JsonClipboardProtocolCodec] so callers can omit it.
 */
class ClipboardAdapter(
    private val codec: ClipboardProtocolCodec = JsonClipboardProtocolCodec()
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
                    "task" to request.task.take(60),
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
     * Decodes [rawText] (raw LLM output) into a [ClipboardResponse] via [codec].
     *
     * Logs a debug entry on success and a warning when the codec returns null
     * (i.e. the input contained no recognisable JSON).
     *
     * @return parsed response, or null if the text is not a valid protocol reply.
     */
    override fun parseResponse(rawText: String): ClipboardResponse? {
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
