# Step 4: Переписать redoLastRequest — два сценария

## Контекст

После Steps 1–3 домен хранит `requestedFiles` в последнем ASSISTANT-сообщении.
Теперь переписываем `redoLastRequest` с двумя сценариями.

**Важно:** Copy JSON не запускает никаких новых процессов, не добавляет сообщений
в историю, не делает переходов state machine. Он просто берёт готовые данные
и вызывает `generateAndCopyJson()` — тот же метод что вызывается при Generate.

## Два сценария

**Сценарий A** — sessionState в памяти принадлежит нужной сессии:
```
sessionStateOwner == sessionId && sessionState != null
→ generateAndCopyJson() напрямую с существующим state
```

**Сценарий B** — sessionState принадлежит другой сессии или отсутствует:
```
getSessionById(sessionId) из домена
→ проверить clipboardStatus != IDLE
→ getProjectContext() — свежий fileTree (обязательно)
→ последнее USER-сообщение из домена
→ requestedFiles из последнего ASSISTANT-сообщения
→ собрать минимальный ClipboardSessionState
→ gatherRequestedFiles() + generateAndCopyJson()
```

## Файл для изменения

**Путь:** `maxvibes-application/src/main/kotlin/com/maxvibes/application/service/ClipboardInteractionService.kt`

## Новая реализация

```kotlin
/**
* Re-generates and copies the clipboard JSON for the given session.
*
* Does NOT add messages to history, does NOT trigger state machine transitions.
* Simply rebuilds the JSON payload and copies it to the clipboard — same as Generate,
* but without starting a new dialog turn.
*
* Two scenarios:
* - **A**: In-memory workspace belongs to this session → reuse it, call [generateAndCopyJson] directly.
* - **B**: Workspace belongs to another session → rebuild minimal workspace from domain
*   (fresh projectContext + last user message + last requested files), then call [generateAndCopyJson].
*
* Returns [ClipboardStepResult.Error] if:
* - session not found in repository
* - session status is IDLE (Generate was never called)
* - session has no USER messages (nothing to regenerate)
* - project context cannot be loaded (Scenario B only)
*
* @param sessionId          The ID of the chat session to redo.
* @param globalContextFiles Paths to always include as fresh files.
*/
suspend fun redoLastRequest(
sessionId: String,
globalContextFiles: List<String>
): ClipboardStepResult {

// --- Scenario A: workspace already belongs to this session ---
if (sessionStateOwner == sessionId && sessionState != null) {
log("Redo scenario A: reusing existing workspace for session $sessionId")
val freshFiles = gatherRequestedFiles(globalContextFiles) ?: emptyMap()
return generateAndCopyJson(
sessionId = sessionId,
freshFiles = freshFiles,
isFirstMessage = false
)
}

// --- Scenario B: workspace belongs to another session, rebuild from domain ---
log("Redo scenario B: rebuilding workspace from domain for session $sessionId")

val session = chatSessionRepository.getSessionById(sessionId)
?: return error("Session not found: $sessionId")

if (session.clipboardStatus == ClipboardSessionStatus.IDLE) {
return error("No active clipboard session for this chat.")
}

val lastUserMessage = session.messages
.lastOrNull { it.role == MessageRole.USER }
?.content
?: return error("No user message found in session $sessionId")

// File paths from the last LLM response only
val lastRequestedFiles = session.messages
.lastOrNull { it.role == MessageRole.ASSISTANT && it.requestedFiles.isNotEmpty() }
?.requestedFiles
?: emptyList()

// Fresh project context is required to build the file tree in the JSON
val projectContextResult = contextProvider.getProjectContext()
if (projectContextResult is Result.Failure) {
return error("Failed to get project context: ${projectContextResult.error.message}")
}
val projectContext = (projectContextResult as Result.Success).value
val prompts = promptPort?.getPrompts() ?: PromptTemplates.EMPTY

// Build minimal workspace — only what generateAndCopyJson needs
sessionState = ClipboardSessionState(
currentMessage = lastUserMessage,
projectContext = projectContext,
dialogHistory = session.messages
.filter { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT }
.map { ChatMessageDTO(role = if (it.role == MessageRole.USER) ChatRole.USER else ChatRole.ASSISTANT, content = it.content) }
.toMutableList(),
prompts = prompts,
allGatheredFiles = mutableMapOf(),
planOnly = false
)
sessionStateOwner = sessionId

val filesToGather = (globalContextFiles + lastRequestedFiles).distinct()
val freshFiles = gatherRequestedFiles(filesToGather) ?: emptyMap()
return generateAndCopyJson(
sessionId = sessionId,
freshFiles = freshFiles,
isFirstMessage = false
)
}
```

## Проверка компиляции

```bash
./gradlew :maxvibes-application:compileKotlin
./gradlew :maxvibes-plugin:compileKotlin
```

## Ручное тестирование

**Сценарий 1 — Scenario A (основной, быстрый путь):**
1. Clipboard mode → Generate → AWAITING_PASTE
2. Не переключаясь — нажать Copy JSON
3. Background task запускается, тот же JSON в буфере

**Сценарий 2 — Scenario B (после переключения):**
1. Сессия A: Generate → ЛЛМ запросил `src/Foo.kt` → вставить ответ (чтобы requestedFiles сохранились)
2. Сессия B: Generate → sessionStateOwner = B
3. Переключиться на A → Copy JSON
4. Background task запускается, JSON содержит `src/Foo.kt`

**Сценарий 3 — IDLE сессия:**
1. Новая сессия → Copy JSON кнопка скрыта (clipboardStatus = IDLE)

## Коммит

```
feat(application): rewrite redoLastRequest with two-scenario logic
```
