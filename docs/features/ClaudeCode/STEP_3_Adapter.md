# Step 3 — Plugin adapter : ClaudeCodeProcessAdapter +Settings

## Цель

Реализовать `ClaudeCodePort` в plugin -слое: запуск процесса claude code, stream - JSON I / O через stdin / stdout, lifecycle, обработка ошибок . Это * * самый сложный шаг * * — выделить на него больше времени и обязательно делать с тестами на этапе разработки(
    хоть smoke -test
).

## Затрагиваемые файлы

| Файл | Действие |
|------|----------|
| `maxvibes-plugin/src/main/kotlin/com/maxvibes/plugin/claudecode/ClaudeCodeProcessAdapter.kt` | CREATE |
| `maxvibes-plugin/src/main/kotlin/com/maxvibes/plugin/claudecode/StreamJsonProtocol.kt` | CREATE(helper) |
| `maxvibes-plugin/src/main/kotlin/com/maxvibes/plugin/settings/MaxVibesSettings.kt` | MODIFY(новые поля) |
| `maxvibes-plugin/src/main/kotlin/com/maxvibes/plugin/settings/MaxVibesSettingsPanel.kt` | MODIFY(UI поля) |
| `maxvibes-plugin/src/main/kotlin/com/maxvibes/plugin/settings/MaxVibesSettingsConfigurable.kt` | MODIFY(apply / reset) |

## ВАЖНО — что разведать ПЕРЕД реализацией

        Точные флаги claude CLI могут отличаться от того, что записано в этом плане.До написания кода:

1.В терминале выполнить `claude --help` и `claude --print --help`
        2.Проверить, какие флаги поддерживает stream -JSON режим :
-`--print`(single - shot или streaming?)
-`--input-format stream-json` / `--output-format stream-json`(точное название ?)
-`--append-system-prompt` / `--system-prompt`
-`--resume <session-id>`(формат session id?)
-`--allowedTools ""` или `--disallowed-tools "*"` — чтобы запретить встроенные tools
        3.Запустить минимальный пример руками : `echo '{...}' | claude ...` и записать формат stdout
        4.Зафиксировать * * точную команду * * в начале `ClaudeCodeProcessAdapter.kt` в виде комментария
5.Если что -то из этого плана не совпадает — обновить файл `STEP_3_Adapter.md` с фактическими флагами перед коммитом

## Контекст

-IntelliJ Platform даёт `GeneralCommandLine` +`OSProcessHandler` — предпочтительнее голого `ProcessBuilder`, потому что корректно работает с charset на Windows и интегрируется с IDE -логированием.
-Для stream -JSON каждое сообщение — одна строка JSON с `\n` в конце.На стороне claude code это[stream - json input format](
    https://docs.claude.com/en/docs/claude-code).
-Процесс должен быть * * один на проект * * — храним в `MaxVibesService` (Step 7). Adapter сам по себе не singleton — singleton -владение в DI.
-Concurrency: `send` под `Mutex` чтобы не путать порядок stdin / stdout . Корутины — `Dispatchers.IO` для блокирующих read / write.

## Изменения

### 3.1 `MaxVibesSettings.kt` — добавить поля

Прочитать файл `FULL`.Добавить(с дефолтами, чтобы XML -десериализация старых state не сломалась):

```kotlin
var claudeCodePath: String = "claude"           // в PATH или абсолютный путь
var claudeCodeExtraArgs: String = ""             // через пробел, например "--allowedTools \"\""
var claudeCodeReadTimeoutSec: Int = 120          // таймаут на ожидание одного response
var claudeCodeStartTimeoutSec: Int = 30          // таймаут на запуск процесса
```

### 3.2 `MaxVibesSettingsPanel.kt` +`Configurable.kt` — UI

Добавить отдельную секцию «Claude Code » с четырьмя полями . По образцу существующих секций(
    API key,
    model,
    etc.
).Реализация — стандартный Swing form, как в существующем коде . Поле `claudeCodePath` имеет browse - button через `TextFieldWithBrowseButton`.

### 3.3 Создать `StreamJsonProtocol.kt` — helper для stream - json формата

```kotlin
package com.maxvibes.plugin.claudecode

import kotlinx . serialization . json . Json
        import kotlinx . serialization . json . JsonObject
        import kotlinx . serialization . json . JsonPrimitive
        import kotlinx . serialization . json . contentOrNull
        import kotlinx . serialization . json . jsonObject
        import kotlinx . serialization . json . jsonPrimitive

        /**
         * Helpers for the Claude Code stream-JSON format.
         *
         * Each line on stdout is a single JSON object with a `type` field. We care about:
         *  - `type="system"` with `subtype="init"` — contains `session_id`
         *  - `type="assistant"` — contains the actual response payload (under `message.content[]`)
         *  - `type="result"` — terminal event for the turn
         *
         * On stdin we send `type="user"` messages with our serialized ClipboardRequest as content.
         *
         * Exact field shapes need verification against `claude --help` output (see STEP_3_Adapter.md note).
         */
        internal object StreamJsonProtocol {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Wraps an encoded ClipboardRequest JSON string into a stream-json user event. */
    fun encodeUserEvent(requestJsonText: String): String {
        // Format TBD on Step 3.0 verification. Working assumption:
        // {"type":"user","message":{"role":"user","content":[{"type":"text","text":"<requestJsonText escaped>"}]}}
        TODO("Implement after verifying claude CLI input-format spec")
    }

    /** Returns session id if the line is a system/init event, null otherwise. */
    fun extractSessionId(line: String): String? {
        val obj = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return null
        if (obj["type"]?.jsonPrimitive?.contentOrNull != "system") return null
        if (obj["subtype"]?.jsonPrimitive?.contentOrNull != "init") return null
        return obj["session_id"]?.jsonPrimitive?.contentOrNull
    }

    /** Returns assistant text content if the line is an assistant event, null otherwise. */
    fun extractAssistantText(line: String): String? {
        // Drill into message.content[].text where type=="text". Concatenate.
        TODO("Implement after verifying claude CLI output-format spec")
    }

    /** True if the line is the turn-terminator event (type="result"). */
    fun isTurnEnd(line: String): Boolean {
        val obj = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return false
        return obj["type"]?.jsonPrimitive?.contentOrNull == "result"
    }
}
```

**Важно:** все три `TODO` нужно заполнить на основе реального вывода `claude` CLI.Не угадывать .

### 3.4 Создать `ClaudeCodeProcessAdapter.kt`

Скелет(полный код пишется по этому скелету):

```kotlin
package com.maxvibes.plugin.claudecode

import com . intellij . execution . configurations . GeneralCommandLine
        import com . maxvibes . application . port . output . *
        import com . maxvibes . domain . model . interaction . ClipboardRequest
        import com . maxvibes . plugin . settings . MaxVibesSettings
        import com . maxvibes . plugin . clipboard . JsonClipboardProtocolCodec
        import com . maxvibes . shared . result . Result
        import kotlinx . coroutines . *
        import kotlinx . coroutines . sync . Mutex
        import kotlinx . coroutines . sync . withLock
        import java . io . BufferedReader
        import java . io . BufferedWriter
        import java . nio . charset . StandardCharsets

/**
 * Lifecycle:
 *  - Lazy-starts on first ensureStarted() / send().
 *  - Held alive across multiple sends within one chat session.
 *  - shutdown() destroys the process. Safe to call multiple times.
 *
 * Concurrency: all stdin/stdout access serialized via [sendMutex].
 *
 * COMMAND ASSUMED (verify on STEP_3_Adapter.md note 0):
 *   claude --print --input-format stream-json --output-format stream-json [--resume <id>]
 */
class ClaudeCodeProcessAdapter(
    private val settings: MaxVibesSettings,
    private val codec: JsonClipboardProtocolCodec = JsonClipboardProtocolCodec(),
    private val scope: CoroutineScope
) : ClaudeCodePort {

    private var process: Process? = null
    private var stdin: BufferedWriter? = null
    private var stdout: BufferedReader? = null
    private var stderrCollector: Job? = null
    private val stderrBuffer = StringBuilder()
    private val sendMutex = Mutex()

    override fun isAvailable(): Boolean {
        // Try `claude --version` quickly
        TODO("Run claudeCodePath --version with short timeout and return exitCode==0")
    }

    override suspend fun ensureStarted(resumeSessionId: String?): Result<Unit, ClaudeCodeError> = sendMutex.withLock {
        if (process?.isAlive == true) return Result.Success(Unit)
        if (!isAvailable()) return Result.Failure(ClaudeCodeError.BinaryNotFound)

        val cmd = GeneralCommandLine(settings.claudeCodePath).apply {
            charset = StandardCharsets.UTF_8
            // Base flags — verify on STEP_3.0
            addParameters("--print", "--input-format", "stream-json", "--output-format", "stream-json")
            if (resumeSessionId != null) addParameters("--resume", resumeSessionId)
            // Extra args from settings (split on whitespace, naive — improve later if quoting needed)
            settings.claudeCodeExtraArgs.split(' ').filter { it.isNotBlank() }.forEach { addParameter(it) }
        }

        val proc = try {
            cmd.createProcess()
        } catch (e: Exception) {
            return Result.Failure(ClaudeCodeError.Crashed("Could not spawn process: ${e.message}"))
        }

        process = proc
        stdin = proc.outputStream.bufferedWriter(StandardCharsets.UTF_8)
        stdout = proc.inputStream.bufferedReader(StandardCharsets.UTF_8)
        stderrCollector = scope.launch(Dispatchers.IO) {
            proc.errorStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                lines.forEach { synchronized(stderrBuffer) { stderrBuffer.appendLine(it) } }
            }
        }

        // Optional: wait for the first system/init event to confirm process is healthy.
        // For MVP — assume healthy if process didn't immediately exit.
        delay(50)
        if (!proc.isAlive) {
            val err = synchronized(stderrBuffer) { stderrBuffer.toString() }
            shutdown()
            return if (resumeSessionId != null)
                Result.Failure(ClaudeCodeError.ResumeFailed(resumeSessionId, err))
            else
                Result.Failure(ClaudeCodeError.ProcessFailed(proc.exitValue(), err))
        }

        return Result.Success(Unit)
    }

    override suspend fun send(request: ClipboardRequest): Result<ClaudeCodeSendResult, ClaudeCodeError> =
        sendMutex.withLock {
            val proc = process ?: return Result.Failure(ClaudeCodeError.Crashed("Process not started"))
            if (!proc.isAlive) return Result.Failure(ClaudeCodeError.Crashed("Process died"))

            // 1. Encode request
            val requestJson = codec.encode(request)
            val streamJsonLine = StreamJsonProtocol.encodeUserEvent(requestJson)

            // 2. Write to stdin
            try {
                withContext(Dispatchers.IO) {
                    stdin!!.write(streamJsonLine)
                    stdin!!.newLine()
                    stdin!!.flush()
                }
            } catch (e: Exception) {
                return Result.Failure(ClaudeCodeError.Crashed("stdin write failed: ${e.message}"))
            }

            // 3. Read stdout until isTurnEnd. Collect assistant text. Capture session id.
            val timeoutMs = settings.claudeCodeReadTimeoutSec.toLong() * 1000
            val accumulated = StringBuilder()
            var observedSessionId: String? = null

            val readResult = withTimeoutOrNull(timeoutMs) {
                withContext(Dispatchers.IO) {
                    while (true) {
                        val line = stdout!!.readLine() ?: break
                        StreamJsonProtocol.extractSessionId(line)?.let { observedSessionId = it }
                        StreamJsonProtocol.extractAssistantText(line)?.let { accumulated.append(it) }
                        if (StreamJsonProtocol.isTurnEnd(line)) break
                    }
                }
            } ?: return Result.Failure(ClaudeCodeError.Timeout)

            // 4. Decode
            val response = codec.decode(accumulated.toString())
                ?: return Result.Failure(ClaudeCodeError.ParseFailed("codec.decode returned null"))

            return Result.Success(ClaudeCodeSendResult(response, observedSessionId))
        }

    override fun shutdown() {
        process?.let { proc ->
            runCatching { stdin?.close() }
            runCatching { stdout?.close() }
            runCatching { stderrCollector?.cancel() }
            runCatching { proc.destroyForcibly() }
        }
        process = null
        stdin = null
        stdout = null
        stderrCollector = null
    }
}
```

## Что НЕ делать

-Не подключать adapter в DI на этом шаге — это в Step 7.
-Не пытаться использовать adapter из UI — UI в Step 8.
-Не делать тонкие оптимизации (батчинг, reuse buffers) — для MVP важен только корректный round -trip.

## Тесты

Полноценный unit -тест требует моки процесса — отложить до Step 9.На этом шаге * * обязательный smoke - test вручную * * :

1.После реализации — поставить breakpoint в начале `send`
        2.Из тестового скрипта(или временной test - функции) дернуть adapter с минимальным `ClipboardRequest`
        3.Убедиться, что:
-Процесс стартует
        -В stdin уходит правильно сформированный JSON (логировать)
-Из stdout приходит ожидаемый ответ
-`observedSessionId` извлёкся
        -`shutdown()` корректно убивает процесс (проверить через `ps aux | grep claude`)

## Acceptance criteria

        -[] `./gradlew :maxvibes-plugin:build` зелёный
-[] Smoke -test вручную : ensureStarted +send + получение валидного `ClipboardResponse`
-[] После shutdown процесс не остаётся в системе
        -[] Settings UI показывает новые поля и сохраняет / восстанавливает их
-[] Все три `TODO` в `StreamJsonProtocol.kt` заполнены реальной логикой(не угаданной)
