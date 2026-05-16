package com.maxvibes.plugin.claudecode

import com.intellij.execution.configurations.GeneralCommandLine
import com.maxvibes.application.port.output.ClaudeCodeError
import com.maxvibes.application.port.output.ClaudeCodePort
import com.maxvibes.application.port.output.ClaudeCodeSendResult
import com.maxvibes.application.port.output.InteractionProtocolCodec
import com.maxvibes.domain.model.interaction.ClipboardRequest
import com.maxvibes.plugin.clipboard.JsonInteractionProtocolCodec
import com.maxvibes.plugin.service.MaxVibesLogger
import com.maxvibes.plugin.settings.MaxVibesSettings
import com.maxvibes.shared.result.Result
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.BufferedWriter
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import com.maxvibes.domain.model.interaction.ClaudeCodeActivity

/**
 * Plugin-layer implementation of [ClaudeCodePort].
 *
 * Lifecycle:
 *  - Lazy-starts on the first [ensureStarted] / [send].
 *  - Held alive across multiple sends within one chat session.
 *  - [shutdown] destroys the process. Safe to call multiple times.
 *
 * Concurrency: all stdin/stdout access is serialized via [sendMutex].
 *
 * COMMAND (May 2026, against `claude --help`):
 *   claude -p --input-format stream-json --output-format stream-json --verbose
 *          [--resume <session-uuid>]
 *          [<extra args from settings>]
 *
 * Note: `-p` (--print) is REQUIRED for stream-json input/output to work.
 * `--verbose` is required so that stream-json emits all event types (system/init,
 * assistant, result), not just the terminal result.
 *
 * Diagnostics: every meaningful step in this adapter writes a JSON line to
 * `.maxvibes/logs/maxvibes.log` via [MaxVibesLogger] under the `ClaudeCode`
 * tag. When something hangs or fails, the log makes it explicit whether the
 * problem is spawn / stdin write / stdout read / parse — without it, the UI
 * only shows a generic "transport error" message.
 *
 * NOTE: stream-JSON line shapes (system/init, assistant, result) are documented
 * in [StreamJsonProtocol] based on observed claude-code 2.1.x behaviour.
 * If a future claude version changes the schema, update [StreamJsonProtocol]
 * — this adapter only cares about the field accessors it exposes.
 */
class ClaudeCodeProcessAdapter(
    private val settings: MaxVibesSettings,
    private val codec: InteractionProtocolCodec = JsonInteractionProtocolCodec(),
    private val scope: CoroutineScope
) : ClaudeCodePort {

    private companion object {
        private const val TAG = "ClaudeCode"

        /** Max characters of a stdout/stderr line to include in log entries. */
        private const val LOG_LINE_PREVIEW_MAX = 500

        /** Max characters of request JSON to include in log entries. */
        private const val LOG_REQUEST_PREVIEW_MAX = 300

        /**
         * Grace period after spawn before we believe the process "really" started.
         * Long enough that an immediate crash (bad path, missing deps) is observable
         * via `!isAlive`; short enough that healthy startups still feel snappy.
         */
        private const val SPAWN_GRACE_MS = 200L
    }

    private var process: Process? = null
    private var stdin: BufferedWriter? = null
    private var stdout: BufferedReader? = null
    private var stderrCollector: Job? = null
    private val stderrBuffer = StringBuilder()
    private val sendMutex = Mutex()

    /**
     * Snapshot of stderr from the most recent failed start, retained so callers
     * can surface it to the UI even after the process is torn down. Cleared on
     * the next successful start.
     */
    @Volatile
    var lastStderrSnapshot: String = ""
        private set

    override fun isAvailable(): Boolean {
        // Run `claude --version` with a short timeout — fast enough to call before every start.
        return try {
            val cmd = GeneralCommandLine(settings.claudeCodePath, "--version").apply {
                charset = StandardCharsets.UTF_8
            }
            val proc = cmd.createProcess()
            val finished = proc.waitFor(5, TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                MaxVibesLogger.warn(
                    TAG, "isAvailable: --version timed out",
                    data = mapOf("path" to settings.claudeCodePath)
                )
                false
            } else {
                val ok = proc.exitValue() == 0
                MaxVibesLogger.info(
                    TAG, "isAvailable",
                    mapOf(
                        "path" to settings.claudeCodePath,
                        "exit" to proc.exitValue(),
                        "ok" to ok
                    )
                )
                ok
            }
        } catch (e: Exception) {
            MaxVibesLogger.warn(
                TAG, "isAvailable threw", ex = e,
                data = mapOf("path" to settings.claudeCodePath)
            )
            false
        }
    }

    override suspend fun ensureStarted(
        resumeSessionId: String?,
        systemPrompt: String?
    ): Result<Unit, ClaudeCodeError> =
        sendMutex.withLock {
            if (process?.isAlive == true) {
                MaxVibesLogger.info(
                    TAG, "ensureStarted: already alive",
                    mapOf(
                        "resume" to (resumeSessionId ?: "null"),
                        "systemPromptIgnored" to (systemPrompt != null)
                    )
                )
                return Result.Success(Unit)
            }

            if (!isAvailable()) {
                MaxVibesLogger.warn(
                    TAG, "ensureStarted: binary not found",
                    data = mapOf("path" to settings.claudeCodePath)
                )
                return Result.Failure(ClaudeCodeError.BinaryNotFound)
            }

            val baseArgs = mutableListOf(
                "-p",
                "--input-format", "stream-json",
                "--output-format", "stream-json",
                "--verbose"
            )
            // System prompt is passed via CLI flag (Strategy B): keeps the JSON
            // user-event payload free of large prompt-like text that would otherwise
            // trigger Claude Code's prompt-injection classifier.
            val hasSystemPrompt = !systemPrompt.isNullOrBlank()
            if (hasSystemPrompt) {
                baseArgs += "--append-system-prompt"
                baseArgs += systemPrompt!!
            }
            if (resumeSessionId != null) {
                baseArgs += "--resume"
                baseArgs += resumeSessionId
            }
            val extraArgs = settings.claudeCodeExtraArgs
                .split(' ')
                .filter { it.isNotBlank() }
            val allArgs = baseArgs + extraArgs

            val cmd = GeneralCommandLine(settings.claudeCodePath).apply {
                charset = StandardCharsets.UTF_8
                addParameters(*allArgs.toTypedArray())
            }

            // Log a redacted view of args — full system prompt is large and would
            // dominate the log; we only show its length + preview.
            val argsForLog = allArgs.toMutableList().apply {
                val idx = indexOf("--append-system-prompt")
                if (idx >= 0 && idx + 1 < size) {
                    val sp = this[idx + 1]
                    this[idx + 1] = "<systemPrompt len=${sp.length} preview=${sp.take(120).replace('\n', ' ')}>"
                }
            }
            MaxVibesLogger.info(
                TAG, "ensureStarted: spawning",
                mapOf(
                    "path" to settings.claudeCodePath,
                    "args" to argsForLog.joinToString(" "),
                    "resume" to (resumeSessionId ?: "null"),
                    "hasSystemPrompt" to hasSystemPrompt,
                    "systemPromptLen" to (systemPrompt?.length ?: 0)
                )
            )

            val proc = try {
                cmd.createProcess()
            } catch (e: Exception) {
                MaxVibesLogger.error(
                    TAG, "ensureStarted: createProcess threw", ex = e,
                    data = mapOf("path" to settings.claudeCodePath, "args" to argsForLog.joinToString(" "))
                )
                return Result.Failure(
                    ClaudeCodeError.Crashed("Could not spawn process: ${e.message}")
                )
            }

            process = proc
            stdin = proc.outputStream.bufferedWriter(StandardCharsets.UTF_8)
            stdout = proc.inputStream.bufferedReader(StandardCharsets.UTF_8)
            synchronized(stderrBuffer) { stderrBuffer.setLength(0) }
            lastStderrSnapshot = ""

            // stderr is collected on a project-scoped IO coroutine. Each line is logged
            // immediately so we can correlate stderr noise with main-flow events, AND
            // appended to a buffer so we can include it in error results.
            stderrCollector = scope.launch(Dispatchers.IO) {
                try {
                    proc.errorStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                        lines.forEach { line ->
                            synchronized(stderrBuffer) { stderrBuffer.appendLine(line) }
                            MaxVibesLogger.warn(
                                TAG, "stderr",
                                data = mapOf("line" to line.take(LOG_LINE_PREVIEW_MAX))
                            )
                        }
                    }
                } catch (e: Exception) {
                    MaxVibesLogger.warn(TAG, "stderr collector threw", ex = e)
                }
            }

            // Brief grace period — long enough to catch immediate crashes (bad binary path,
            // missing deps), short enough not to feel like a hang. We intentionally do NOT
            // wait for system/init on stdout here — claude in stream-json mode may block on
            // stdin until the first user event, which would deadlock us. The init line is
            // picked up by the first send()'s read loop instead.
            delay(SPAWN_GRACE_MS)

            if (!proc.isAlive) {
                val err = synchronized(stderrBuffer) { stderrBuffer.toString() }
                lastStderrSnapshot = err
                val exitCode = runCatching { proc.exitValue() }.getOrDefault(-1)
                MaxVibesLogger.error(
                    TAG, "ensureStarted: process died during grace period",
                    data = mapOf(
                        "exit" to exitCode,
                        "stderr" to err.take(LOG_LINE_PREVIEW_MAX),
                        "resume" to (resumeSessionId ?: "null")
                    )
                )
                shutdown(reason = "died during spawn grace")
                return if (resumeSessionId != null) {
                    Result.Failure(ClaudeCodeError.ResumeFailed(resumeSessionId, err))
                } else {
                    Result.Failure(ClaudeCodeError.ProcessFailed(exitCode, err))
                }
            }

            MaxVibesLogger.info(
                TAG, "ensureStarted: alive after grace",
                mapOf(
                    "pid" to runCatching { proc.pid() }.getOrDefault(-1L),
                    "resume" to (resumeSessionId ?: "null")
                )
            )
            return Result.Success(Unit)
        }

    override suspend fun send(
        request: ClipboardRequest,
        onActivity: (ClaudeCodeActivity) -> Unit
    ): Result<ClaudeCodeSendResult, ClaudeCodeError> =
        sendMutex.withLock {
            val proc = process
            if (proc == null) {
                MaxVibesLogger.warn(TAG, "send: process not started")
                return Result.Failure(ClaudeCodeError.Crashed("Process not started"))
            }
            if (!proc.isAlive) {
                val err = synchronized(stderrBuffer) { stderrBuffer.toString() }
                lastStderrSnapshot = err
                MaxVibesLogger.warn(
                    TAG, "send: process died before write",
                    data = mapOf(
                        "exit" to runCatching { proc.exitValue() }.getOrDefault(-1),
                        "stderr" to err.take(LOG_LINE_PREVIEW_MAX)
                    )
                )
                return Result.Failure(ClaudeCodeError.Crashed("Process died"))
            }

            val sendStartedAt = System.currentTimeMillis()

            // Safe wrapper for the activity callback — UI/listener exceptions must not
            // break the transport. Logged and swallowed.
            fun emit(activity: ClaudeCodeActivity) {
                try {
                    onActivity(activity)
                } catch (e: Exception) {
                    MaxVibesLogger.warn(TAG, "onActivity callback threw — ignoring", ex = e)
                }
            }

            // 1. Encode request through the shared codec, then wrap in stream-json.
            //    omitMetaFields = true strips `_protocol`, `_responseFormat` and `systemInstruction`
            //    from the payload so it doesn't look like a prompt injection to Claude Code's
            //    classifier. The same information is delivered out-of-band via
            //    --append-system-prompt at process spawn (see ensureStarted).
            val requestJson = codec.encode(request, omitMetaFields = true)
            val streamJsonLine = StreamJsonProtocol.encodeUserEvent(requestJson)

            MaxVibesLogger.info(
                TAG, "send: encoded",
                mapOf(
                    "requestJsonLen" to requestJson.length,
                    "streamLineLen" to streamJsonLine.length,
                    "requestPreview" to requestJson.take(LOG_REQUEST_PREVIEW_MAX)
                )
            )

            // 2. Write to stdin.
            try {
                withContext(Dispatchers.IO) {
                    val w = stdin ?: error("stdin not initialized")
                    w.write(streamJsonLine)
                    w.newLine()
                    w.flush()
                }
                MaxVibesLogger.info(TAG, "send: stdin flushed")
            } catch (e: Exception) {
                MaxVibesLogger.error(TAG, "send: stdin write failed", ex = e)
                return Result.Failure(
                    ClaudeCodeError.Crashed("stdin write failed: ${e.message}")
                )
            }

            // 3. Read stdout until isTurnEnd. Collect assistant text. Capture session id.
            //    runInterruptible lets withTimeoutOrNull actually interrupt the blocking
            //    readLine() — without it, only the coroutine is cancelled while the
            //    underlying thread stays stuck on I/O.
            val timeoutMs = settings.claudeCodeReadTimeoutSec.toLong() * 1000
            val accumulated = StringBuilder()
            var observedSessionId: String? = null
            var linesRead = 0
            var sawTurnEnd = false

            val readResult = withTimeoutOrNull(timeoutMs) {
                runInterruptible(Dispatchers.IO) {
                    val r = stdout ?: error("stdout not initialized")
                    while (true) {
                        val line = r.readLine() ?: break
                        linesRead++

                        val type = peekType(line)
                        MaxVibesLogger.debug(
                            TAG, "stdout line",
                            mapOf(
                                "n" to linesRead,
                                "type" to (type ?: "unknown"),
                                "len" to line.length,
                                "preview" to line.take(LOG_LINE_PREVIEW_MAX)
                            )
                        )

                        StreamJsonProtocol.extractSessionId(line)?.let {
                            observedSessionId = it
                            MaxVibesLogger.info(TAG, "session id observed", mapOf("sessionId" to it))
                            emit(ClaudeCodeActivity.Started(sendStartedAt, it))
                        }
                        // Live-only signals (thinking + tool_use) — surface as Thinking events
                        // so the bubble updates while the model is working through chain-of-thought
                        // or attempting tool calls. NOT accumulated into the final assistant text;
                        // that comes strictly from extractAssistantText below (text content blocks).
                        StreamJsonProtocol.extractThinkingPreview(line)?.let { thought ->
                            MaxVibesLogger.debug(
                                TAG, "thinking preview",
                                mapOf("len" to thought.length, "preview" to thought.take(LOG_LINE_PREVIEW_MAX))
                            )
                            emit(ClaudeCodeActivity.Thinking(sendStartedAt, "\uD83D\uDCAD $thought"))
                        }
                        StreamJsonProtocol.extractToolUseName(line)?.let { toolName ->
                            MaxVibesLogger.debug(TAG, "tool_use observed", mapOf("name" to toolName))
                            emit(ClaudeCodeActivity.Thinking(sendStartedAt, "\uD83D\uDD27 using $toolName"))
                        }
                        StreamJsonProtocol.extractAssistantText(line)?.let { txt ->
                            accumulated.append(txt)
                            MaxVibesLogger.debug(
                                TAG, "assistant chunk",
                                mapOf("chunkLen" to txt.length, "totalLen" to accumulated.length)
                            )
                            emit(ClaudeCodeActivity.Thinking(sendStartedAt, txt))
                        }
                        StreamJsonProtocol.extractRateLimitInfo(line)?.let { info ->
                            MaxVibesLogger.info(TAG, "rate limit", mapOf("info" to info))
                            emit(ClaudeCodeActivity.RateLimit(sendStartedAt, info))
                        }
                        if (StreamJsonProtocol.isTurnEnd(line)) {
                            sawTurnEnd = true
                            MaxVibesLogger.info(
                                TAG, "turn end",
                                mapOf("linesRead" to linesRead, "assistantLen" to accumulated.length)
                            )
                            break
                        }
                    }
                }
            }

            val elapsedMs = System.currentTimeMillis() - sendStartedAt

            if (readResult == null) {
                val err = synchronized(stderrBuffer) { stderrBuffer.toString() }
                MaxVibesLogger.warn(
                    TAG, "send: read timeout",
                    data = mapOf(
                        "timeoutMs" to timeoutMs,
                        "linesRead" to linesRead,
                        "sawTurnEnd" to sawTurnEnd,
                        "assistantLen" to accumulated.length,
                        "alive" to proc.isAlive,
                        "stderr" to err.take(LOG_LINE_PREVIEW_MAX)
                    )
                )
                return Result.Failure(ClaudeCodeError.Timeout)
            }

            if (!sawTurnEnd) {
                // readLine returned null without us seeing a result event — process closed
                // its stdout. Treat as crash; surface stderr if any.
                val err = synchronized(stderrBuffer) { stderrBuffer.toString() }
                lastStderrSnapshot = err
                MaxVibesLogger.warn(
                    TAG, "send: stdout closed without turn end",
                    data = mapOf(
                        "linesRead" to linesRead,
                        "assistantLen" to accumulated.length,
                        "alive" to proc.isAlive,
                        "exit" to runCatching { proc.exitValue() }.getOrDefault(-1),
                        "stderr" to err.take(LOG_LINE_PREVIEW_MAX),
                        "elapsedMs" to elapsedMs
                    )
                )
                return Result.Failure(
                    ClaudeCodeError.Crashed("stdout closed before turn end (stderr: ${err.take(200)})")
                )
            }

            // 4. Decode the accumulated assistant text into an InteractionResponse.
            val responseText = accumulated.toString()
            MaxVibesLogger.info(
                TAG, "send: decoding response",
                mapOf(
                    "assistantLen" to responseText.length,
                    "preview" to responseText.take(LOG_REQUEST_PREVIEW_MAX),
                    "elapsedMs" to elapsedMs
                )
            )
            val response = codec.decode(responseText)
            if (response == null) {
                MaxVibesLogger.warn(
                    TAG, "send: codec.decode returned null",
                    data = mapOf(
                        "assistantLen" to responseText.length,
                        "preview" to responseText.take(LOG_LINE_PREVIEW_MAX)
                    )
                )
                return Result.Failure(
                    ClaudeCodeError.ParseFailed(
                        "codec.decode returned null. Raw assistant text length=${responseText.length}"
                    )
                )
            }

            MaxVibesLogger.info(
                TAG, "send: done",
                mapOf(
                    "elapsedMs" to elapsedMs,
                    "linesRead" to linesRead,
                    "assistantLen" to responseText.length,
                    "sessionId" to (observedSessionId ?: "null")
                )
            )
            return Result.Success(ClaudeCodeSendResult(response, observedSessionId))
        }

    override fun shutdown() {
        shutdown(reason = "explicit")
    }

    private fun shutdown(reason: String) {
        val proc = process
        if (proc != null) {
            MaxVibesLogger.info(
                TAG, "shutdown",
                mapOf(
                    "reason" to reason,
                    "alive" to proc.isAlive,
                    "exit" to runCatching { if (!proc.isAlive) proc.exitValue() else null }.getOrNull()
                )
            )
            runCatching { stdin?.close() }
            runCatching { stdout?.close() }
            runCatching { stderrCollector?.cancel() }
            runCatching { proc.destroyForcibly() }
            // Wait briefly so the OS reclaims the PID before we drop our refs.
            runCatching { proc.waitFor(2, TimeUnit.SECONDS) }
        }
        process = null
        stdin = null
        stdout = null
        stderrCollector = null
        // Note: stderrBuffer is NOT cleared here — we keep the snapshot available
        // via [lastStderrSnapshot] for any UI that wants to display it post-mortem.
    }

    /**
     * Extracts the `type` field of a stream-json line without full parsing.
     * Used only for logging — never for control flow. Returns null on parse error.
     */
    private fun peekType(line: String): String? {
        val idx = line.indexOf("\"type\"")
        if (idx < 0) return null
        val colon = line.indexOf(':', startIndex = idx)
        if (colon < 0) return null
        val firstQuote = line.indexOf('"', startIndex = colon + 1)
        if (firstQuote < 0) return null
        val secondQuote = line.indexOf('"', startIndex = firstQuote + 1)
        if (secondQuote < 0) return null
        return line.substring(firstQuote + 1, secondQuote)
    }
}
