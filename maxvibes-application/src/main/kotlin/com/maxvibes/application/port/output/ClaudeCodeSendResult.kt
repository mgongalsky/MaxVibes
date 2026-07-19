package com.maxvibes.application.port.output

import com.maxvibes.domain.model.interaction.InteractionResponse

/**
 * Result of a successful [ClaudeCodePort.send] call.
 *
 * @property response decoded application-level response
 * @property observedSessionId the `session_id` claude reports in its first
 *           system/init event for this turn. Captured so the caller can
 *           persist it on the [com.maxvibes.domain.model.chat.ChatSession]
 *           and pass it to `--resume` after IDE restarts. May be null if
 *           the system/init event was not observed (e.g. on a resumed run
 *           where claude does not re-emit it — depends on CLI behaviour).
 * @property thinkingText full extended-thinking text accumulated over this turn,
 *           or null when the model emitted no thinking blocks. Multiple blocks
 *           (one per assistant event between tool rounds) are joined with a blank
 *           line. Presentation-only: it is never sent back to the model — the CLI
 *           strips past thinking from context on subsequent turns anyway.
 */
data class ClaudeCodeSendResult(
    val response: InteractionResponse,
    val observedSessionId: String?,
    val thinkingText: String? = null,
    /** Per-turn stats from the CLI result event; null when the result carried none. */
    val stats: SessionStats? = null
)
