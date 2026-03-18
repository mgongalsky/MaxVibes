# Step 5: Обновить тесты

## Контекст

После Step 4 поведение `redoLastRequest` изменилось:
- Сценарий A: sessionStateOwner совпадает → прямой вызов generateAndCopyJson
- Сценарий B: sessionStateOwner не совпадает → читаем домен, пересобираем workspace

## Файл для изменения

**Путь:** `maxvibes-application/src/test/kotlin/com/maxvibes/application/service/ClipboardInteractionServiceTest.kt`

## Вспомогательный билдер в тестах

```kotlin
/**
* Builds a minimal ChatSession for redoLastRequest Scenario B tests.
*/
private fun buildDomainSession(
clipboardStatus: ClipboardSessionStatus,
lastUserMessage: String,
lastAssistantRequestedFiles: List<String> = emptyList()
): ChatSession {
val messages = buildList {
add(ChatMessage(role = MessageRole.USER, content = lastUserMessage))
if (lastAssistantRequestedFiles.isNotEmpty()) {
add(ChatMessage(
role = MessageRole.ASSISTANT,
content = "Need files",
requestedFiles = lastAssistantRequestedFiles
))
}
}
return mockk<ChatSession>(relaxed = true).also {
every { it.clipboardStatus } returns clipboardStatus
every { it.messages } returns messages
every { it.copy(messages = any()) } returns it
}
}
```

## Тесты которые УДАЛЯЕМ

- `redoLastRequest - wrong sessionId returns Error when state belongs to different session`
→ заменяется на Scenario B тест где redo работает
- `redoLastRequest - after two sessions only the latest owner succeeds`
→ логика изменилась: теперь оба работают

## Новые тесты

### Scenario A: sessionStateOwner совпадает — прямой redo

```kotlin
@Test
fun `redoLastRequest scenario A - reuses existing workspace when owner matches`(): Unit = runBlocking {
stubProjectContext()
stubGatherFiles(emptyMap())
// startTask устанавливает sessionStateOwner = SESSION_ID
service.startTask(sessionId = SESSION_ID, currentMessage = "Task")

// Redo для той же сессии — должен сработать без обращения к репозиторию
stubGatherFiles(emptyMap())
val result = service.redoLastRequest(sessionId = SESSION_ID, globalContextFiles = emptyList())

assertInstanceOf(ClipboardStepResult.WaitingForResponse::class.java, result)
// Репозиторий не должен был вызываться — Scenario A
verify(exactly = 0) { chatSessionRepository.getSessionById(any()) }
}
```

### Scenario B: другая сессия — пересборка из домена

```kotlin
@Test
fun `redoLastRequest scenario B - rebuilds workspace from domain when owner differs`(): Unit = runBlocking {
val SESSION_A = "session-a"
val SESSION_B = "session-b"

// Session B owns the workspace
stubProjectContext()
stubGatherFiles(emptyMap())
service.startTask(sessionId = SESSION_B, currentMessage = "Task B")

// Domain has Session A with AWAITING_PASTE
every { chatSessionRepository.getSessionById(SESSION_A) } returns
buildDomainSession(
clipboardStatus = ClipboardSessionStatus.AWAITING_PASTE,
lastUserMessage = "Task A"
)
stubProjectContext()
stubGatherFiles(emptyMap())

val result = service.redoLastRequest(sessionId = SESSION_A, globalContextFiles = emptyList())

assertInstanceOf(
ClipboardStepResult.WaitingForResponse::class.java,
result,
"Scenario B must succeed for session A even though session B owns the workspace"
)
}
```

### Scenario B: requestedFiles из последнего ответа передаются в gather

```kotlin
@Test
fun `redoLastRequest scenario B - gathers files from last assistant requestedFiles`(): Unit = runBlocking {
val SESSION_B = "session-b"
stubProjectContext()
stubGatherFiles(emptyMap())
service.startTask(sessionId = SESSION_B, currentMessage = "Task B")

every { chatSessionRepository.getSessionById(SESSION_ID) } returns
buildDomainSession(
clipboardStatus = ClipboardSessionStatus.AWAITING_PASTE,
lastUserMessage = "Task A",
lastAssistantRequestedFiles = listOf("src/Foo.kt", "src/Bar.kt")
)
stubProjectContext()
stubGatherFiles(emptyMap())

service.redoLastRequest(sessionId = SESSION_ID, globalContextFiles = emptyList())

coVerify {
contextProvider.gatherFiles(
match { it.containsAll(listOf("src/Foo.kt", "src/Bar.kt")) }
)
}
}
```

### IDLE сессия → Error (оба сценария)

```kotlin
@Test
fun `redoLastRequest - returns Error when session status is IDLE`(): Unit = runBlocking {
every { chatSessionRepository.getSessionById(SESSION_ID) } returns
buildDomainSession(
clipboardStatus = ClipboardSessionStatus.IDLE,
lastUserMessage = "some message"
)

// sessionStateOwner != SESSION_ID (не вызывали startTask для этой сессии)
val result = service.redoLastRequest(sessionId = SESSION_ID, globalContextFiles = emptyList())

assertInstanceOf(ClipboardStepResult.Error::class.java, result)
}
```

## Прогон

```bash
./gradlew :maxvibes-application:test
```

Все тесты зелёные.

## Коммит

```
test: update redoLastRequest tests for two-scenario behaviour
```
