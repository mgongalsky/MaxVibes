# Claude Code Live Activity — Master Plan

## Проблема

Claude Code mode работает не быстро : один send может занимать минуты . Сейчас
        UI просто "висит" — никакой обратной связи о том, что процесс жив и работает.Пользователь не понимает, всё ли сломалось или просто долго .

В то же время `ClaudeCodeProcessAdapter` уже * * получает промежуточные события * *
из stream -JSON потока (`system/init`, `assistant` chunks, `rate_limit_event`),
но глотает их и отдаёт наверх только финальный результат.

## Цель

Показать пользователю transient "live bubble" под последним сообщением во время
        обработки Claude Code запроса . Bubble :
-появляется сразу после старта send,
-показывает минимальную анимацию(пульсирующие точки) + elapsed time,
-опционально показывает превью последнего assistant chunk 'а,
-исчезает при завершении / ошибке.

**Не делаем сейчас:** cancel - кнопку, полный лог активности в footer'е финального
ответа(это вторая фича, отдельно).

## Архитектура

```
┌──────────────────────────────────────────────────────────┐
│  UI Layer (maxvibes - plugin)                              │
│  ┌────────────────────────────────────────────────────┐  │
│  │ ChatPanel                                          │  │
│  │   ├─ live bubble (JPanel под conversationPanel)    │  │
│  │   ├─ Swing Timer (500 ms, точки)                    │  │
│  │   └─ Swing Timer (200 ms, polls tracker)            │  │
│  └────────────────────────────────────────────────────┘  │
│            ▲ subscribes(LiveActivityListener)           │
├──────────────────────────────────────────────────────────┤
│  Application Layer │
│  ┌────────────────────────────────────────────────────┐  │
│  │ ClaudeCodeActivityTracker                          │  │
│  │   ├─ currentActivity: ClaudeCodeActivity? per ses │  │
│  │   ├─ update(sessionId, activity)                   │  │
│  │   ├─ clear(sessionId)                              │  │
│  │   └─ listeners                                     │  │
│  └────────────────────────────────────────────────────┘  │
│            ▲ called from service.doSend()                │
│  ┌────────────────────────────────────────────────────┐  │
│  │ ClaudeCodeInteractionService                       │  │
│  │   └─ doSend passes onActivity lambda to port . send │  │
│  └────────────────────────────────────────────────────┘  │
├──────────────────────────────────────────────────────────┤
│  Port(output)                                           │
│  ┌────────────────────────────────────────────────────┐  │
│  │ ClaudeCodePort.send(req, onActivity = { ... })     │  │
│  └────────────────────────────────────────────────────┘  │
├──────────────────────────────────────────────────────────┤
│  Adapter(maxvibes - plugin)                               │
│  ┌────────────────────────────────────────────────────┐  │
│  │ ClaudeCodeProcessAdapter                           │  │
│  │   └─ stdout loop :                                  │  │
│  │       extractAssistantText → onActivity(Thinking)  │  │
│  │       extractSessionId     → onActivity(Started)   │  │
│  │       isRateLimit          → onActivity(RateLimit) │  │
│  └────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────┘
```

## Доменная модель

        Новый sealed class `ClaudeCodeActivity` в `domain/model/interaction/`:

```kotlin
sealed class ClaudeCodeActivity {
    abstract val startedAtMs: Long

    data class Started(override val startedAtMs: Long, val sessionId: String?) : ClaudeCodeActivity()
    data class Thinking(override val startedAtMs: Long, val previewText: String) : ClaudeCodeActivity()
    data class RateLimit(override val startedAtMs: Long, val info: String) : ClaudeCodeActivity()
}
```

`startedAtMs` — wall - clock начала текущего send, чтобы UI мог показывать elapsed.

## Шаги

| Шаг | Файл | Что делает |
|-----|------|-----------|
| 1 | STEP_1_Domain.md | Sealed class `ClaudeCodeActivity` в domain |
| 2 | STEP_2_Port.md | Расширение `ClaudeCodePort.send` параметром `onActivity` (default = `{}`) |
| 3 | STEP_3_StreamJsonProtocol.md | Хелпер `isRateLimitEvent` в `StreamJsonProtocol` |
| 4 | STEP_4_Adapter.md | Emit activity -событий из stdout - loop в `ClaudeCodeProcessAdapter` |
| 5 | STEP_5_Tracker.md | Application - сервис `ClaudeCodeActivityTracker` |
| 6 | STEP_6_Service.md | Wire callback в `ClaudeCodeInteractionService.doSend` |
| 7 | STEP_7_UI.md | `ChatPanelState.liveActivity` + live bubble +анимация |
| 8 | STEP_8_DI.md | Регистрация `ClaudeCodeActivityTracker` в `MaxVibesService` |
| 9 | STEP_9_Tests.md | Unit - тесты + smoke - test чеклист |

## Ключевые принципы

        1.* * Backward - compatible * *: `onActivity` имеет default `{}`, существующие тесты порта / адаптера не ломаются.2.* * Transient state * * : live activity нигде не персистится . После рестарта IDE её нет — это OK.3.* * Чистая Clean Arch * *: домен(
    Activity
) → port(callback) → adapter(emit) → application(Tracker) → UI(listener).4.* * Throttle на UI * *: Swing Timer 200 ms читает последнее состояние из tracker'а; адаптер пишет в tracker через service без своих таймеров.
5.* * SessionManager не трогаем * *: live activity — это НЕ session status.Свой контракт у status persisted, у activity transient.Разделяем.

## Edge cases (учтены в шагах)

-* * JSON - фрагменты в assistant chunks * * : если preview начинается с `{` или содержит `\"message\":` — скрываем превью, показываем только пульс + elapsed.STEP_7.
-* * Чанки приходят слишком часто * * : throttle на UI -уровне 200 ms . STEP_7 .
-* * Send упал / timeout * * : `ClaudeCodeInteractionService.doSend` гарантированно вызывает `tracker.clear(sessionId)` в `finally`.STEP_6.
-* * Закрытие tool window во время операции * * : ChatPanel снимает listener в `dispose()`, Swing Timer 'ы останавливаются. STEP_7.
-* * Параллельные sends разных сессий * * : tracker per - session, не глобальный . STEP_5 .

## Готовность к agent - execution

Каждый STEP_ *.md содержит точные file paths, code snippets, Gradle commands и
commit message . Шаги независимы в смысле компиляции (после каждого код собирается),
но зависят по логике в указанном порядке.

## Commit hierarchy

```
feat: add ClaudeCodeActivity domain model
        feat: extend ClaudeCodePort with onActivity callback
feat: add isRateLimitEvent to StreamJsonProtocol
        feat: emit live activity events from ClaudeCodeProcessAdapter
        feat: add ClaudeCodeActivityTracker application service
        feat: wire live activity callback in ClaudeCodeInteractionService
        feat: show live activity bubble in ChatPanel
        feat: register ClaudeCodeActivityTracker in MaxVibesService
        test: cover live activity flow
```
