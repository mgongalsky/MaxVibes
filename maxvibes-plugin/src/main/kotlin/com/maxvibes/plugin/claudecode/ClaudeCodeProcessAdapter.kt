package com.maxvibes.plugin.claudecode

import com.intellij.execution.configurations.GeneralCommandLine
import com.maxvibes.application.port.output.ClaudeCodeError
import com.maxvibes.application.port.output.ClaudeCodePort
import com.maxvibes.application.port.output.ClaudeCodeSendResult
import com.maxvibes.application.port.output.ClaudeCodeSessionLogPort
import com.maxvibes.application.port.output.InteractionProtocolCodec
import com.maxvibes.domain.model.interaction.ClaudeCodeActivity
import com.maxvibes.domain.model.interaction.ClipboardRequest
import com.maxvibes.domain.model.interaction.InteractionResponse
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
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

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
 *          --disallowed-tools <list>
 *          [--append-system-prompt-file <path>]
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
 * Additionally, when a [ClaudeCodeSessionLogPort] is supplied, the adapter
 * mirrors the FULL untruncated exchange into the per-dialog transcript
 * (`.maxvibes/logs/claude-code/<chatSessionId>.log`): the complete spawn
 * command line (system prompt included), every raw stream-json line in both
 * directions, all stderr, and lifecycle events. MaxVibesLogger stays the
 * truncated overview; the transcript is the source of truth when debugging
 * a misbehaving dialog.
 */
class ClaudeCodeProcessAdapter(
    private val settings: MaxVibesSettings,
    private val codec: InteractionProtocolCodec = JsonInteractionProtocolCodec(),
    private val scope: CoroutineScope,
    /**
     * Working directory for the spawned `claude` process. When non-null and the
     * directory exists, the process is started with this as its CWD. This matters
     * because:
     *  - claude-code's `CLAUDE.md` auto-loader looks in the current directory;
     *  - any tool calls the model attempts (when not disabled) are sandboxed to
     *    the CWD subtree, so launching outside the project means tools can't
     *    reach project files even by absolute path;
     *  - error messages and reset behaviour reference paths relative to CWD,
     *    which is most useful when it's the project root.
     *
     * Defaults to null — process inherits the parent (IDE) CWD, which is usually
     * something useless like the IDE installation's bin directory.
     */
    private val workingDirectory: String? = null,
    /**
     * Optional per-dialog verbose transcript. When non-null, the adapter writes
     * the FULL untruncated exchange to it (see class KDoc). The active dialog is
     * selected upstream by the interaction service via
     * [ClaudeCodeSessionLogPort.begin] — the adapter itself never learns the
     * chat session id. Null (default) disables the transcript entirely, which
     * keeps existing DI call-sites and unit tests compiling unchanged.
     */
    private val sessionLog: ClaudeCodeSessionLogPort? = null
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
                TAG, "isAvail",
            mapOf(
                "path" to settings.claudeCodePath,
            "exit" to proc.exitValue(),
            "ok" to ok
            )
            )
            ok
        }
    } catch (e: Exception)
    {
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
            sessionLog?.event(
                "ensureStarted: already alive",
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
            sessionLog?.event("binary not found", mapOf("path" to settings.claudeCodePath))
            return Result.Failure(ClaudeCodeError.BinaryNotFound)
        }

        val baseArgs = mutableListOf(
            "-p",
        "--input-format", "stream-json",
        "--output-format", "stream-json",
        "--verbose",
        // Disable the built-in tools that the model would otherwise reach for during
        // a normal Claude Code session. In MaxVibes mode the plugin is the sole I/O
        // channel: files arrive via `freshFiles` in the user payload, modifications
        // go out via the `modifications` array in the JSON response.
        //
        // Empirically without this restriction the model wastes 30-180s per turn on
        // bounced tool calls (Glob → "no files", PowerShell → "blocked",
        // Read → "file does not exist"), each costing a full API round-trip.
        //
        // Note: we tried `--allowed-tools ""` (empty allowlist) earlier; on Windows
        // GeneralCommandLine renders the empty arg in a way that claude-code rejects
        // at argv parsing, killing the process with exit 1 before stdout opens.
        // Listing the disallowed tools explicitly avoids that quoting problem.
        "--disallowed-tools",
        "Read,Write,Edit,MultiEdit,NotebookEdit,Bash,Glob,Grep,WebFetch,WebSearch,PowerShell,Task"
        )
        // System prompt is passed via a temp file + CLI flag (Strategy B, file variant):
        // keeps the JSON user-event payload free of large prompt-like text that would
        // otherwise trigger Claude Code's prompt-injection classifier, AND avoids the
        // Windows ARG_MAX / CreateProcess argv-length limit that an inline prompt arg
        // would hit for a multi-KB system prompt.
        val hasSystemPrompt = !systemPrompt.isNullOrBlank()
        var promptFilePath: String? = null
        if (!systemPrompt.isNullOrBlank()) {
            val promptFile = java.nio.file.Files.createTempFile("maxvibes-sysprompt-", ".md")
            java.nio.file.Files.writeString(promptFile, systemPrompt, Charsets.UTF_8)
            promptFile.toFile().deleteOnExit()
            val path = promptFile.toAbsolutePath().toString()
            promptFilePath = path
            baseArgs += "--append-system-prompt-file"
            baseArgs += path
        }
        if (resumeSessionId != null) {
            baseArgs += "--resume"
            baseArgs += resumeSessionId
        }
        val extraArgs = settings.claudeCodeExtraArgs
            .split(' ')
            .filter { it.isNotBlank() }
        val allArgs = baseArgs + extraArgs

        // Resolve working directory. Only set it on the command line if the
        // configured path is non-blank and points to an existing directory —
        // otherwise let the OS pick the default (parent process CWD), since
        // GeneralCommandLine.withWorkDirectory throws on a missing dir.
        val resolvedWorkDir: File? = workingDirectory
            ?.takeIf { it.isNotBlank() }
            ?.let { File(it) }
            ?.takeIf { it.isDirectory }

        val cmd = GeneralCommandLine(settings.claudeCodePath).apply {
            charset = StandardCharsets.UTF_8
            addParameters(*allArgs.toTypedArray())
            if (resolvedWorkDir != null) {
                withWorkDirectory(resolvedWorkDir)
            }
        }

        // System prompt no longer travels inline in argv (it's a temp file now),
        // so there is nothing large to redact — args are safe to log verbatim.
        val argsForLog = allArgs
        MaxVibesLogger.info(
            TAG, "ensureStarted: spawning",
        mapOf(
            "path" to settings.claudeCodePath,
        "args" to argsForLog.joinToString(" "),
        "resume" to (resumeSessionId ?: "null"),
        "hasSystemPrompt" to hasSystemPrompt,
        "promptFile" to (promptFilePath ?: "<none>"),
        "promptLen" to (systemPrompt?.length ?: 0),
        "workDir" to (resolvedWorkDir?.absolutePath ?: "<inherited>"),
        "requestedWorkDir" to (workingDirectory ?: "null")
        )
        )
        // The transcript gets the FULL command line on purpose — including the
        // path to the system-prompt temp file. That is the whole point of the
        // per-dialog log: everything needed to reproduce the invocation lives
        // in one file.
        sessionLog?.event(
            "spawning",
        mapOf(
            "cmd" to settings.claudeCodePath,
        "args" to allArgs.joinToString(" "),
        "resume" to (resumeSessionId ?: "null"),
        "workDir" to (resolvedWorkDir?.absolutePath ?: "<inherited>")
        )
        )

        val proc = try {
            cmd.createProcess()
        } catch (e: Exception) {
            MaxVibesLogger.error(
                TAG, "ensureStarted: createProcess threw", ex = e,
            data = mapOf("path" to settings.claudeCodePath, "args" to argsForLog.joinToString(" "))
            )
            sessionLog?.event(
                "createProcess threw",
            mapOf("ex" to e.javaClass.simpleName, "exMsg" to (e.message ?: ""))
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

        stderrCollector = scope.launch(Dispatchers.IO) {
            try {
                proc.errorStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                    lines.forEach { line ->
                        synchronized(stderrBuffer) { stderrBuffer.appendLine(line) }
                        MaxVibesLogger.warn(
                            TAG, "stderr",
                        data = mapOf("line" to line.take(LOG_LINE_PREVIEW_MAX))
                        )
                        sessionLog?.stderr(line)
                    }
                }
            } catch (e: Exception) {
                MaxVibesLogger.warn(TAG, "stderr collector threw", ex = e)
            }
        }

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
            sessionLog?.event(
                "process died during spawn grace",
            mapOf(
                "exit" to exitCode,
            "resume" to (resumeSessionId ?: "null"),
            "stderr" to err
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
        sessionLog?.event(
            "alive after grace",
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
            sessionLog?.event("send rejected: process not started")
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
            sessionLog?.event(
                "send rejected: process died before write",
            mapOf(
                "exit" to runCatching { proc.exitValue() }.getOrDefault(-1),
            "stderr" to err
            )
            )
            return Result.Failure(ClaudeCodeError.Crashed("Process died"))
        }

        val sendStartedAt = System.currentTimeMillis()

        fun emit(activity: ClaudeCodeActivity) {
            try {
                onActivity(activity)
            } catch (e: Exception) {
                MaxVibesLogger.warn(TAG, "onActivity callback threw — ignoring", ex = e)
            }
        }

        // 1. Encode request through the shared codec, then wrap in stream-json.
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
        // Full outbound line goes into the transcript BEFORE the write attempt,
        // so a failed write still leaves the exact payload visible.
        sessionLog?.outbound(streamJsonLine)

        // 2. Write to stdin.
        try {
            withContext(Dispatchers.IO) {
                val w = stdin ?: error("stdin not initialized")
                w.write(streamJsonLine)
                w.newLine()
                w.flush()
            }
            MaxVibesLogger.info(TAG, "send: stdin flushed")
            sessionLog?.event("stdin flushed")
        } catch (e: Exception) {
            MaxVibesLogger.error(TAG, "send: stdin write failed", ex = e)
            sessionLog?.event(
                "stdin write failed",
            mapOf("ex" to e.javaClass.simpleName, "exMsg" to (e.message ?: ""))
            )
            return Result.Failure(
                ClaudeCodeError.Crashed("stdin write failed: ${e.message}")
            )
        }

        // 3. Read stdout until isTurnEnd.
        val timeoutMs = settings.claudeCodeReadTimeoutSec.toLong() * 1000
        val accumulated = StringBuilder()
        // Full extended-thinking text accumulated over the turn (ThinkingBubble).
        // Separate from the truncated live-activity previews emitted below.
        val thinkingAccumulated = StringBuilder()
        var observedSessionId: String? = null
        var linesRead = 0
        var sawTurnEnd = false

        val readResult = withTimeoutOrNull(timeoutMs) {
            runInterruptible(Dispatchers.IO) {
                val r = stdout ?: error("stdout not initialized")
                while (true) {
                    val line = r.readLine() ?: break
                    linesRead++

                    // Full raw line — before any parsing — into the transcript.
                    sessionLog?.inbound(line)

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
                        sessionLog?.event("claude session_id observed", mapOf("claudeSessionId" to it))
                        emit(ClaudeCodeActivity.Started(sendStartedAt, it))
                    }
                    StreamJsonProtocol.extractThinkingPreview(line)?.let { thought ->
                        MaxVibesLogger.debug(
                            TAG, "thinking preview",
                        mapOf("len" to thought.length, "preview" to thought.take(LOG_LINE_PREVIEW_MAX))
                        )
                        emit(ClaudeCodeActivity.Thinking(sendStartedAt, "\uD83D\uDCAD $thought"))
                    }
                    StreamJsonProtocol.extractThinkingFull(line)?.let { full ->
                        if (thinkingAccumulated.isNotEmpty()) thinkingAccumulated.append("\n\n")
                        thinkingAccumulated.append(full)
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
            sessionLog?.event(
                "read timeout",
            mapOf(
                "timeoutMs" to timeoutMs,
            "linesRead" to linesRead,
            "sawTurnEnd" to sawTurnEnd,
            "assistantLen" to accumulated.length,
            "alive" to proc.isAlive,
            "stderr" to err
            )
            )
            return Result.Failure(ClaudeCodeError.Timeout)
        }

        if (!sawTurnEnd) {
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
            sessionLog?.event(
                "stdout closed without turn end",
            mapOf(
                "linesRead" to linesRead,
            "assistantLen" to accumulated.length,
            "alive" to proc.isAlive,
            "exit" to runCatching { proc.exitValue() }.getOrDefault(-1),
            "elapsedMs" to elapsedMs,
            "stderr" to err
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
            ?: run {
                if (responseText.isNotBlank()) {
                    MaxVibesLogger.warn(
                        TAG, "send: codec.decode returned null — falling back to plain message",
                    data = mapOf(
                        "assistantLen" to responseText.length,
                    "preview" to responseText.take(LOG_LINE_PREVIEW_MAX)
                    )
                    )
                    sessionLog?.event(
                        "decode failed — falling back to plain message",
                    mapOf("assistantLen" to responseText.length)
                    )
                    InteractionResponse(message = responseText)
                } else {
                    MaxVibesLogger.warn(
                        TAG, "send: codec.decode returned null and accumulated text is blank",
                    data = mapOf("assistantLen" to responseText.length)
                    )
                    sessionLog?.event("decode failed — no usable content")
                    return Result.Failure(
                        ClaudeCodeError.ParseFailed(
                            "Claude Code returned no usable content (assistantLen=${responseText.length})"
                    )
                    )
                }
            }

        // Full chain-of-thought for the turn; null when no thinking blocks arrived.
        val thinkingText = thinkingAccumulated.toString().takeIf { it.isNotBlank() }

        MaxVibesLogger.info(
            TAG, "send: done",
        mapOf(
            "elapsedMs" to elapsedMs,
        "linesRead" to linesRead,
        "assistantLen" to responseText.length,
        "thinkingLen" to (thinkingText?.length ?: 0),
        "sessionId" to (observedSessionId ?: "null")
        )
        )
        sessionLog?.event(
            "turn done",
        mapOf(
            "elapsedMs" to elapsedMs,
        "linesRead" to linesRead,
        "assistantLen" to responseText.length,
        "thinkingLen" to (thinkingText?.length ?: 0),
        "claudeSessionId" to (observedSessionId ?: "null")
        )
        )
        return Result.Success(ClaudeCodeSendResult(response, observedSessionId, thinkingText))
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
        sessionLog?.event(
            "shutdown",
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
        runCatching { proc.waitFor(2, TimeUnit.SECONDS) }
    }
    process = null
    stdin = null
    stdout = null
    stderrCollector = null
}

/**
 * Extracts the `type` field of a stream-json line without full parsing.
 * Used only for logging — never for control flow. Returns null on parse error.
 */
private fun peekType(line: String): String? {
    val idx = line.indexOf("type")
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
