package com.maxvibes.application.port.output

/** Aggregated per-turn statistics reported by the agent transport in its terminal event. */
data class SessionStats(
    val costUsd: Double,
    val numTurns: Int,
    val durationMs: Long,
    val inputTokens: Int,
    val outputTokens: Int
)

/**
 * CLI-independent live-stream event emitted by the agent transport during one turn.
 * The adapter maps raw stream-json lines into these; the UI renders them.
 * No IDE or CLI types may appear here.
 */
sealed interface AgentStreamEvent {

    /**
     * Subscription rate-limit telemetry pushed by the agent during a turn (one event
     * per window). Forwarded for the limits indicator; the live feed must NOT render
     * these as notices - they fire on every turn. [kind] is the agent's own window
     * identifier and doubles as the merge key; [windowMinutes] is the window length
     * when the agent reports it (Codex does; Claude only names its windows).
     * [utilizationPct] is null when the agent omits the field; [status] passes through
     * verbatim ("allowed" observed; "allowed_warning" / "rejected" per CLI strings).
     */
    data class RateLimitUpdate(
        val kind: String,
        val status: String,
        val utilizationPct: Int?,
        val resetsAtEpochSec: Long?,
        val windowMinutes: Int? = null
    ) : AgentStreamEvent

    data class SessionStarted(val sessionId: String, val model: String) : AgentStreamEvent

    /** Incremental text chunk. [thinking] separates reasoning from user-facing narration. */
    data class NarrationDelta(val messageId: String, val text: String, val thinking: Boolean) : AgentStreamEvent

    /**
     * Authoritative full message text. REPLACES any buffer accumulated from
     * [NarrationDelta]s with the same [messageId] (self-healing against lost deltas;
     * also the only narration source when the CLI lacks partial-message support).
     */
    data class NarrationMessage(val messageId: String, val text: String, val thinking: Boolean) : AgentStreamEvent

    data class ToolStarted(val toolUseId: String, val name: String, val summary: String) : AgentStreamEvent

    data class ToolFinished(val toolUseId: String, val ok: Boolean, val summary: String?) : AgentStreamEvent

    /** Out-of-band notice: api_retry/rate-limit, history compaction, stderr. */
    data class Notice(val text: String) : AgentStreamEvent

    /**
     * Cumulative hidden-thinking token estimate for the current turn. The thinking
     * text is redacted server-side, so this counter is the only visible reasoning
     * signal; rendered in the live header, never as a feed notice (fires every ~1.5s).
     */
    data class ThinkingProgress(val estimatedTokens: Int) : AgentStreamEvent

    /** Turn finished. [finalText] is the ONLY text that feeds the channel-protocol parser. */
    data class Completed(val finalText: String, val stats: SessionStats) : AgentStreamEvent

    data class Failed(val reason: String, val partialText: String?) : AgentStreamEvent
}

/**
 * Sink the transport emits [AgentStreamEvent]s into. Implementations must be fast,
 * non-blocking and must never throw into the transport reader thread.
 */
fun interface AgentStreamSink {
    fun emit(event: AgentStreamEvent)
}