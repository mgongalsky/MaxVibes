# STEP 9 — Tests + Smoke Test

## Цель

Покрыть критические участки unit -тестами и провести один ручной smoke -test для
        валидации end -to - end потока .

## Unit tests

### 1.`ClaudeCodeActivityTrackerTest`

**Файл:**
`maxvibes-application/src/test/kotlin/com/maxvibes/application/service/ClaudeCodeActivityTrackerTest.kt`

```kotlin
package com.maxvibes.application.service

import com . maxvibes . domain . model . interaction . ClaudeCodeActivity
        import org . junit . jupiter . api . Assertions . *
        import org . junit . jupiter . api . Test

class ClaudeCodeActivityTrackerTest {

    @Test
    fun `update stores activity and notifies listener`() {
        val tracker = ClaudeCodeActivityTracker()
        val received = mutableListOf<Pair<String, ClaudeCodeActivity?>>()
        tracker.addListener { sid, act -> received += sid to act }

        val activity = ClaudeCodeActivity.Started(0L, "abc")
        tracker.update("s1", activity)

        assertEquals(activity, tracker.currentFor("s1"))
        assertEquals(1, received.size)
        assertEquals("s1", received[0].first)
        assertEquals(activity, received[0].second)
    }

    @Test
    fun `clear removes activity and notifies with null`() {
        val tracker = ClaudeCodeActivityTracker()
        val received = mutableListOf<Pair<String, ClaudeCodeActivity?>>()
        tracker.update("s1", ClaudeCodeActivity.Thinking(0L, "hi"))
        tracker.addListener { sid, act -> received += sid to act }

        tracker.clear("s1")

        assertNull(tracker.currentFor("s1"))
        assertEquals(1, received.size)
        assertNull(received[0].second)
    }

    @Test
    fun `clear is no-op when no activity`() {
        val tracker = ClaudeCodeActivityTracker()
        val received = mutableListOf<Any>()
        tracker.addListener { _, act -> received += (act ?: "null") }

        tracker.clear("missing")

        assertTrue(received.isEmpty())
    }

    @Test
    fun `sessions are isolated`() {
        val tracker = ClaudeCodeActivityTracker()
        val a = ClaudeCodeActivity.Started(0L, "a")
        val b = ClaudeCodeActivity.Started(0L, "b")

        tracker.update("s1", a)
        tracker.update("s2", b)

        assertEquals(a, tracker.currentFor("s1"))
        assertEquals(b, tracker.currentFor("s2"))
    }

    @Test
    fun `listener exception does not prevent other listeners`() {
        val tracker = ClaudeCodeActivityTracker()
        val received = mutableListOf<String>()
        tracker.addListener { _, _ -> error("boom") }
        tracker.addListener { sid, _ -> received += sid }

        tracker.update("s1", ClaudeCodeActivity.Thinking(0L, "x"))

        assertEquals(listOf("s1"), received)
    }
}
```

### 2.`StreamJsonProtocolRateLimitTest`

Дополнить существующий тест - файл(если есть) или создать:
`maxvibes-plugin/src/test/kotlin/com/maxvibes/plugin/claudecode/StreamJsonProtocolTest.kt`

```kotlin
@Test
fun `extractRateLimitInfo returns message when present`() {
    val line = """{"type":"rate_limit_event","message":"slow down"}"""
    assertEquals("slow down", StreamJsonProtocol.extractRateLimitInfo(line))
}

@Test
fun `extractRateLimitInfo returns null for non-rate-limit lines`() {
    val line = """{"type":"assistant","message":{"content":[]}}"""
    assertNull(StreamJsonProtocol.extractRateLimitInfo(line))
}

@Test
fun `extractRateLimitInfo falls back to generic notice`() {
    val line = """{"type":"rate_limit_event"}"""
    assertEquals("rate limit notice", StreamJsonProtocol.extractRateLimitInfo(line))
}
```

### 3.`ClaudeCodeInteractionService` — обновить существующие тесты

В фикстурах сервисных тестов добавить конструкторный параметр:

```kotlin
val tracker = ClaudeCodeActivityTracker()
val service = ClaudeCodeInteractionService(
    // ... existing params ...
    activityTracker = tracker
)
```

Добавить хотя бы один новый тест :

```kotlin
@Test
fun `tracker is cleared after successful send`() = runBlocking {
    // ... arrange mocks for happy path ...
    val tracker = ClaudeCodeActivityTracker()
    val service = ClaudeCodeInteractionService(/* ... */, activityTracker = tracker)

    service.handleUserInput(sessionId = "s1", userInput = "hi")

    assertNull(tracker.currentFor("s1"))
}

@Test
fun `tracker is cleared after transport failure`() = runBlocking {
    // ... arrange mocks to return Result.Failure ...
    val tracker = ClaudeCodeActivityTracker()
    val service = ClaudeCodeInteractionService(/* ... */, activityTracker = tracker)

    service.handleUserInput(sessionId = "s1", userInput = "hi")

    assertNull(tracker.currentFor("s1"))
}
```

## Запуск

```bash
    ./ gradlew : maxvibes -application:test
    ./ gradlew : maxvibes -plugin:test
```

Плагинные UI -тесты(если есть) запускать через IntelliJ Run Configuration
        (см.memory: "Run plugin UI tests via IntelliJ IDEA runner to avoid coroutines
debug agent conflicts").

## Smoke Test (manual)

### Подготовка

1.Собрать плагин : `./gradlew :maxvibes-plugin:buildPlugin`
        2.Запустить sandbox -IDE: `./gradlew :maxvibes-plugin:runIde`
3.Открыть testVibes проект.4.В MaxVibes toolwindow выбрать `CLAUDE_CODE` mode .

### Сценарий 1 — happy path

        1.Послать заведомо долгий запрос : "Опиши архитектуру проекта подробно, какие
паттерны используются ".
2.* * Ожидание:** в течение 1 - 2 секунд под conversation появляется bubble
        `🤖 Claude Code started · (Ns)`.3.* * Ожидание:** через ~5 секунд label меняется на `🤖 Claude Code is thinking`,
точки пульсируют (1→2→3→4→1 каждые 500 ms), elapsed тикает в секундах .
4.* * Ожидание:** при наличии текстовых assistant chunk'ов (НЕ JSON) — под
главной строкой dimmed preview ~80 char .
5.* * Ожидание:** при финальном response — bubble мгновенно исчезает, появляется
обычный assistant message.

### Сценарий 2 — error path

        1.Положить заведомо неработающий путь в `MaxVibes settings → Claude Code path`
        (например `/nonexistent/claude`).2.Послать запрос .
3.* * Ожидание:** bubble НЕ должен залипнуть . Если он мелькнул — должен исчезнуть
сразу после transport error message.

### Сценарий 3 — переключение режимов

        1.В режиме CLAUDE_CODE с активным send переключиться на API mode .
2.* * Ожидание:** bubble корректно исчезает(
    либо при следующем render, либо
            при clear на завершении send
).

### Сценарий 4 — закрытие toolwindow

        1.Послать долгий запрос.2.Во время ожидания закрыть toolwindow.3.Снова открыть .
4.* * Ожидание:** Swing Timer 'ы остановлены, нет утечек, нет двойных listener' ов
        при повторном открытии.Проверить через `Help → Diagnostic Tools → Activity Monitor`
что нет accumulating threads .

### Чеклист smoke -test'а

-[] Bubble появляется при старте send
        -[] Точки пульсируют(≤500 ms интервал)
-[] Elapsed time тикает (видимо каждую секунду)
-[] Preview скрыт для JSON chunk 'ов
-[] Preview показан для человеческого текста
        -[] Bubble исчезает при completion
-[] Bubble исчезает при transport error
        -[] Bubble исчезает при reset
-[] Закрытие toolwindow → нет утечек

## Commit

```
test: cover live activity flow
```
