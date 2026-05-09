package com.maxvibes.plugin.claudecode

import com.intellij.execution.configurations.GeneralCommandLine
import com.maxvibes.application.port.output.ClaudeCodeError
import com.maxvibes.application.port.output.ClaudeCodePort
import com.maxvibes.application.port.output.ClaudeCodeSendResult
import com.maxvibes.application.port.output.InteractionProtocolCodec
import com.maxvibes.domain.model.interaction.ClipboardRequest
import com.maxvibes.plugin.clipboard.JsonInteractionProtocolCodec
import com.maxvibes.plugin.settings.MaxVibesSettings
import com.maxvibes.shared.result.Result
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.BufferedWriter
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
 *          [--resume <session-uuid>]
 *          [<extra args from settings>]
 *
 * Note: `-p` (--print) is REQUIRED for stream-json input/output to work.
 * `--verbose` is required so that stream-json emits all event types (system/init,
 * assistant, result), not just the terminal result.
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

    private var process: Process? = null
    private var stdin: BufferedWriter? = null
    private var stdout: BufferedReader? = null
    private var stderrCollector: Job? = null
    private val stderrBuffer = StringBuilder()
    private val sendMutex = Mutex()

    /** Captured during ensureStarted so the first turn doesn't have to wait for it. */
    private var pendingSessionId: String? = null

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
                false
            } else {
                proc.exitValue() == 0
            }
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun ensureStarted(resumeSessionId: String?): Result<Unit, ClaudeCodeError> =
        sendMutex.withLock {
            if (process?.isAlive == true) return Result.Success(Unit)
            if (!isAvailable()) return Result.Failure(ClaudeCodeError.BinaryNotFound)

            val cmd = GeneralCommandLine(settings.claudeCodePath).apply {
                charset = StandardCharsets.UTF_8
                // Verified base flags — --print is required for stream-json I/O,
                // --verbose is required for stream-json output to emit all event types.
                addParameters(
                    "-p",
                    "--input-format", "stream-json",
                    "--output-format", "stream-json",
                    "--verbose"
                )
                if (resumeSessionId != null) {
                    addParameters("--resume", resumeSessionId)
                }
                // Settings extra args (split on whitespace — naive, improve later if quoting needed).
                settings.claudeCodeExtraArgs
                    .split(' ')
                    .filter { it.isNotBlank() }
                    .forEach { addParameter(it) }
            }

            val proc = try {
                cmd.createProcess()
            } catch (e: Exception) {
                return Result.Failure(
                    ClaudeCodeError.Crashed("Could not spawn process: ${e.message}")
                )
            }

            process = proc
            stdin = proc.outputStream.bufferedWriter(StandardCharsets.UTF_8)
            stdout = proc.inputStream.bufferedReader(StandardCharsets.UTF_8)
            stderrCollector = scope.launch(Dispatchers.IO) {
                proc.errorStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                    lines.forEach { line ->
                        synchronized(stderrBuffer) { stderrBuffer.appendLine(line) }
                    }
                }
            }

            // Health check: wait for first system/init line within startTimeout.
            // Real liveness signal — if claude can't initialize we find out here, not later.
            val startTimeoutMs = settings.claudeCodeStartTimeoutSec.toLong() * 1000
            val initObserved = withTimeoutOrNull(startTimeoutMs) {
                withContext(Dispatchers.IO) {
                    while (true) {
                        val line = stdout!!.readLine() ?: return@withContext null
                        StreamJsonProtocol.extractSessionId(line)?.let { return@withContext it }
                        // Skip non-init lines (in case claude emits something else first).
                        if (StreamJsonProtocol.isTurnEnd(line)) return@withContext null
                    }
                    @Suppress("UNREACHABLE_CODE")
                    null
                }
            }

            if (!proc.isAlive && initObserved == null) {
                val err = synchronized(stderrBuffer) { stderrBuffer.toString() }
                val exitCode = runCatching { proc.exitValue() }.getOrDefault(-1)
                shutdown()
                return if (resumeSessionId != null) {
                    Result.Failure(ClaudeCodeError.ResumeFailed(resumeSessionId, err))
                } else {
                    Result.Failure(ClaudeCodeError.ProcessFailed(exitCode, err))
                }
            }

            pendingSessionId = initObserved
            return Result.Success(Unit)
        }

    override suspend fun send(request: ClipboardRequest): Result<ClaudeCodeSendResult, ClaudeCodeError> =
        sendMutex.withLock {
            val proc = process
                ?: return Result.Failure(ClaudeCodeError.Crashed("Process not started"))
            if (!proc.isAlive) return Result.Failure(ClaudeCodeError.Crashed("Process died"))

            // 1. Encode request through the shared codec, then wrap in stream-json.
            val requestJson = codec.encode(request)
            val streamJsonLine = StreamJsonProtocol.encodeUserEvent(requestJson)

            // 2. Write to stdin.
            try {
                withContext(Dispatchers.IO) {
                    val w = stdin ?: error("stdin not initialized")
                    w.write(streamJsonLine)
                    w.newLine()
                    w.flush()
                }
            } catch (e: Exception) {
                return Result.Failure(
                    ClaudeCodeError.Crashed("stdin write failed: ${e.message}")
                )
            }

            // 3. Read stdout until isTurnEnd. Collect assistant text. Capture session id.
            val timeoutMs = settings.claudeCodeReadTimeoutSec.toLong() * 1000
            val accumulated = StringBuilder()
            // Carry over the session id captured during ensureStarted, if any.
            var observedSessionId: String? = pendingSessionId.also { pendingSessionId = null }

            val readReached = withTimeoutOrNull(timeoutMs) {
                withContext(Dispatchers.IO) {
                    val r = stdout ?: error("stdout not initialized")
                    while (true) {
                        val line = r.readLine() ?: break
                        StreamJsonProtocol.extractSessionId(line)?.let { observedSessionId = it }
                        StreamJsonProtocol.extractAssistantText(line)?.let { accumulated.append(it) }
                        if (StreamJsonProtocol.isTurnEnd(line)) break
                    }
                    Unit
                }
            } ?: return Result.Failure(ClaudeCodeError.Timeout)

            // 4. Decode the accumulated assistant text into an InteractionResponse.
            val responseText = accumulated.toString()
            val response = codec.decode(responseText)
                ?: return Result.Failure(
                    ClaudeCodeError.ParseFailed(
                        "codec.decode returned null. Raw assistant text length=${responseText.length}"
                    )
                )

            return Result.Success(ClaudeCodeSendResult(response, observedSessionId))
        }

    override fun shutdown() {
        process?.let { proc ->
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
        pendingSessionId = null
        synchronized(stderrBuffer) { stderrBuffer.setLength(0) }
    }
}
