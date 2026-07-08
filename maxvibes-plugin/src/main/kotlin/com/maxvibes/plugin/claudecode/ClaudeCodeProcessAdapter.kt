package com.maxvibes.plugin.claudecode

import com.intellij.execution.configurations.GeneralCommandLine
import com.maxvibes.application.port.output.AgentStreamEvent
import com.maxvibes.application.port.output.AgentStreamSink
import com.maxvibes.application.port.output.ClaudeCodeError
import com.maxvibes.application.port.output.ClaudeCodePort
import com.maxvibes.application.port.output.ClaudeCodeSendResult
import com.maxvibes.application.port.output.ClaudeCodeSessionLogPort
import com.maxvibes.application.port.output.InteractionProtocolCodec
import com.maxvibes.application.port.output.SessionStats
import com.maxvibes.domain.model.interaction.AttachedImage
import com.maxvibes.domain.model.interaction.ClaudeCodeActivity
import com.maxvibes.domain.model.interaction.ClipboardRequest
import com.maxvibes.domain.model.interaction.InteractionResponse
import com.maxvibes.plugin.clipboard.JsonInteractionProtocolCodec
import com.maxvibes.plugin.service.MaxVibesLogger
import com.maxvibes.plugin.settings.MaxVibesSettings
import com.maxvibes.shared.result.Result
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.BufferedWriter
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * Plugin-layer implementation of [ClaudeCodePort] over the stream-json NDJSON protocol.
 *
 * Architecture (live-stream rework):
 *  - A PERSISTENT reader coroutine is started at spawn and lives until the process dies.
 *    Every stdout line goes: raw into the per-dialog transcript (ground truth), then
 *    through [StreamJsonEventParser], then out as [AgentStreamEvent]s via [streamSink].
 *    Continuous draining also keeps the Windows pipe from blocking the CLI.
 *  - [send] only writes the request to stdin and awaits the turn outcome on a
 *    per-turn deferred, which the reader completes on the `result` event / EOF.
 *  - The read timeout is an INACTIVITY threshold: it fires only after
 *    `claudeCodeReadTimeoutSec` seconds with no stdout line at all, so multi-minute
 *    rate-limit pauses do not kill a live turn as long as the CLI keeps emitting.
 *  - [abort] kills the whole process TREE (descendants first) and completes the
 *    in-flight turn with [ClaudeCodeError.Aborted] carrying the partial narration.
 *
 * Command line (verified against claude 2.1.138):
 *   claude -p --input-format stream-json --output-format stream-json --verbose
 *          --include-partial-messages --disallowed-tools <list>
 *          [--append-system-prompt-file <path>] [--resume <uuid>] [<extra args>]
 *
 * The final text fed to the channel-protocol codec is the `result` field of the
 * terminal event, with a self-healing fallback to the accumulated authoritative
 * assistant text when `result` is blank or missing.
 *
 * The legacy onActivity callback is accepted but never invoked - superseded by
 * [streamSink]; the whole ClaudeCodeActivity contour is removed in Set 2.
 *
 * Concurrency: [send]/[ensureStarted] are serialized via [sendMutex]. [abort] and
 * [shutdown] are deliberately lock-free (send holds the mutex while awaiting) and
 * operate on volatile references only.
 */
class ClaudeCodeProcessAdapter(
    private val settings: MaxVibesSettings,
    private val codec: InteractionProtocolCodec = JsonInteractionProtocolCodec(),
    private val scope: CoroutineScope,
    private val workingDirectory: String? = null,
    private val sessionLog: ClaudeCodeSessionLogPort? = null,
    /** Live-event sink (AgentStreamHub in production). Null disables live events entirely. */
    private val streamSink: AgentStreamSink? = null
) : ClaudeCodePort {

    private companion object {
        private const val TAG = "ClaudeCode"
        private const val LOG_LINE_PREVIEW_MAX = 500
        private const val LOG_REQUEST_PREVIEW_MAX = 300
        private const val SPAWN_GRACE_MS = 200L

        /** Poll interval of the inactivity watchdog inside [send]. */
        private const val WATCHDOG_TICK_MS = 1000L
    }

    /** Outcome of one turn, produced by the reader loop (or abort/shutdown/watchdog). */
    private sealed interface TurnOutcome {
        data class Finished(val finalText: String?, val isError: Boolean, val stats: SessionStats) : TurnOutcome
        data class Died(val message: String) : TurnOutcome
        data class Aborted(val partialText: String?) : TurnOutcome
    }

    /** Mutable state of the in-flight turn. Appends come from the reader thread only. */
    private class TurnState(val startedAtMs: Long) {
        val deferred = CompletableDeferred<TurnOutcome>()
        private val text = StringBuilder()
        private val thinking = StringBuilder()

        @Volatile var observedSessionId: String? = null
        @Volatile var lastActivityAtMs: Long = startedAtMs
        @Volatile var linesRead: Int = 0

        fun touch() { lastActivityAtMs = System.currentTimeMillis() }

        @Synchronized fun appendText(s: String) { text.append(s) }
        @Synchronized fun appendThinking(s: String) {
            if (thinking.isNotEmpty()) thinking.append("\n\n")
            thinking.append(s)
        }
        @Synchronized fun snapshotText(): String = text.toString()
        @Synchronized fun snapshotThinking(): String = thinking.toString()
    }

    @Volatile private var process: Process? = null
    @Volatile private var stdin: BufferedWriter? = null
    @Volatile private var readerJob: Job? = null
    @Volatile private var stderrCollector: Job? = null
    @Volatile private var activeTurn: TurnState? = null

    /** Session id from the last system/init seen - fallback when init precedes the turn. */
    @Volatile private var lastKnownSessionId: String? = null

    private val stderrBuffer = StringBuilder()
    private val sendMutex = Mutex()
    private val parser = StreamJsonEventParser()

    @Volatile
    var lastStderrSnapshot: String = ""
        private set

    // ==================== Availability ====================

    override fun isAvailable(): Boolean {
        return try {
            val cmd = GeneralCommandLine(settings.claudeCodePath, "--version").apply {
                charset = StandardCharsets.UTF_8
            }
            val proc = cmd.createProcess()
            val finished = proc.waitFor(5, TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                MaxVibesLogger.warn(TAG, "isAvailable: --version timed out",
                    data = mapOf("path" to settings.claudeCodePath))
                false
            } else {
                val ok = proc.exitValue() == 0
                MaxVibesLogger.info(TAG, "isAvail",
                    mapOf("path" to settings.claudeCodePath, "exit" to proc.exitValue(), "ok" to ok))
                ok
            }
        } catch (e: Exception) {
            MaxVibesLogger.warn(TAG, "isAvailable threw", ex = e,
                data = mapOf("path" to settings.claudeCodePath))
            false
        }
    }

    // ==================== Lifecycle ====================

    override suspend fun ensureStarted(
        resumeSessionId: String?,
        systemPrompt: String?
    ): Result<Unit, ClaudeCodeError> =
        sendMutex.withLock {
            val alive = process
            if (alive?.isAlive == true) {
                MaxVibesLogger.info(TAG, "ensureStarted: already alive",
                    mapOf("resume" to (resumeSessionId ?: "null"),
                        "systemPromptIgnored" to (systemPrompt != null)))
                sessionLog?.event("ensureStarted: already alive",
                    mapOf("resume" to (resumeSessionId ?: "null"),
                        "systemPromptIgnored" to (systemPrompt != null)))
                return Result.Success(Unit)
            }

            if (!isAvailable()) {
                MaxVibesLogger.warn(TAG, "ensureStarted: binary not found",
                    data = mapOf("path" to settings.claudeCodePath))
                sessionLog?.event("binary not found", mapOf("path" to settings.claudeCodePath))
                return Result.Failure(ClaudeCodeError.BinaryNotFound)
            }

            val baseArgs = mutableListOf(
                "-p",
                "--input-format", "stream-json",
                "--output-format", "stream-json",
                "--verbose",
                // Partial-message deltas drive the live narration (content_block_delta).
                // Verified present on 2.1.138; if a future CLI drops it, the adapter
                // degrades to authoritative full messages per turn - still functional.
                "--include-partial-messages",
                // In MaxVibes mode the plugin is the sole I/O channel: files arrive via
                // freshFiles in the payload, modifications go out via the JSON protocol.
                // Without this restriction the model wastes 30-180s/turn on bounced tools.
                // Note: an empty --allowed-tools arg breaks argv quoting on Windows,
                // hence the explicit disallow list.
                "--disallowed-tools",
                "Read,Write,Edit,MultiEdit,NotebookEdit,Bash,Glob,Grep,WebFetch,WebSearch,PowerShell,Task"
            )
            // System prompt via temp file + flag: keeps prompt-looking text out of the
            // user payload (prompt-injection classifier) and dodges Windows argv limits.
            val hasSystemPrompt = !systemPrompt.isNullOrBlank()
            var promptFilePath: String? = null
            if (!systemPrompt.isNullOrBlank()) {
                val promptFile = java.nio.file.Files.createTempFile("maxvibes-sysprompt-", ".md")
                java.nio.file.Files.writeString(promptFile, systemPrompt, Charsets.UTF_8)
                promptFile.toFile().deleteOnExit()
                promptFilePath = promptFile.toAbsolutePath().toString()
                baseArgs += "--append-system-prompt-file"
                baseArgs += promptFilePath
            }
            if (resumeSessionId != null) {
                baseArgs += "--resume"
                baseArgs += resumeSessionId
            }
            val extraArgs = settings.claudeCodeExtraArgs.split(' ').filter { it.isNotBlank() }
            val allArgs = baseArgs + extraArgs

            val resolvedWorkDir: File? = workingDirectory
                ?.takeIf { it.isNotBlank() }
                ?.let { File(it) }
                ?.takeIf { it.isDirectory }

            val cmd = GeneralCommandLine(settings.claudeCodePath).apply {
                charset = StandardCharsets.UTF_8
                addParameters(*allArgs.toTypedArray())
                if (resolvedWorkDir != null) withWorkDirectory(resolvedWorkDir)
            }

            MaxVibesLogger.info(TAG, "ensureStarted: spawning",
                mapOf(
                    "path" to settings.claudeCodePath,
                    "args" to allArgs.joinToString(" "),
                    "resume" to (resumeSessionId ?: "null"),
                    "hasSystemPrompt" to hasSystemPrompt,
                    "promptFile" to (promptFilePath ?: "<none>"),
                    "promptLen" to (systemPrompt?.length ?: 0),
                    "workDir" to (resolvedWorkDir?.absolutePath ?: "<inherited>"),
                    "requestedWorkDir" to (workingDirectory ?: "null")
                ))
            // The transcript intentionally gets the FULL command line - it is the
            // single file needed to reproduce the invocation.
            sessionLog?.event("spawning",
                mapOf(
                    "cmd" to settings.claudeCodePath,
                    "args" to allArgs.joinToString(" "),
                    "resume" to (resumeSessionId ?: "null"),
                    "workDir" to (resolvedWorkDir?.absolutePath ?: "<inherited>")
                ))

            val proc = try {
                cmd.createProcess()
            } catch (e: Exception) {
                MaxVibesLogger.error(TAG, "ensureStarted: createProcess threw", ex = e,
                    data = mapOf("path" to settings.claudeCodePath, "args" to allArgs.joinToString(" ")))
                sessionLog?.event("createProcess threw",
                    mapOf("ex" to e.javaClass.simpleName, "exMsg" to (e.message ?: "")))
                return Result.Failure(ClaudeCodeError.Crashed("Could not spawn process: ${e.message}"))
            }

            process = proc
            stdin = proc.outputStream.bufferedWriter(StandardCharsets.UTF_8)
            synchronized(stderrBuffer) { stderrBuffer.setLength(0) }
            lastStderrSnapshot = ""

            startStderrCollector(proc)
            startReaderLoop(proc)

            delay(SPAWN_GRACE_MS)

            if (!proc.isAlive) {
                val err = stderrSnapshot()
                lastStderrSnapshot = err
                val exitCode = runCatching { proc.exitValue() }.getOrDefault(-1)
                MaxVibesLogger.error(TAG, "ensureStarted: process died during grace period",
                    data = mapOf("exit" to exitCode, "stderr" to err.take(LOG_LINE_PREVIEW_MAX),
                        "resume" to (resumeSessionId ?: "null")))
                sessionLog?.event("process died during spawn grace",
                    mapOf("exit" to exitCode, "resume" to (resumeSessionId ?: "null"), "stderr" to err))
                terminate(reason = "died during spawn grace", asAbort = false)
                return if (resumeSessionId != null) {
                    Result.Failure(ClaudeCodeError.ResumeFailed(resumeSessionId, err))
                } else {
                    Result.Failure(ClaudeCodeError.ProcessFailed(exitCode, err))
                }
            }

            MaxVibesLogger.info(TAG, "ensureStarted: alive after grace",
                mapOf("pid" to runCatching { proc.pid() }.getOrDefault(-1L),
                    "resume" to (resumeSessionId ?: "null")))
            sessionLog?.event("alive after grace",
                mapOf("pid" to runCatching { proc.pid() }.getOrDefault(-1L),
                    "resume" to (resumeSessionId ?: "null")))
            return Result.Success(Unit)
        }

    override fun shutdown() {
        terminate(reason = "explicit shutdown", asAbort = false)
    }

    override fun abort() {
        terminate(reason = "abort (user Stop)", asAbort = true)
    }

    /**
     * Kills the process TREE and completes any in-flight turn. Lock-free by design:
     * [send] holds [sendMutex] while awaiting the turn, so taking it here would deadlock.
     */
    private fun terminate(reason: String, asAbort: Boolean) {
        val turn = activeTurn
        if (turn != null && !turn.deferred.isCompleted) {
            val partial = turn.snapshotText().takeIf { it.isNotBlank() }
            if (asAbort) {
                emitEvent(AgentStreamEvent.Failed("aborted by user", partial))
                turn.deferred.complete(TurnOutcome.Aborted(partial))
            } else {
                turn.deferred.complete(TurnOutcome.Died("process shut down ($reason)"))
            }
        }
        val proc = process
        if (proc != null) {
            MaxVibesLogger.info(TAG, "terminate",
                mapOf("reason" to reason, "alive" to proc.isAlive,
                    "exit" to runCatching { if (!proc.isAlive) proc.exitValue() else null }.getOrNull()))
            sessionLog?.event("terminate", mapOf("reason" to reason, "alive" to proc.isAlive))
            // Descendants FIRST, then the root - kills the whole tree, not just the launcher.
            runCatching { proc.toHandle().descendants().forEach { it.destroyForcibly() } }
            runCatching { stdin?.close() }
            runCatching { readerJob?.cancel() }
            runCatching { stderrCollector?.cancel() }
            runCatching { proc.destroyForcibly() }
            runCatching { proc.waitFor(2, TimeUnit.SECONDS) }
        }
        process = null
        stdin = null
        readerJob = null
        stderrCollector = null
    }

    // ==================== Reader loop ====================

    private fun startStderrCollector(proc: Process) {
        stderrCollector = scope.launch(Dispatchers.IO) {
            try {
                proc.errorStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                    lines.forEach { line ->
                        synchronized(stderrBuffer) { stderrBuffer.appendLine(line) }
                        MaxVibesLogger.warn(TAG, "stderr",
                            data = mapOf("line" to line.take(LOG_LINE_PREVIEW_MAX)))
                        sessionLog?.stderr(line)
                        // Surface stderr as a Notice only mid-turn; spawn-time noise is skipped.
                        if (activeTurn != null) {
                            emitEvent(AgentStreamEvent.Notice("stderr: ${line.take(200)}"))
                        }
                    }
                }
            } catch (e: Exception) {
                MaxVibesLogger.warn(TAG, "stderr collector threw", ex = e)
            }
        }
    }

    /**
     * Persistent stdout drain: raw line -> transcript (ground truth) -> parser -> events.
     * A broken line NEVER kills the loop. On EOF, any in-flight turn fails as Died.
     */
    private fun startReaderLoop(proc: Process) {
        readerJob = scope.launch(Dispatchers.IO) {
            val reader = proc.inputStream.bufferedReader(StandardCharsets.UTF_8)
            try {
                while (true) {
                    val line = reader.readLine() ?: break
                    try {
                        handleStdoutLine(line)
                    } catch (e: Exception) {
                        MaxVibesLogger.warn(TAG, "stdout line handler threw - line skipped", ex = e,
                            data = mapOf("preview" to line.take(LOG_LINE_PREVIEW_MAX)))
                    }
                }
            } catch (e: Exception) {
                MaxVibesLogger.warn(TAG, "stdout reader terminated", ex = e)
            }
            val turn = activeTurn
            if (turn != null && !turn.deferred.isCompleted) {
                val err = stderrSnapshot()
                lastStderrSnapshot = err
                MaxVibesLogger.warn(TAG, "stdout closed mid-turn",
                    data = mapOf("linesRead" to turn.linesRead, "stderr" to err.take(LOG_LINE_PREVIEW_MAX)))
                sessionLog?.event("stdout closed mid-turn",
                    mapOf("linesRead" to turn.linesRead, "stderr" to err))
                emitEvent(AgentStreamEvent.Failed(
                    "process stdout closed before turn end",
                    turn.snapshotText().takeIf { it.isNotBlank() }))
                turn.deferred.complete(
                    TurnOutcome.Died("stdout closed before turn end (stderr: ${err.take(200)})"))
            }
        }
    }

    private fun handleStdoutLine(line: String) {
        sessionLog?.inbound(line)
        val turn = activeTurn
        turn?.let { it.linesRead++; it.touch() }

        when (val parsed = parser.parse(line)) {
            is StreamJsonEventParser.Line.Init -> {
                lastKnownSessionId = parsed.sessionId ?: lastKnownSessionId
                turn?.observedSessionId = parsed.sessionId
                MaxVibesLogger.info(TAG, "session init",
                    mapOf("sessionId" to (parsed.sessionId ?: "null"), "model" to (parsed.model ?: "null")))
                sessionLog?.event("claude session_id observed",
                    mapOf("claudeSessionId" to (parsed.sessionId ?: "null")))
                emitEvent(AgentStreamEvent.SessionStarted(
                    sessionId = parsed.sessionId ?: "?",
                    model = parsed.model ?: "?"))
            }

            is StreamJsonEventParser.Line.SystemNotice -> {
                MaxVibesLogger.info(TAG, "notice", mapOf("text" to parsed.text))
                emitEvent(AgentStreamEvent.Notice(parsed.text))
            }

            is StreamJsonEventParser.Line.Delta ->
                emitEvent(AgentStreamEvent.NarrationDelta(parsed.messageId, parsed.text, parsed.thinking))

            is StreamJsonEventParser.Line.Assistant -> {
                // Authoritative message: accumulate for the final-text fallback and
                // emit as a buffer-replacing NarrationMessage (one per channel).
                parsed.text?.let {
                    turn?.appendText(it)
                    emitEvent(AgentStreamEvent.NarrationMessage(parsed.messageId, it, thinking = false))
                }
                parsed.thinking?.let {
                    turn?.appendThinking(it)
                    emitEvent(AgentStreamEvent.NarrationMessage(parsed.messageId, it, thinking = true))
                }
                parsed.toolUses.forEach {
                    emitEvent(AgentStreamEvent.ToolStarted(it.id, it.name, it.summary))
                }
            }

            is StreamJsonEventParser.Line.ToolResult ->
                emitEvent(AgentStreamEvent.ToolFinished(parsed.toolUseId, parsed.ok, parsed.summary))

            is StreamJsonEventParser.Line.TurnEnd -> {
                val stats = SessionStats(
                    costUsd = parsed.costUsd,
                    numTurns = parsed.numTurns,
                    durationMs = parsed.durationMs,
                    inputTokens = parsed.inputTokens,
                    outputTokens = parsed.outputTokens
                )
                // Self-healing: blank/missing result text falls back to accumulated
                // authoritative assistant text - protocol parsing survives schema drift.
                val finalText = parsed.finalText?.takeIf { it.isNotBlank() }
                    ?: turn?.snapshotText().orEmpty()
                MaxVibesLogger.info(TAG, "turn end",
                    mapOf("isError" to parsed.isError, "finalLen" to finalText.length,
                        "costUsd" to parsed.costUsd, "numTurns" to parsed.numTurns,
                        "inTok" to parsed.inputTokens, "outTok" to parsed.outputTokens))
                if (parsed.isError) {
                    emitEvent(AgentStreamEvent.Failed(
                        finalText.ifBlank { "turn ended with error" },
                        turn?.snapshotText()?.takeIf { it.isNotBlank() }))
                } else {
                    emitEvent(AgentStreamEvent.Completed(finalText, stats))
                }
                parser.reset()
                turn?.deferred?.complete(
                    TurnOutcome.Finished(finalText, parsed.isError, stats))
            }

            is StreamJsonEventParser.Line.Unknown ->
                MaxVibesLogger.info(TAG, "unknown stream-json line skipped",
                    mapOf("type" to (parsed.type ?: "unparseable"),
                        "preview" to line.take(LOG_LINE_PREVIEW_MAX)))

            StreamJsonEventParser.Line.Ignored -> { /* service envelope - raw already in CC log */ }
        }
    }

    // ==================== Send ====================

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
                val err = stderrSnapshot()
                lastStderrSnapshot = err
                MaxVibesLogger.warn(TAG, "send: process died before write",
                    data = mapOf("exit" to runCatching { proc.exitValue() }.getOrDefault(-1),
                        "stderr" to err.take(LOG_LINE_PREVIEW_MAX)))
                sessionLog?.event("send rejected: process died before write",
                    mapOf("exit" to runCatching { proc.exitValue() }.getOrDefault(-1), "stderr" to err))
                return Result.Failure(ClaudeCodeError.Crashed("Process died"))
            }

            // 1. Encode request; wrap into a stream-json user event.
            val protocolJson = codec.encode(request, omitMetaFields = true)
            val eventJson = buildUserEvent(protocolJson, request.attachedImages)
            MaxVibesLogger.info(TAG, "send: encoded",
                mapOf("requestJsonLen" to protocolJson.length, "streamLineLen" to eventJson.length,
                    "requestPreview" to protocolJson.take(LOG_REQUEST_PREVIEW_MAX),
                    "images" to request.attachedImages.size))
            // Outbound line into the transcript BEFORE the write attempt. Image payloads
            // (base64) are never mirrored - text-only variant plus a count event instead.
            if (request.attachedImages.isEmpty()) {
                sessionLog?.outbound(eventJson)
            } else {
                sessionLog?.outbound(buildUserEvent(protocolJson, emptyList()))
                sessionLog?.event("outbound images omitted from transcript",
                    mapOf("images" to request.attachedImages.size))
            }

            // 2. Register the turn BEFORE writing stdin so the reader attributes
            //    every response line to it (no early-line race).
            val turn = TurnState(System.currentTimeMillis())
            parser.reset()
            activeTurn = turn
            try {
                try {
                    withContext(Dispatchers.IO) {
                        val w = stdin ?: error("stdin not initialized")
                        w.write(eventJson)
                        w.newLine()
                        w.flush()
                    }
                    MaxVibesLogger.info(TAG, "send: stdin flushed")
                    sessionLog?.event("stdin flushed")
                } catch (e: Exception) {
                    MaxVibesLogger.error(TAG, "send: stdin write failed", ex = e)
                    sessionLog?.event("stdin write failed",
                        mapOf("ex" to e.javaClass.simpleName, "exMsg" to (e.message ?: "")))
                    return Result.Failure(ClaudeCodeError.Crashed("stdin write failed: ${e.message}"))
                }

                // 3. Await the outcome with an INACTIVITY watchdog: the timeout counts
                //    silence since the last stdout line, not total turn duration.
                val inactivityLimitMs = settings.claudeCodeReadTimeoutSec.toLong() * 1000
                var outcome: TurnOutcome? = null
                while (outcome == null) {
                    outcome = withTimeoutOrNull(WATCHDOG_TICK_MS) { turn.deferred.await() }
                    if (outcome == null) {
                        val silentMs = System.currentTimeMillis() - turn.lastActivityAtMs
                        if (silentMs > inactivityLimitMs) {
                            val err = stderrSnapshot()
                            MaxVibesLogger.warn(TAG, "send: inactivity timeout - killing process tree",
                                data = mapOf("silentMs" to silentMs, "linesRead" to turn.linesRead,
                                    "stderr" to err.take(LOG_LINE_PREVIEW_MAX)))
                            sessionLog?.event("inactivity timeout",
                                mapOf("silentMs" to silentMs, "linesRead" to turn.linesRead, "stderr" to err))
                            emitEvent(AgentStreamEvent.Failed(
                                "no output for ${silentMs / 1000}s - process killed",
                                turn.snapshotText().takeIf { it.isNotBlank() }))
                            turn.deferred.complete(TurnOutcome.Died("inactivity timeout"))
                            terminate(reason = "inactivity timeout", asAbort = false)
                            return Result.Failure(ClaudeCodeError.Timeout)
                        }
                    }
                }

                val elapsedMs = System.currentTimeMillis() - turn.startedAtMs

                return when (outcome) {
                    is TurnOutcome.Aborted ->
                        Result.Failure(ClaudeCodeError.Aborted(outcome.partialText))

                    is TurnOutcome.Died -> {
                        lastStderrSnapshot = stderrSnapshot()
                        Result.Failure(ClaudeCodeError.Crashed(outcome.message))
                    }

                    is TurnOutcome.Finished -> {
                        if (outcome.isError) {
                            sessionLog?.event("result is_error",
                                mapOf("preview" to (outcome.finalText ?: "").take(LOG_LINE_PREVIEW_MAX)))
                            return Result.Failure(ClaudeCodeError.Crashed(
                                "claude reported error result: ${(outcome.finalText ?: "").take(200)}"))
                        }
                        val text = outcome.finalText?.takeIf { it.isNotBlank() } ?: turn.snapshotText()
                        MaxVibesLogger.info(TAG, "send: decoding response",
                            mapOf("assistantLen" to text.length,
                                "preview" to text.take(LOG_REQUEST_PREVIEW_MAX), "elapsedMs" to elapsedMs))
                        val response = codec.decode(text)
                            ?: if (text.isNotBlank()) {
                                MaxVibesLogger.warn(TAG,
                                    "send: codec.decode returned null - falling back to plain message",
                                    data = mapOf("assistantLen" to text.length,
                                        "preview" to text.take(LOG_LINE_PREVIEW_MAX)))
                                sessionLog?.event("decode failed - falling back to plain message",
                                    mapOf("assistantLen" to text.length))
                                InteractionResponse(message = text)
                            } else {
                                MaxVibesLogger.warn(TAG, "send: no usable content")
                                sessionLog?.event("decode failed - no usable content")
                                return Result.Failure(ClaudeCodeError.ParseFailed(
                                    "Claude Code returned no usable content (assistantLen=${text.length})"))
                            }
                        val thinkingText = turn.snapshotThinking().takeIf { it.isNotBlank() }
                        MaxVibesLogger.info(TAG, "send: done",
                            mapOf("elapsedMs" to elapsedMs, "linesRead" to turn.linesRead,
                                "assistantLen" to text.length,
                                "thinkingLen" to (thinkingText?.length ?: 0),
                                "sessionId" to (turn.observedSessionId ?: lastKnownSessionId ?: "null"),
                                "costUsd" to outcome.stats.costUsd))
                        sessionLog?.event("turn done",
                            mapOf("elapsedMs" to elapsedMs, "linesRead" to turn.linesRead,
                                "assistantLen" to text.length,
                                "thinkingLen" to (thinkingText?.length ?: 0),
                                "claudeSessionId" to (turn.observedSessionId ?: lastKnownSessionId ?: "null")))
                        Result.Success(ClaudeCodeSendResult(
                            response = response,
                            observedSessionId = turn.observedSessionId ?: lastKnownSessionId,
                            thinkingText = thinkingText,
                            stats = outcome.stats
                        ))
                    }
                }
            } finally {
                activeTurn = null
            }
        }

    // ==================== Helpers ====================

    private fun emitEvent(event: AgentStreamEvent) {
        try {
            streamSink?.emit(event)
        } catch (e: Exception) {
            MaxVibesLogger.warn(TAG, "streamSink.emit threw - ignoring", ex = e)
        }
    }

    private fun stderrSnapshot(): String = synchronized(stderrBuffer) { stderrBuffer.toString() }

    /**
     * Builds the stream-json user event for stdin. Without images the content stays a
     * plain string (byte-identical to the legacy format, prompt-cache friendly); with
     * images it becomes a content-block array (Anthropic Messages format).
     */
    private fun buildUserEvent(protocolJson: String, images: List<AttachedImage>): String {
        val message = buildJsonObject {
            put("role", "user")
            if (images.isEmpty()) {
                put("content", protocolJson)
            } else {
                putJsonArray("content") {
                    addJsonObject {
                        put("type", "text")
                        put("text", protocolJson)
                    }
                    images.forEach { img ->
                        addJsonObject {
                            put("type", "image")
                            putJsonObject("source") {
                                put("type", "base64")
                                put("media_type", img.mediaType)
                                put("data", img.base64Data)
                            }
                        }
                    }
                }
            }
        }
        return buildJsonObject {
            put("type", "user")
            put("message", message)
        }.toString()
    }
}