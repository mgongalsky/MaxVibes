# Step 1 — Domain: InteractionMode + AWAITING_APPROVE + ChatSession поля

## Цель

Расширить domain -уровень новыми перечислениями и полями для Claude Code режима.Никакой логики — только данные.Шаг должен компилироваться и не ломать существующих тестов .

## Затрагиваемые файлы

| Файл | Действие |
|------|----------|
| `maxvibes-domain/src/main/kotlin/com/maxvibes/domain/model/interaction/InteractionMode.kt` | MODIFY(добавить enum -литерал) |
| `maxvibes-domain/src/main/kotlin/com/maxvibes/domain/model/interaction/ClipboardSessionStatus.kt` | MODIFY(добавить enum -литерал) |
| `maxvibes-domain/src/main/kotlin/com/maxvibes/domain/model/chat/ChatSession.kt` | MODIFY(добавить два поля) |

## Контекст

-`InteractionMode` сейчас содержит `API`, `CLIPBOARD`, `CHEAP_API`.Нужен четвёртый — `CLAUDE_CODE` .
-`ClipboardSessionStatus` сейчас : `IDLE`, `SESSION_ACTIVE`, `AWAITING_PASTE`.`AWAITING_PASTE` — clipboard - специфичный(« ждём, пока юзер вставит ответ »). Для Claude Code нужен новый статус — `AWAITING_APPROVE` («получили ответ от claude, ждём Approve чтобы собрать файлы и продолжить»).
-`ChatSession` хранит per - session - state.Для Claude Code режима нужно помнить :
-`claudeCodeSessionId` — id сессии claude code (для `--resume` после restart)
-`claudeCodeNeedsFullContext` — flag, что следующий send должен быть с полным контекстом (история + system prompt +file tree).Ставится в `true` при создании сессии и сбрасывается в `false` после первого успешного send . Также ставится в `true` если был fallback после неудачного `--resume`.

## Изменения

### 1.1 `InteractionMode.kt`

        Добавить четвёртый литерал в существующий enum :

```kotlin
enum class InteractionMode {
    API,
    CLIPBOARD,
    CHEAP_API,

    /**
     * Claude Code (local CLI process). MaxVibes spawns `claude` CLI in stream-JSON mode
     * and exchanges request/response JSON identical to CLIPBOARD mode.
     * All code modifications and context gathering are performed by the plugin —
     * Claude Code only generates JSON responses.
     */
    CLAUDE_CODE
}
```

### 1.2 `ClipboardSessionStatus.kt`

        Добавить четвёртый литерал в существующий enum (имя файла и enum остаются `Clipboard*` — переименование отложено):

```kotlin
enum class ClipboardSessionStatus {
    IDLE,
    SESSION_ACTIVE,
    AWAITING_PASTE,

    /**
     * Claude Code mode only.
     *
     * The plugin received a response from the Claude Code process and is waiting
     * for the user to press Approve to either:
     *  - gather requested files and send the next request, or
     *  - confirm and apply modifications (when auto-apply is disabled).
     *
     * Distinct from [AWAITING_PASTE] — there is no clipboard step here, the response
     * is already in plugin memory; only user confirmation is needed to proceed.
     */
    AWAITING_APPROVE
}
```

### 1.3 `ChatSession.kt`

        Прочитать файл целиком(`FULL`) перед изменением — это data class с persistable полями, нельзя просто дописать сбоку . Добавить два nullable поля в конструктор:

```kotlin
/**
 * Claude Code session id returned by the CLI's first system event.
 * Used for `claude --resume <id>` after IDE/process restart.
 * Null if no Claude Code exchange has happened yet for this session.
 */
var claudeCodeSessionId: String? = null,

/**
 * When true, the next Claude Code send must include the full context
 * (system prompt, history, file tree). Set to true:
 *  - when the session is created (no claude-side state yet),
 *  - after a failed `--resume` attempt that fell back to a fresh process.
 * Cleared to false after the first successful send.
 */
var claudeCodeNeedsFullContext: Boolean = true
```

Если `ChatSession` сериализуется через IntelliJ XML serializer — оба поля имеют дефолты, так что отсутствие в существующих XML не сломает десериализацию(
    атрибуты,
    равные дефолту,
    не пишутся — это ожидаемое поведение IntelliJ XML serializer
).

## Что НЕ менять

-`ClipboardSessionManager` пока не трогаем — его доработка в Step 6.
-Никакие сервисы не должны новые литералы обрабатывать на этом шаге — это всё в Step 5 / 6 / 8.
-Существующие транзишены в `ClipboardSessionManager` остаются как есть.

## Тесты

На этом шаге автотесты не пишем — нет логики.Если в проекте уже есть `ChatSessionTest` или подобное — прогнать, убедиться что зелёный.

## Acceptance criteria

        -[] `./gradlew :maxvibes-domain:build` зелёный
-[] `./gradlew :maxvibes-application:build` зелёный
-[] `./gradlew test` зелёный(никакой `when (mode)` без `else` не сломан)
-[] Если после добавления `CLAUDE_CODE` компилятор ругается на non - exhaustive `when` где - то — добавить `else -> { /* TODO Step 5/8 */ }` ветку, не реализовывать поведение

## Что после этого шага должно работать

        Ничего нового видимо не работает — это чистый data - shift.Цель — подготовить типы, на которые опираются последующие шаги.
