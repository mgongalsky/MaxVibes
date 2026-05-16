package com.maxvibes.domain.model.interaction

/**
 * Live-progress event emitted by the Claude Code transport during a single send.
 *
 * Distinct from [ClipboardSessionStatus] — that one is **persisted** state of the
 * dialog session; this one is **transient** per-send progress info, surfaced to UI
 * so the user sees that the process is alive and working.
 *
 * Lifecycle: emitted by [com.maxvibes.application.port.output.ClaudeCodePort]
 * implementations during [com.maxvibes.application.port.output.ClaudeCodePort.send]
 * via the `onActivity` callback. Cleared by the service when the send completes
 * (success or failure). Never persisted to disk — surviving IDE restart is not
 * required and would be misleading anyway.
 *
 * @property startedAtMs wall-clock timestamp ([System.currentTimeMillis]) of when
 *           the current send started. UI uses this to display elapsed time without
 *           needing to track start time separately.
 */
sealed class ClaudeCodeActivity {

    abstract val startedAtMs: Long

    /**
     * The Claude Code process has emitted its `system/init` event for this turn —
     * the session is known to be alive and ready. Emitted at most once per send.
     *
     * @param sessionId the session id reported by claude in the init event.
     *        May be null when claude does not re-emit it (e.g. on `--resume` runs).
     */
    data class Started(
        override val startedAtMs: Long,
        val sessionId: String?
    ) : ClaudeCodeActivity()

    /**
     * Claude streamed a chunk of assistant text. May be emitted many times per send.
     *
     * @param previewText raw text chunk as observed in the stream. UI is responsible
     *        for sanitising/truncating it before display — the domain object does not
     *        impose any size limit because we want to remain faithful to the source.
     */
    data class Thinking(
        override val startedAtMs: Long,
        val previewText: String
    ) : ClaudeCodeActivity()

    /**
     * Claude reported a rate-limit notice during the turn. The turn is still active —
     * this is informational only.
     *
     * @param info short human-readable detail extracted from the rate_limit_event.
     */
    data class RateLimit(
        override val startedAtMs: Long,
        val info: String
    ) : ClaudeCodeActivity()
}
