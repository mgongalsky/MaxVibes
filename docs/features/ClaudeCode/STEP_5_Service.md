# Step 5 — Application service : ClaudeCodeInteractionService

## Цель

Создать сервис, который оркестрирует Claude Code диалог: первое сообщение → запуск процесса → send → парсинг → если есть `requestedViews` ставит статус `AWAITING_APPROVE` → пользователь жмёт Approve → собираем файлы → следующий send → ... → если есть `modifications` применяет их.Сервис максимально похож на `ClipboardInteractionService`, но с автоматическим транспортом вместо ручного буфера обмена .

## Затрагиваемые файлы

| Файл | Действие |
|------|----------|
| `maxvibes-application/src/main/kotlin/com/maxvibes/application/service/ClaudeCodeInteractionService.kt` | CREATE |
| `maxvibes-application/src/main/kotlin/com/maxvibes/application/service/ClaudeCodeStepResult.kt` | CREATE(sealed class) |

Зависит от Step 1(новые типы), Step 2(порт), Step 4(системный промпт).

## Контекст

### Аналогия с ClipboardInteractionService

У `ClipboardInteractionService` есть:
-`sessionState: ClipboardSessionState?` — workspace в памяти
-`sessionStateOwner: String?` — guard от cross - session
-`handleUserInput` — единый entry point
-`startTask` / `continueDialog` — explicit phases
        -`handlePastedResponse` — обработка ответа от LLM
        -`processUnifiedResponse` — общий пайплайн пост - парсинга(apply modifications, transition state)
-`generateAndCopyJson` — финальный шаг (build request → encode → copy)

**В ClaudeCodeInteractionService роли смещаются : * *
-`handlePastedResponse` → **`approve` * *(нет ручной вставки — есть подтверждение)
-`generateAndCopyJson` → **`generateAndSend` * *(вместо copy в clipboard — `claudeCodePort.send`)
-Остальное — почти 1 - в - 1

### Flow одного сообщения юзера

```
user sends "refactor X"
↓
handleUserInput(sessionId, "refactor X")
↓
status is IDLE → startTask(...)
↓
build ClipboardRequest with FULL context(system, history, file tree)
↓
ensureStarted(resumeSessionId = session.claudeCodeSessionId)
↓ if ResumeFailed → ensureStarted(null) and rebuild request with full context
↓
claudeCodePort.send(request) → ClaudeCodeSendResult(response, observedSessionId)
↓
persist observedSessionId → ChatSession . claudeCodeSessionId; clear claudeCodeNeedsFullContext
↓
append USER +ASSISTANT messages to ChatSession
↓
response has requestedViews?
yes → transition AWAITING_APPROVE → return WaitingForApprove(response)
no  → response has modifications?
yes → applyModifications → return Completed
no  → just text response → transition SESSION_ACTIVE → return Completed
```

### Flow Approve

```
user presses Approve
↓
approve(sessionId)
↓
status must be AWAITING_APPROVE
↓
take last assistant message — extract requestedViews / codeViewRequests
↓
gather files via contextProvider
↓
build MINIMAL ClipboardRequest(
    no system,
    no history,
    no fileTree — just freshFiles + currentMessage with role = assistant
    if echoing
)
↓
claudeCodePort.send(request) → response
↓
same post -processing as in flow above
```

## Изменения

### 5.1 Создать `ClaudeCodeStepResult.kt`

```kotlin
package com.maxvibes.application.service

import com . maxvibes . domain . model . code . RequestedViewInfo
        import com . maxvibes . domain . model . modification . ModificationResult

        sealed class ClaudeCodeStepResult {

    /** Response received and contains requestedViews — UI should show Approve button. */
    data class WaitingForApprove(
        val assistantMessage: String,
        val requestedViews: List<RequestedViewInfo>,
        val inputTokens: Int = 0,
        val outputTokens: Int = 0,
        val llmReasoning: String? = null
    ) : ClaudeCodeStepResult()

    /** Response was either text-only or contained applied modifications — turn complete. */
    data class Completed(
        val message: String,
        val modifications: List<ModificationResult>,
        val success: Boolean,
        val inputTokens: Int = 0,
        val outputTokens: Int = 0,
        val llmReasoning: String? = null,
        val commitMessage: String? = null
    ) : ClaudeCodeStepResult()

    /** Generic application-level error. */
    data class Error(val message: String) : ClaudeCodeStepResult()

    /** Process failed or was unavailable. UI may suggest checking settings. */
    data class TransportError(val detail: String) : ClaudeCodeStepResult()
}
```

### 5.2 Создать `ClaudeCodeInteractionService.kt`

Структурная схема (полную реализацию пишем по этому скелету; смотреть `ClipboardInteractionService` как образец для деталей):

```kotlin
package com.maxvibes.application.service

import com . maxvibes . application . port . output . *
        import com . maxvibes . domain . model . chat . MessageRole
        import com . maxvibes . domain . model . interaction . ClipboardRequest
        import com . maxvibes . domain . model . interaction . ClipboardResponse
        import com . maxvibes . domain . model . interaction . ClipboardSessionStatus
        import com . maxvibes . shared . result . Result

class ClaudeCodeInteractionService(
    private val contextProvider: ProjectContextPort,
    private val claudeCodePort: ClaudeCodePort,
    private val codeRepository: CodeRepository,
    private val notificationPort: NotificationPort,
    private val promptPort: PromptPort,
    private val logger: LoggerPort? = null,
    private val sessionManager: ClipboardSessionManager,
    private val chatSessionRepository: ChatSessionRepository
) {

    private var sessionState: ClipboardSessionState? = null
    private var sessionStateOwner: String? = null
    private val responseValidator = ClipboardResponseValidator()

    suspend fun handleUserInput(
        sessionId: String,
        userInput: String,
        history: List<ChatMessageDTO> = emptyList(),
        attachedContext: String? = null,
        planOnly: Boolean = false,
        ideErrors: String? = null,
        globalContextFiles: List<String> = emptyList(),
        specificPromptContent: String? = null
    ): ClaudeCodeStepResult {
        val currentStatus = sessionManager.statusFor(sessionId)
        return when (currentStatus) {
            ClipboardSessionStatus.IDLE,
            ClipboardSessionStatus.SESSION_ACTIVE ->
                startOrContinue(
                    sessionId,
                    userInput,
                    history,
                    attachedContext,
                    planOnly,
                    ideErrors,
                    globalContextFiles,
                    specificPromptContent
                )

            ClipboardSessionStatus.AWAITING_APPROVE ->
                ClaudeCodeStepResult.Error("Session is awaiting approve. Press Approve or Reset before sending a new message.")

            ClipboardSessionStatus.AWAITING_PASTE ->
                ClaudeCodeStepResult.Error("Session is in clipboard AWAITING_PASTE state — switch back to clipboard mode or reset.")
        }
    }

    /**
     * Press Approve — when last response had requestedViews.
     * Gathers requested files and sends a minimal-context follow-up.
     */
    suspend fun approve(sessionId: String): ClaudeCodeStepResult {
        val state = sessionState
            ?: return ClaudeCodeStepResult.Error("No active workspace — cannot approve")
        if (sessionStateOwner != sessionId)
            return ClaudeCodeStepResult.Error("Workspace owned by another session")
        if (sessionManager.statusFor(sessionId) != ClipboardSessionStatus.AWAITING_APPROVE)
            return ClaudeCodeStepResult.Error("Approve is only valid in AWAITING_APPROVE state")

        // 1. Take requestedViews/codeViewRequests from last assistant message
        val session = chatSessionRepository.getSessionById(sessionId)
            ?: return ClaudeCodeStepResult.Error("Session not found")
        val lastAssistant = session.messages.lastOrNull { it.role == MessageRole.ASSISTANT }
            ?: return ClaudeCodeStepResult.Error("No assistant message to approve")
        val viewsToGather = lastAssistant.requestedViews // assuming domain field exists

        // 2. Gather files
        val freshFiles = gatherRequestedFiles(viewsToGather)
            ?: return ClaudeCodeStepResult.Error("Failed to gather requested files")
        state.allGatheredFiles.putAll(freshFiles)

        // 3. Build MINIMAL request (isFirstMessage = false, addHistory = false)
        // 4. send → process response → post-process
        return doSend(sessionId, freshFiles, isFirstMessage = false, addHistory = false)
    }

    fun status(sessionId: String): ClipboardSessionStatus = sessionManager.statusFor(sessionId)

    fun reset(sessionId: String) {
        sessionState = null
        sessionStateOwner = null
        sessionManager.transition(sessionId, ClipboardEvent.Reset)
    }

    // ── Private ────────────────────────────────────────────────────────

    private suspend fun startOrContinue(
        sessionId: String,
        userInput: String,
        history: List<ChatMessageDTO>,
        attachedContext: String?,
        planOnly: Boolean,
        ideErrors: String?,
        globalContextFiles: List<String>,
        specificPromptContent: String?
    ): ClaudeCodeStepResult {
        // Initialize/refresh sessionState (similar to ClipboardInteractionService.startTask)
        // Then doSend(sessionId, freshFiles=globalContextFiles loaded, isFirstMessage=true, addHistory=true)
        TODO("Mirror ClipboardInteractionService.startTask but call doSend instead of generateAndCopyJson")
    }

    private suspend fun doSend(
        sessionId: String,
        freshFiles: Map<String, String>,
        isFirstMessage: Boolean,
        addHistory: Boolean
    ): ClaudeCodeStepResult {
        val state = sessionState ?: return ClaudeCodeStepResult.Error("No state")

        val session = chatSessionRepository.getSessionById(sessionId) ?: return ClaudeCodeStepResult.Error("No session")

        // Decide context size: full if first OR session.claudeCodeNeedsFullContext
        val useFullContext = isFirstMessage || session.claudeCodeNeedsFullContext

        val request = ClipboardRequestBuilder.build(
            state = state,
            freshFiles = freshFiles,
            isFirstMessage = useFullContext,
            addHistory = useFullContext,
            // Override system prompt for Claude Code
            // NOTE: ClipboardRequestBuilder doesn't take systemInstruction directly — it reads from state.prompts.
            // Two options: (a) plug claudeCodeSystem into state.prompts upstream; (b) post-process request after build.
            // Recommended: do (a) when initializing sessionState in startOrContinue (replace state.prompts with one whose chatSystem == claudeCodeSystem).
            specificPromptContent = null // TODO threading from UI
        )

        // Try resume first if we have an id and process not started yet
        val resumeId = session.claudeCodeSessionId
        var ensureResult = claudeCodePort.ensureStarted(resumeSessionId = resumeId)
        if (ensureResult is Result.Failure && ensureResult.error is ClaudeCodeError.ResumeFailed) {
            // Fallback: start fresh, mark needsFullContext, rebuild request
            session.claudeCodeNeedsFullContext = true
            chatSessionRepository.saveSession(session)
            ensureResult = claudeCodePort.ensureStarted(resumeSessionId = null)
        }
        if (ensureResult is Result.Failure) {
            return ClaudeCodeStepResult.TransportError(ensureResult.error.message)
        }

        val sendResult = claudeCodePort.send(request)
        return when (sendResult) {
            is Result.Success -> {
                // Persist observedSessionId, clear needsFullContext
                sendResult.value.observedSessionId?.let {
                    session.claudeCodeSessionId = it
                }
                session.claudeCodeNeedsFullContext = false
                chatSessionRepository.saveSession(session)
                processResponse(sessionId, sendResult.value.response)
            }

            is Result.Failure -> ClaudeCodeStepResult.TransportError(sendResult.error.message)
        }
    }

    private suspend fun processResponse(
        sessionId: String,
        response: ClipboardResponse
    ): ClaudeCodeStepResult {
        // 1. Persist assistant message into ChatSession (with requestedViews if any)
        // 2. If response has modifications and not plan-only — apply them
        // 3. Decide next status:
        //    - has requestedViews → AWAITING_APPROVE → return WaitingForApprove
        //    - has modifications applied → SESSION_ACTIVE → return Completed
        //    - text-only → SESSION_ACTIVE → return Completed
        TODO("Mirror ClipboardInteractionService.processUnifiedResponse adapted for AWAITING_APPROVE")
    }

    private suspend fun gatherRequestedFiles(views: List<*>): Map<String, String>? {
        TODO("Same as ClipboardInteractionService.gatherRequestedFiles")
    }
}
```

### Тонкость по системному промпту

        Есть две стратегии замены `chatSystem` на `claudeCodeSystem` для запроса:

**Стратегия A (рекомендуемая):** при инициализации `sessionState` в `startOrContinue`, заполнять `state.prompts` объектом с `chatSystem = promptPort.claudeCodeSystem()` и `planningSystem = promptPort.claudeCodeSystem()`.Тогда `ClipboardRequestBuilder` ничего не подозревает — он просто читает `state.prompts.chatSystem` .

**Стратегия B : * * после `ClipboardRequestBuilder.build(...)` копировать request с заменой `systemInstruction` . Хрупко, требует рефлексии или copy через `.copy(systemInstruction = ...)` (если `ClipboardRequest` — data class — то ок, но это бизнес - код в сервисе).

Выбирай * * A * * .

## Что НЕ делать

-Не пытаться использовать `ClipboardInteractionService` как родителя — наследование тут неуместно . Композиция через общий builder уже даёт ~80 % переиспользования.
-Не выносить общий код из `ClipboardInteractionService` в утилку « потому что DRY » — оставь две реализации рядом . Когда обе будут стабильны, в отдельной задаче решим, что вынести (например, `ModificationApplier`).
-Не реализовывать auto - loop — это явно out of scope MVP .
-Не подключать сервис в DI на этом шаге — это в Step 7.

## Тесты

Unit - тесты с MockK в `maxvibes-application/src/test/`:

-`ClaudeCodeInteractionServiceTest`:
-`firstMessage_withModifications_appliesAndCompletes`
-`firstMessage_withRequestedViews_transitionsToAwaitingApprove`
-`approve_gathersFilesAndSends`
-`transportError_returnsTransportError`
-`resume_failedWithFallback_marksNeedsFullContextAndRetries`
-`handleUserInput_inAwaitingApprove_returnsError`

Использовать `runBlocking`, MockK `coEvery`, реальные data -class инстансы для request / response.Образец стиля — найти существующий тест в `maxvibes-application/src/test/` .

## Acceptance criteria

        -[] `./gradlew :maxvibes-application:test` зелёный
-[] Все шесть сценариев выше покрыты тестами
-[] Сервис компилируется самостоятельно (без UI / DI)
