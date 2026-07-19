# Step 2 — Application port : ClaudeCodePort +ClaudeCodeError

## Цель

Объявить порт для общения с процессом Claude Code на уровне application.Без реализации — это интерфейс, реализация в Step 3.

## Затрагиваемые файлы

| Файл | Действие |
|------|----------|
| `maxvibes-application/src/main/kotlin/com/maxvibes/application/port/output/ClaudeCodePort.kt` | CREATE |

## Контекст

Порт инкапсулирует всё, что связано с локальным процессом claude . Сервис уровня application не должен знать про `ProcessBuilder`, stdout - парсинг и т.д.— он только вызывает `send(request)` и получает `Result<ClipboardResponse, ClaudeCodeError>` .

Ключевое отличие от `ClipboardPort` :
-`ClipboardPort.copyRequestToClipboard` — fire - and - forget, response придёт позже отдельным вызовом `parseResponse(rawText)` .
-`ClaudeCodePort.send` — **suspend, blocking * * до получения response . Это и есть выигрыш от автоматического транспорта .

## Изменения

### 2.1 Создать `ClaudeCodePort.kt`

Полный текст файла:

```kotlin
package com.maxvibes.application.port.output

import com . maxvibes . domain . model . interaction . ClipboardRequest
        import com . maxvibes . domain . model . interaction . ClipboardResponse
        import com . maxvibes . shared . result . Result

/**
 * Port for interacting with a local Claude Code CLI process.
 *
 * Implementations spawn the `claude` CLI in stream-JSON mode and exchange JSON
 * messages over stdin/stdout. The same [ClipboardRequest] / [ClipboardResponse]
 * types as the clipboard mode are used — the only difference is the transport.
 *
 * Lifecycle:
 *  - [ensureStarted] — idempotent, lazy-starts the process if not running
 *  - [send] — writes request to stdin, reads response from stdout
 *  - [shutdown] — terminates the process; safe to call multiple times
 *  - [isAvailable] — quick check whether the binary is reachable
 *
 * Implemented in the plugin layer by `ClaudeCodeProcessAdapter`.
 */
interface ClaudeCodePort {

    /**
     * Ensures the underlying process is running.
     *
     * If [resumeSessionId] is non-null, the process is started with
     * `claude --resume <id>` to restore a prior session. If the resume fails
     * (binary returns non-zero or session id is unknown to claude),
     * implementations should return [ClaudeCodeError.ResumeFailed] without
     * automatically falling back — the caller decides whether to retry with
     * `ensureStarted(resumeSessionId = null)`.
     *
     * @param resumeSessionId optional session id from a previous run
     * @return Success if the process is running and ready to receive input
     */
    suspend fun ensureStarted(resumeSessionId: String? = null): Result<Unit, ClaudeCodeError>

    /**
     * Sends [request] to the Claude Code process and waits for the next response.
     *
     * The request is encoded via `JsonClipboardProtocolCodec.encode` and written
     * to stdin as a single line. The implementation reads stdout until a complete
     * assistant message is received, then decodes it via
     * `JsonClipboardProtocolCodec.decode`.
     *
     * Returns the claude session id (if observed in this exchange) so the caller
     * can persist it on [com.maxvibes.domain.model.chat.ChatSession].
     */
    suspend fun send(request: ClipboardRequest): Result<ClaudeCodeSendResult, ClaudeCodeError>

    /**
     * Forcefully terminates the process. Safe to call when not running.
     */
    fun shutdown()

    /** True if the configured binary path resolves to an executable file. */
    fun isAvailable(): Boolean
}

/**
 * Result of a single [ClaudeCodePort.send] call.
 *
 * @property response decoded protocol response
 * @property observedSessionId claude-side session id seen in this exchange,
 *           if any. Null when the implementation could not extract it.
 */
data class ClaudeCodeSendResult(
    val response: ClipboardResponse,
    val observedSessionId: String?
)

/** Domain-level errors for the Claude Code transport. */
sealed class ClaudeCodeError(val message: String) {
    /** The configured binary path is missing or not executable. */
    object BinaryNotFound : ClaudeCodeError("Claude Code binary not found")

    /** The process exited unexpectedly with a non-zero code. */
    data class ProcessFailed(val exitCode: Int, val stderr: String) :
        ClaudeCodeError("Claude Code process failed (exit $exitCode): $stderr")

    /** No response received within the configured read timeout. */
    object Timeout : ClaudeCodeError("Timed out waiting for Claude Code response")

    /** A response was received but could not be parsed as a protocol message. */
    data class ParseFailed(val detail: String) :
        ClaudeCodeError("Failed to parse Claude Code response: $detail")

    /** `--resume <id>` returned an error. Caller may retry without resume. */
    data class ResumeFailed(val sessionId: String, val detail: String) :
        ClaudeCodeError("Failed to resume session $sessionId: $detail")

    /** The process crashed mid-exchange. */
    data class Crashed(val cause: String) :
        ClaudeCodeError("Claude Code process crashed: $cause")
}
```

## Что НЕ делать

-Не пытаться реализовать порт в этом шаге — реализация в Step 3.
-Не подключать порт ни в какой DI на этом шаге .
-Не добавлять в порт методов, которые ещё не нужны (например, `cancel()` — добавим, когда появится auto - loop с долгими операциями).

## Тесты

Интерфейс — тестов нет . Если в проекте есть конвенция « у каждого порта свой smoke -test через mock» — отложить до Step 5, где сервис будет тестироваться с MockK -моком этого порта.

## Acceptance criteria

        -[] `./gradlew :maxvibes-application:build` зелёный
-[] Файл `ClaudeCodePort.kt` находится в `port/output/` рядом с другими портами
        -[] Никакие существующие тесты не упали
