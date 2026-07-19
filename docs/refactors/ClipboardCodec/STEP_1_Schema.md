# Step 1: ClipboardRequestSchema + ClipboardProtocolCodec interface

## Цель шага

        Создать два новых файла в `maxvibes-application` — без изменений существующего кода.Плагин после шага компилируется и работает идентично.

## Контекст

Текущая проблема : строки -имена полей JSON(
    `"_protocol"`,
    `"message"`,
    `"requestedFiles"`,
    `"modifications"`, ...) захардкожены в двух методах `ClipboardAdapter`:
-`serializeRequest()` — при сборке JSON
-`parseUnifiedResponse()` / `parseModification()` — при разборе

        Если нужно добавить поле или переименовать — ищешь по всему файлу, легко промахнуться .

## Что делаем

### 1.Создать `ClipboardRequestSchema.kt`

**Путь * *: `maxvibes-application/src/main/kotlin/com/maxvibes/application/port/output/ClipboardRequestSchema.kt`

Объект - хранилище всех строковых констант протокола: имена полей JSON, значения маркеров .

```kotlin
package com.maxvibes.application.port.output

/**
 * Константы JSON-протокола обмена с LLM через буфер обмена.
 *
 * Все строковые имена полей сосредоточены здесь.
 * При добавлении нового поля — добавить константу сюда,
 * затем использовать её в JsonClipboardProtocolCodec.
 */
object ClipboardRequestSchema {

    // ── Метаполя (служебные маркеры для LLM) ──────────────────────────
    const val META_PROTOCOL = "_protocol"
    const val META_RESPONSE_FORMAT = "_responseFormat"

    const val PROTOCOL_MARKER =
        "MaxVibes IDE Plugin — respond with JSON only, do NOT use tools/artifacts/computer"
    const val RESPONSE_FORMAT_HINT =
        """Respond with ONLY a raw JSON object: {"message": "...", "requestedFiles": [...], "modifications": []}"""

    // ── Поля запроса ──────────────────────────────────────────────────
    const val FIELD_SYSTEM_INSTRUCTION = "systemInstruction"
    const val FIELD_TASK = "task"
    const val FIELD_PROJECT_NAME = "projectName"
    const val FIELD_PLAN_ONLY = "planOnly"
    const val FIELD_FILE_TREE = "fileTree"
    const val FIELD_FILES = "files"
    const val FIELD_PREVIOUSLY_GATHERED = "previouslyGatheredFiles"
    const val FIELD_CHAT_HISTORY = "chatHistory"
    const val FIELD_ERROR_TRACE = "errorTrace"
    const val FIELD_IDE_ERRORS = "ideErrors"

    // ── Поля истории ──────────────────────────────────────────────────
    const val HISTORY_ROLE = "role"
    const val HISTORY_CONTENT = "content"

    // ── Поля ответа ───────────────────────────────────────────────────
    const val RESP_MESSAGE = "message"
    const val RESP_REASONING = "reasoning"
    const val RESP_REQUESTED_FILES = "requestedFiles"
    const val RESP_MODIFICATIONS = "modifications"
    const val RESP_COMMIT_MESSAGE = "commitMessage"

    // ── Поля модификации ──────────────────────────────────────────────
    const val MOD_TYPE = "type"
    const val MOD_PATH = "path"
    const val MOD_CONTENT = "content"
    const val MOD_ELEMENT_KIND = "elementKind"
    const val MOD_POSITION = "position"
    const val MOD_IMPORT_PATH = "importPath"

    // ── Значения по умолчанию ─────────────────────────────────────────
    const val DEFAULT_ELEMENT_KIND = "FILE"
    const val DEFAULT_POSITION = "LAST_CHILD"
}
```

### 2.Создать `ClipboardProtocolCodec.kt`

**Путь * *: `maxvibes-application/src/main/kotlin/com/maxvibes/application/port/output/ClipboardProtocolCodec.kt`

Интерфейс порта . Живёт в application -слое — не зависит от IDE, тестируется напрямую .

```kotlin
package com.maxvibes.application.port.output

import com . maxvibes . domain . model . interaction . ClipboardRequest
        import com . maxvibes . domain . model . interaction . ClipboardResponse

/**
 * Кодек для сериализации/десериализации JSON-протокола Clipboard mode.
 *
 * Реализуется в plugin-слое ([JsonClipboardProtocolCodec]).
 * Не содержит зависимостей на IDE или системный буфер обмена —
 * только чистая логика JSON ↔ доменные модели.
 *
 * Это позволяет тестировать протокол unit-тестами без мока IDE.
 */
interface ClipboardProtocolCodec {

    /**
     * Сериализует [ClipboardRequest] в JSON-строку для отправки в LLM.
     *
     * Результат — pretty-printed JSON с метаполями `_protocol` и `_responseFormat`.
     */
    fun encode(request: ClipboardRequest): String

    /**
     * Десериализует сырой текст ответа LLM в [ClipboardResponse].
     *
     * Умеет разбирать:
     * - raw JSON (`{...}`)
     * - JSON в блоке ` ```json ` ... ` ``` `
     * - JSON, встроенный в произвольный текст
     * - plain text (без JSON) — возвращает ClipboardResponse(message = text)
     *
     * @return распарсенный ответ, или `null` если JSON не найден и текст пустой
     */
    fun decode(rawText: String): ClipboardResponse?
}
```

## Проверка

### Smoke test
        1.`./gradlew :maxvibes-application:compileKotlin` — должно собраться без ошибок
        2.Запустить плагин в IDE (`./gradlew runIde`) — поведение не изменилось

### Unit тесты
        На этом шаге тестов нет — только интерфейсы . Тесты будут на Шаге 2.

## Commit message

```
feat: add ClipboardRequestSchema constants and ClipboardProtocolCodec interface
```

## Следующий шаг

→ [STEP_2_Codec.md](STEP_2_Codec.md) — реализация `JsonClipboardProtocolCodec` и тесты
