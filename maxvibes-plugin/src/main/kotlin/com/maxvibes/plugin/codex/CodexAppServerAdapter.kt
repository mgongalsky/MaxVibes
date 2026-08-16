package com.maxvibes.plugin.codex

import com.intellij.execution.configurations.GeneralCommandLine
import com.maxvibes.application.port.output.AgentStreamEvent
import com.maxvibes.application.port.output.AgentStreamSink
import com.maxvibes.application.port.output.ClaudeCodeError
import com.maxvibes.application.port.output.CodingAgentCliPort
import com.maxvibes.application.port.output.CodingAgentCliSendResult
import com.maxvibes.application.port.output.CodingAgentSessionLogPort
import com.maxvibes.application.port.output.InteractionProtocolCodec
import com.maxvibes.application.port.output.SessionStats
import com.maxvibes.domain.model.interaction.AttachedImage
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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.BufferedWriter
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.contentOrNull
import com.maxvibes.domain.model.stream.StreamTranscriptDigest

/** Persistent Codex App Server transport over stdio JSONL. */
class CodexAppServerAdapter(
    private val settings: MaxVibesSettings,
    private val codec: InteractionProtocolCodec = JsonInteractionProtocolCodec(),
    private val scope: CoroutineScope,
    private val workingDirectory: String? = null,
    private val sessionLog: CodingAgentSessionLogPort? = null,
    private val streamSink: AgentStreamSink? = null
) : CodingAgentCliPort {

    private companion object {
        private const val TAG = "Codex"
        private const val SPAWN_GRACE_MS = 200L
        private const val WATCHDOG_TICK_MS = 1000L
        private const val PREVIEW_MAX = 500
    }

    private data class SpawnConfig(
        val path: String,
        val extraArgs: String,
        val model: String,
        val reasoningEffort: String
    )

    private sealed interface TurnOutcome {
        data class Finished(
            val finalText: String,
            val isError: Boolean,
            val errorMessage: String?,
            val stats: SessionStats
        ) : TurnOutcome

        data class Died(val message: String) : TurnOutcome
        data class Aborted(val partialText: String?) : TurnOutcome
    }

    private class TurnState(
        val startedAtMs: Long
    ) {
        val deferred = CompletableDeferred<TurnOutcome>()
        private val messages = linkedMapOf<String, StringBuilder>()
        private val reasoning = linkedMapOf<String, StringBuilder>()

        @Volatile
        var lastActivityAtMs: Long = startedAtMs
        @Volatile
        var inputTokens: Int = 0
        @Volatile
        var outputTokens: Int = 0

        fun touch() {
            lastActivityAtMs = System.currentTimeMillis()
        }

        @Synchronized
        fun appendMessage(id: String, text: String) {
            messages.getOrPut(id) { StringBuilder() }.append(text)
        }

        @Synchronized
        fun setMessage(id: String, text: String) {
            messages[id] = StringBuilder(text)
        }

        @Synchronized
        fun appendReasoning(id: String, text: String) {
            reasoning.getOrPut(id) { StringBuilder() }.append(text)
        }

        @Synchronized
        fun setReasoning(id: String, text: String) {
            reasoning[id] = StringBuilder(text)
        }

        @Synchronized
        fun snapshotText(): String = joinBuffers(messages)

        @Synchronized
        fun snapshotReasoning(): String = joinBuffers(reasoning)

        private fun joinBuffers(source: LinkedHashMap<String, StringBuilder>): String =
            source.values
                .map { it.toString().trim() }
                .filter { it.isNotBlank() }
                .joinToString(System.lineSeparator() + System.lineSeparator())
    }

    @Volatile
    private var spawnConfig: SpawnConfig? = null
    @Volatile
    private var process: Process? = null
    @Volatile
    private var stdin: BufferedWriter? = null
    @Volatile
    private var readerJob: Job? = null
    @Volatile
    private var stderrJob: Job? = null
    @Volatile
    private var activeTurn: TurnState? = null
    @Volatile
    private var currentThreadId: String? = null

    private val requestIds = AtomicLong(1L)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<CodexAppServerLineParser.Line.Response>>()
    private val sendMutex = Mutex()
    private val parser = CodexAppServerLineParser()

    /** Свёртка дельт: в транскрипт уходит итог по потоку, а не строка на фрагмент. */
    private val transcriptDigest = StreamTranscriptDigest()
    private val stderrBuffer = StringBuilder()

    private fun currentConfig(): SpawnConfig = SpawnConfig(
        path = settings.codexPath,
        extraArgs = settings.codexExtraArgs,
        model = settings.codexModel,
        reasoningEffort = settings.codexReasoningEffort
    )

    override fun isAvailable(): Boolean = try {
        val cmd = GeneralCommandLine(settings.codexPath, "--version").apply {
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
    } catch (exception: Exception) {
        MaxVibesLogger.warn(TAG, "isAvailable threw", ex = exception)
        false
    }

    override suspend fun ensureStarted(
        resumeSessionId: String?,
        systemPrompt: String?
    ): Result<Unit, ClaudeCodeError> = sendMutex.withLock {
        val desired = currentConfig()
        if (process?.isAlive == true && spawnConfig != desired) {
            terminate("config changed", asAbort = false)
        }

        if (process?.isAlive != true) {
            val started = startProcess()
            if (started is Result.Failure) return started
        }

        if (resumeSessionId != null && currentThreadId == resumeSessionId) {
            return Result.Success(Unit)
        }

        return if (resumeSessionId != null) {
            resumeThread(resumeSessionId)
        } else {
            startThread()
        }
    }

    private suspend fun startProcess(): Result<Unit, ClaudeCodeError> {
        if (!isAvailable()) return Result.Failure(ClaudeCodeError.BinaryNotFound)

        val args = mutableListOf("app-server")
        args += settings.codexExtraArgs.split(' ').filter { it.isNotBlank() }
        val resolvedWorkDir = workingDirectory
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?.takeIf { it.isDirectory }

        val cmd = GeneralCommandLine(settings.codexPath).apply {
            charset = StandardCharsets.UTF_8
            addParameters(*args.toTypedArray())
            if (resolvedWorkDir != null) withWorkDirectory(resolvedWorkDir)
        }

        sessionLog?.event(
            "spawning Codex App Server",
            mapOf(
                "path" to settings.codexPath,
                "args" to args.joinToString(" "),
                "workDir" to (resolvedWorkDir?.absolutePath ?: "<inherited>")
            )
        )

        val proc = try {
            cmd.createProcess()
        } catch (exception: Exception) {
            return Result.Failure(
                ClaudeCodeError.Crashed("Could not spawn Codex App Server: ${exception.message}")
            )
        }

        process = proc
        stdin = proc.outputStream.bufferedWriter(StandardCharsets.UTF_8)
        synchronized(stderrBuffer) { stderrBuffer.setLength(0) }
        startStderrCollector(proc)
        startReaderLoop(proc)
        delay(SPAWN_GRACE_MS)

        if (!proc.isAlive) {
            val exit = runCatching { proc.exitValue() }.getOrDefault(-1)
            val stderr = stderrSnapshot()
            terminate("died during spawn grace", asAbort = false)
            return Result.Failure(ClaudeCodeError.ProcessFailed(exit, stderr))
        }

        val initialize = buildJsonObject {
            putJsonObject("clientInfo") {
                put("name", "maxvibes")
                put("title", "MaxVibes")
                put("version", "1")
            }
        }
        val response = rpcRequest(
            method = "initialize",
            params = initialize,
            timeoutMs = settings.codexStartTimeoutSec.toLong() * 1000
        ) ?: run {
            terminate("initialize timeout", asAbort = false)
            return Result.Failure(ClaudeCodeError.Timeout)
        }
        response.error?.let {
            terminate("initialize failed", asAbort = false)
            return Result.Failure(ClaudeCodeError.Crashed(it.message))
        }

        rpcNotify("initialized", JsonObject(emptyMap()))
        spawnConfig = currentConfig()
        currentThreadId = null
        return Result.Success(Unit)
    }

    private suspend fun startThread(): Result<Unit, ClaudeCodeError> {
        val params = buildThreadParams()
        val response = rpcRequest(
            method = "thread/start",
            params = params,
            timeoutMs = settings.codexStartTimeoutSec.toLong() * 1000
        ) ?: return Result.Failure(ClaudeCodeError.Timeout)
        response.error?.let { return Result.Failure(ClaudeCodeError.Crashed(it.message)) }

        val threadId = extractThreadId(response.result)
            ?: return Result.Failure(ClaudeCodeError.ParseFailed("thread/start returned no thread id"))
        currentThreadId = threadId
        emitEvent(
            AgentStreamEvent.SessionStarted(
                sessionId = threadId,
                model = settings.codexModel.ifBlank { "auto" }
            )
        )
        sessionLog?.event("Codex thread started", mapOf("threadId" to threadId))
        return Result.Success(Unit)
    }

    private suspend fun resumeThread(threadId: String): Result<Unit, ClaudeCodeError> {
        val params = buildJsonObject {
            buildThreadParams().forEach { (key, value) -> put(key, value) }
            put("threadId", threadId)
        }
        val response = rpcRequest(
            method = "thread/resume",
            params = params,
            timeoutMs = settings.codexStartTimeoutSec.toLong() * 1000
        ) ?: return Result.Failure(ClaudeCodeError.Timeout)
        response.error?.let {
            return Result.Failure(ClaudeCodeError.ResumeFailed(threadId, it.message))
        }

        currentThreadId = extractThreadId(response.result) ?: threadId
        emitEvent(
            AgentStreamEvent.SessionStarted(
                sessionId = currentThreadId ?: threadId,
                model = settings.codexModel.ifBlank { "auto" }
            )
        )
        sessionLog?.event("Codex thread resumed", mapOf("threadId" to threadId))
        return Result.Success(Unit)
    }

    private fun buildThreadParams(): JsonObject = buildJsonObject {
        settings.codexModel.trim().takeIf { it.isNotEmpty() }?.let { put("model", it) }
        workingDirectory?.takeIf { it.isNotBlank() }?.let { put("cwd", it) }
        put("approvalPolicy", "never")
        put("sandbox", "read-only")
        settings.codexReasoningEffort.trim().takeIf { it.isNotEmpty() }?.let { effort ->
            putJsonObject("config") {
                put("model_reasoning_effort", effort)
            }
        }
    }

    override suspend fun send(
        request: ClipboardRequest
    ): Result<CodingAgentCliSendResult, ClaudeCodeError> = sendMutex.withLock {
        val proc = process
        if (proc == null || !proc.isAlive) {
            return Result.Failure(ClaudeCodeError.Crashed("Codex App Server is not running"))
        }
        val threadId = currentThreadId
            ?: return Result.Failure(ClaudeCodeError.Crashed("Codex thread is not initialized"))

        val protocolJson = codec.encode(request, omitMetaFields = false)
        val turn = TurnState(System.currentTimeMillis())
        activeTurn = turn
        try {
            val response = rpcRequest(
                method = "turn/start",
                params = buildTurnParams(threadId, protocolJson, request.attachedImages),
                timeoutMs = settings.codexStartTimeoutSec.toLong() * 1000,
                transcriptParams = buildTurnParams(threadId, protocolJson, emptyList())
            ) ?: return Result.Failure(ClaudeCodeError.Timeout)

            response.error?.let {
                return Result.Failure(ClaudeCodeError.Crashed("turn/start failed: ${it.message}"))
            }

            val inactivityLimitMs = settings.codexReadTimeoutSec.toLong() * 1000
            var outcome: TurnOutcome? = null
            while (outcome == null) {
                outcome = withTimeoutOrNull(WATCHDOG_TICK_MS) { turn.deferred.await() }
                if (outcome == null) {
                    val silentMs = System.currentTimeMillis() - turn.lastActivityAtMs
                    if (silentMs > inactivityLimitMs) {
                        emitEvent(
                            AgentStreamEvent.Failed(
                                "no App Server output for ${silentMs / 1000}s",
                                turn.snapshotText().takeIf { it.isNotBlank() }
                            )
                        )
                        terminate("turn inactivity timeout", asAbort = false)
                        return Result.Failure(ClaudeCodeError.Timeout)
                    }
                }
            }

            return when (outcome) {
                is TurnOutcome.Aborted -> Result.Failure(ClaudeCodeError.Aborted(outcome.partialText))
                is TurnOutcome.Died -> Result.Failure(ClaudeCodeError.Crashed(outcome.message))
                is TurnOutcome.Finished -> {
                    if (outcome.isError) {
                        return Result.Failure(
                            ClaudeCodeError.Crashed(
                                outcome.errorMessage ?: "Codex turn failed"
                            )
                        )
                    }
                    val text = outcome.finalText
                    val decoded = codec.decode(text)
                        ?: if (text.isNotBlank()) {
                            InteractionResponse(message = text)
                        } else {
                            return Result.Failure(
                                ClaudeCodeError.ParseFailed("Codex returned no usable content")
                            )
                        }
                    val reasoning = turn.snapshotReasoning().takeIf { it.isNotBlank() }
                    Result.Success(
                        CodingAgentCliSendResult(
                            response = decoded,
                            observedSessionId = currentThreadId,
                            thinkingText = reasoning,
                            stats = outcome.stats
                        )
                    )
                }
            }
        } finally {
            activeTurn = null
        }
    }

    private fun buildTurnParams(
        threadId: String,
        text: String,
        images: List<AttachedImage>
    ): JsonObject = buildJsonObject {
        put("threadId", threadId)
        putJsonArray("input") {
            add(
                buildJsonObject {
                    put("type", "text")
                    put("text", text)
                }
            )
            images.forEach { image ->
                add(
                    buildJsonObject {
                        put("type", "image")
                        put(
                            "url",
                            "data:${image.mediaType};base64,${image.base64Data}"
                        )
                    }
                )
            }
        }
    }

    private suspend fun rpcRequest(
        method: String,
        params: JsonObject,
        timeoutMs: Long,
        transcriptParams: JsonObject = params
    ): CodexAppServerLineParser.Line.Response? {
        val id = requestIds.getAndIncrement()
        val deferred = CompletableDeferred<CodexAppServerLineParser.Line.Response>()
        pending[id] = deferred
        val line = buildRpcLine(id, method, params)
        val transcriptLine = buildRpcLine(id, method, transcriptParams)
        try {
            writeLine(line, transcriptLine)
            return withTimeoutOrNull(timeoutMs) { deferred.await() }
        } finally {
            pending.remove(id)
        }
    }

    private suspend fun rpcNotify(method: String, params: JsonObject) {
        val line = buildJsonObject {
            put("method", method)
            put("params", params)
        }.toString()
        writeLine(line, line)
    }

    private fun buildRpcLine(id: Long, method: String, params: JsonObject): String =
        buildJsonObject {
            put("id", id)
            put("method", method)
            put("params", params)
        }.toString()

    private suspend fun writeLine(line: String, transcriptLine: String) {
        sessionLog?.outbound(transcriptLine)
        withContext(Dispatchers.IO) {
            val writer = stdin ?: error("Codex stdin is not initialized")
            writer.write(line)
            writer.newLine()
            writer.flush()
        }
    }

    private fun startReaderLoop(proc: Process) {
        readerJob = scope.launch(Dispatchers.IO) {
            try {
                proc.inputStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                    // Запись в транскрипт живёт в handleLine: там уже известен тип
                    // строки, и дельты не уезжают в файл вместе с JSON-RPC конвертом.
                    lines.forEach { line -> handleLine(line) }
                }
            } catch (exception: Exception) {
                MaxVibesLogger.warn(TAG, "reader loop terminated", ex = exception)
            }

            val turn = activeTurn
            if (turn != null && !turn.deferred.isCompleted) {
                turn.deferred.complete(TurnOutcome.Died("Codex App Server stdout closed"))
            }
            pending.forEach { (id, deferred) ->
                deferred.complete(
                    CodexAppServerLineParser.Line.Response(
                        id = id,
                        result = null,
                        error = CodexAppServerLineParser.Line.RpcError(
                            code = null,
                            message = "Codex App Server stdout closed"
                        )
                    )
                )
            }
        }
    }

    private fun startStderrCollector(proc: Process) {
        stderrJob = scope.launch(Dispatchers.IO) {
            try {
                proc.errorStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                    lines.forEach { line ->
                        synchronized(stderrBuffer) { stderrBuffer.appendLine(line) }
                        sessionLog?.stderr(line)
                        if (activeTurn != null) {
                            emitEvent(AgentStreamEvent.Notice("stderr: ${line.take(200)}"))
                        }
                    }
                }
            } catch (exception: Exception) {
                MaxVibesLogger.warn(TAG, "stderr collector terminated", ex = exception)
            }
        }
    }

    private fun handleLine(rawLine: String) {
        val line = parser.parse(rawLine)

        // Дельта приходит на каждые несколько символов, и вокруг неё едет полный
        // JSON-RPC конверт — в транскрипте это давало мегабайты шума на короткий
        // ответ. Тип строки известен только после разбора, поэтому запись идёт
        // отсюда, а не из цикла чтения: дельты сворачиваются в счётчики и уходят
        // одной строкой на поток перед ближайшей содержательной строкой.
        when (line) {
            is CodexAppServerLineParser.Line.NarrationDelta ->
                transcriptDigest.delta(kind = "text", id = line.itemId, chars = line.text.length)

            is CodexAppServerLineParser.Line.ReasoningDelta ->
                transcriptDigest.delta(kind = "reasoning", id = line.itemId, chars = line.text.length)

            CodexAppServerLineParser.Line.Ignored -> transcriptDigest.skipped(rawLine.length)

            else -> {
                transcriptDigest.flush().forEach { sessionLog?.inbound(it) }
                sessionLog?.inbound(rawLine)
            }
        }

        when (line) {
            is CodexAppServerLineParser.Line.Response ->
                pending.remove(line.id)?.complete(line)

            is CodexAppServerLineParser.Line.ThreadStarted -> {
                currentThreadId = line.threadId
                emitEvent(
                    AgentStreamEvent.SessionStarted(
                        sessionId = line.threadId,
                        model = line.model ?: settings.codexModel.ifBlank { "auto" }
                    )
                )
            }

            is CodexAppServerLineParser.Line.TurnStarted ->
                activeTurn?.touch()

            is CodexAppServerLineParser.Line.NarrationDelta -> {
                activeTurn?.apply {
                    touch()
                    appendMessage(line.itemId, line.text)
                }
                emitEvent(AgentStreamEvent.NarrationDelta(line.itemId, line.text, thinking = false))
            }

            is CodexAppServerLineParser.Line.ReasoningDelta -> {
                activeTurn?.apply {
                    touch()
                    appendReasoning(line.itemId, line.text)
                }
                emitEvent(AgentStreamEvent.NarrationDelta(line.itemId, line.text, thinking = true))
            }

            is CodexAppServerLineParser.Line.NarrationMessage -> {
                activeTurn?.apply {
                    touch()
                    setMessage(line.itemId, line.text)
                }
                emitEvent(AgentStreamEvent.NarrationMessage(line.itemId, line.text, thinking = false))
            }

            is CodexAppServerLineParser.Line.ReasoningMessage -> {
                activeTurn?.apply {
                    touch()
                    setReasoning(line.itemId, line.text)
                }
                emitEvent(AgentStreamEvent.NarrationMessage(line.itemId, line.text, thinking = true))
            }

            is CodexAppServerLineParser.Line.ToolStarted -> {
                activeTurn?.touch()
                emitEvent(AgentStreamEvent.ToolStarted(line.itemId, line.name, line.summary))
            }

            is CodexAppServerLineParser.Line.ToolFinished -> {
                activeTurn?.touch()
                emitEvent(AgentStreamEvent.ToolFinished(line.itemId, line.ok, line.summary))
            }

            is CodexAppServerLineParser.Line.TokenUsage ->
                activeTurn?.apply {
                    touch()
                    inputTokens = line.inputTokens
                    outputTokens = line.outputTokens
                }

            is CodexAppServerLineParser.Line.TurnCompleted -> {
                val turn = activeTurn ?: return
                turn.touch()
                val durationMs = System.currentTimeMillis() - turn.startedAtMs
                val text = turn.snapshotText()
                val status = line.status?.lowercase()
                val failed = line.errorMessage != null ||
                        (status != null && status != "completed" && status != "success")
                val stats = SessionStats(
                    costUsd = 0.0,
                    numTurns = 1,
                    durationMs = durationMs,
                    inputTokens = turn.inputTokens,
                    outputTokens = turn.outputTokens
                )
                if (failed) {
                    emitEvent(
                        AgentStreamEvent.Failed(
                            line.errorMessage ?: "Codex turn ended with status ${line.status}",
                            text.takeIf { it.isNotBlank() }
                        )
                    )
                } else {
                    emitEvent(AgentStreamEvent.Completed(text, stats))
                }
                turn.deferred.complete(
                    TurnOutcome.Finished(
                        finalText = text,
                        isError = failed,
                        errorMessage = line.errorMessage,
                        stats = stats
                    )
                )
            }

            is CodexAppServerLineParser.Line.Notice -> {
                activeTurn?.touch()
                emitEvent(AgentStreamEvent.Notice(line.text))
            }

            is CodexAppServerLineParser.Line.Unknown -> {
                activeTurn?.touch()
                MaxVibesLogger.info(
                    TAG,
                    "unknown App Server message skipped",
                    mapOf(
                        "method" to (line.method ?: "unparseable"),
                        "preview" to rawLine.take(PREVIEW_MAX)
                    )
                )
            }

            CodexAppServerLineParser.Line.Ignored -> activeTurn?.touch()
        }
    }

    private fun extractThreadId(result: JsonElement?): String? {
        val obj = result as? JsonObject ?: return null
        val thread = obj["thread"] as? JsonObject
        return thread?.get("id")?.jsonPrimitive?.contentOrNull
            ?: obj["threadId"]?.jsonPrimitive?.contentOrNull
            ?: obj["id"]?.jsonPrimitive?.contentOrNull
    }

    override fun abort() {
        terminate("abort requested", asAbort = true)
    }

    override fun shutdown() {
        terminate("explicit shutdown", asAbort = false)
    }

    private fun terminate(reason: String, asAbort: Boolean) {
        val turn = activeTurn
        if (turn != null && !turn.deferred.isCompleted) {
            val partial = turn.snapshotText().takeIf { it.isNotBlank() }
            if (asAbort) {
                emitEvent(AgentStreamEvent.Failed("aborted by user", partial))
                turn.deferred.complete(TurnOutcome.Aborted(partial))
            } else {
                turn.deferred.complete(TurnOutcome.Died("Codex App Server stopped: $reason"))
            }
        }

        process?.let { proc ->
            sessionLog?.event("terminate Codex App Server", mapOf("reason" to reason))
            runCatching { proc.toHandle().descendants().forEach { it.destroyForcibly() } }
            runCatching { stdin?.close() }
            runCatching { readerJob?.cancel() }
            runCatching { stderrJob?.cancel() }
            runCatching { proc.destroyForcibly() }
            runCatching { proc.waitFor(2, TimeUnit.SECONDS) }
        }

        process = null
        stdin = null
        readerJob = null
        stderrJob = null
        activeTurn = null
        currentThreadId = null
        spawnConfig = null
        pending.clear()
    }

    private fun stderrSnapshot(): String = synchronized(stderrBuffer) {
        stderrBuffer.toString()
    }

    private fun emitEvent(event: AgentStreamEvent) {
        try {
            streamSink?.emit(event)
        } catch (exception: Exception) {
            MaxVibesLogger.warn(TAG, "stream sink failed", ex = exception)
        }
    }
}
