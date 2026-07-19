package com.maxvibes.application.port.output

/**
 * Port for the per-dialog verbose transcript of the Claude Code mode.
 *
 * Motivation: [LoggerPort] / MaxVibesLogger serve the whole plugin and truncate
 * long payloads. Debugging the Claude Code transport needs the opposite — the
 * FULL untruncated exchange (spawn command line, every raw stream-json line in
 * both directions, stderr, lifecycle events) for one dialog in one file.
 *
 * Contract:
 *  - Exactly one dialog is "active" at a time. [begin] switches the active
 *    dialog; all subsequent [event]/[outbound]/[inbound]/[stderr] calls append
 *    to that dialog's transcript. This matches the transport's own contract
 *    (sends are serialized via a mutex; the interaction service is
 *    single-threaded by contract).
 *  - The service layer calls [begin] at every entry point (user input, approve)
 *    BEFORE any transport call, so the adapter never needs to know the chat
 *    session id.
 *  - Implementations must be thread-safe (writes arrive from IO dispatcher
 *    threads and the stderr collector thread) and must never throw into callers.
 *  - All methods must be cheap enough to call from the stdout read loop.
 */
interface ClaudeCodeSessionLogPort {

    /**
     * Marks [chatSessionId] as the active dialog. Idempotent for the same id
     * (implementations may emit a turn separator). Called by the interaction
     * service at each entry point before touching the transport.
     */
    fun begin(chatSessionId: String)

    /** Lifecycle / meta record: spawn, shutdown, timeout, parse fallback, etc. */
    fun event(text: String, data: Map<String, Any?>? = null)

    /** Full raw line written to the claude process stdin. */
    fun outbound(line: String)

    /** Full raw line read from the claude process stdout. */
    fun inbound(line: String)

    /** Line read from the claude process stderr. */
    fun stderr(line: String)

    /**
     * Absolute path of the transcript for [chatSessionId], or null if no
     * transcript exists yet. Used by the UI "open log" affordance.
     */
    fun logFilePath(chatSessionId: String): String?
}
