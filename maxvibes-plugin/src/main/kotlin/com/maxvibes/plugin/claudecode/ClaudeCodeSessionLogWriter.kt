package com.maxvibes.plugin.claudecode

import com.maxvibes.application.port.output.ClaudeCodeSessionLogPort
import com.maxvibes.plugin.service.MaxVibesLogger
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Plugin-layer implementation of [ClaudeCodeSessionLogPort].
 *
 * Writes a dedicated, non-truncated transcript of every Claude Code dialog to
 *   `<project>/.maxvibes/logs/claude-code/<chatSessionId>.log`
 *
 * Format: one record per line, prefixed with a millisecond timestamp and a
 * direction marker:
 *   `[HH:mm:ss.SSS] --` lifecycle / meta event
 *   `[HH:mm:ss.SSS] >>` full stdin line sent to the claude process
 *   `[HH:mm:ss.SSS] <<` full stdout line received from the claude process
 *   `[HH:mm:ss.SSS] !!` stderr line from the claude process
 *
 * Unlike [MaxVibesLogger] (shared maxvibes.log, previews capped at ~500 chars),
 * this transcript keeps FULL content — including the complete command line with
 * the system prompt and every raw stream-json line before parsing. That is the
 * point: when the mode misbehaves, the transcript alone must be enough to
 * replay and diagnose the exchange.
 *
 * Concurrency: single active dialog at a time (see port contract). All writes
 * are serialized on [lock]; callers may write from any thread (transport IO
 * dispatcher, stderr collector thread, EDT).
 *
 * Failure policy: logging must never break the transport — every I/O error is
 * swallowed after a single warning to [MaxVibesLogger].
 *
 * No rotation in v1: files are per-dialog and debugging-oriented; revisit if
 * transcripts grow unwieldy.
 */
class ClaudeCodeSessionLogWriter(
    projectBasePath: String
) : ClaudeCodeSessionLogPort {

    private companion object {
        private const val TAG = "ClaudeCodeSessionLog"

        /** Fallback transcript for raw I/O that arrives before any [begin]. */
        private const val NO_SESSION_FILE = "_no-session"
    }

    private val logDir = File(projectBasePath, ".maxvibes/logs/claude-code")
    private val lock = Any()
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private val headerFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    private var currentSessionId: String? = null
    private var writer: PrintWriter? = null
    private var warnedWriteFailure = false

    override fun begin(chatSessionId: String) {
        synchronized(lock) {
            if (chatSessionId == currentSessionId && writer != null) {
                // Same dialog, new turn — visual separator only.
                writeLocked("--", "---- new turn ----")
                return
            }
            closeLocked()
            currentSessionId = chatSessionId
            openLocked(chatSessionId)
            writeLocked(
                "--",
                "======== DIALOG $chatSessionId ======== " +
                        "(${LocalDateTime.now().format(headerFormatter)}, " +
                        "plugin log session ${MaxVibesLogger.sessionId})"
            )
        }
    }

    override fun event(text: String, data: Map<String, Any?>?) {
        synchronized(lock) {
            ensureOpenLocked()
            val suffix = if (data.isNullOrEmpty()) "" else {
                " | " + data.entries.joinToString(", ") { (k, v) -> "$k=${v ?: "null"}" }
            }
            writeLocked("--", text + suffix)
        }
    }

    override fun outbound(line: String) = write(">>", line)

    override fun inbound(line: String) = write("<<", line)

    override fun stderr(line: String) = write("!!", line)

    override fun logFilePath(chatSessionId: String): String? {
        val f = File(logDir, "$chatSessionId.log")
        return if (f.exists()) f.absolutePath else null
    }

    /** Closes the current writer. Safe to call multiple times / when nothing is open. */
    fun close() {
        synchronized(lock) { closeLocked() }
    }

    private fun write(marker: String, text: String) {
        synchronized(lock) {
            ensureOpenLocked()
            writeLocked(marker, text)
        }
    }

    private fun ensureOpenLocked() {
        if (writer == null) {
            // Raw I/O arrived before any begin() — route to a fallback transcript
            // rather than dropping data.
            if (currentSessionId == null) currentSessionId = NO_SESSION_FILE
            openLocked(currentSessionId!!)
        }
    }

    private fun openLocked(sessionId: String) {
        try {
            logDir.mkdirs()
            val file = File(logDir, "$sessionId.log")
            writer = PrintWriter(
                OutputStreamWriter(FileOutputStream(file, true), StandardCharsets.UTF_8),
                true
            )
            warnedWriteFailure = false
        } catch (e: Exception) {
            writer = null
            if (!warnedWriteFailure) {
                warnedWriteFailure = true
                MaxVibesLogger.warn(TAG, "cannot open dialog log", ex = e, data = mapOf("sessionId" to sessionId))
            }
        }
    }

    private fun writeLocked(marker: String, text: String) {
        val w = writer ?: return
        try {
            w.println("[${LocalDateTime.now().format(timeFormatter)}] $marker $text")
        } catch (e: Exception) {
            if (!warnedWriteFailure) {
                warnedWriteFailure = true
                MaxVibesLogger.warn(TAG, "dialog log write failed", ex = e)
            }
        }
    }

    private fun closeLocked() {
        try {
            writer?.close()
        } catch (_: Exception) {
        }
        writer = null
    }
}
