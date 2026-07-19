# Рефакторинг: Per - Session Clipboard State

## Проблема

### Текущее состояние

        Clipboard - режим в MaxVibes управляет трёхфазным диалогом с LLM :
1.Пользователь отправляет сообщение → сервис генерирует JSON и копирует в буфер
2.Пользователь вставляет JSON в Claude / ChatGPT, получает ответ
        3.Пользователь вставляет ответ обратно → сервис парсит и применяет изменения

        Для этого сервис хранит внутреннее состояние : `waitingForPaste: Boolean` и `sessionState: ClipboardSessionState?` .

### Корневые проблемы

**1.Глобальное состояние * *
        `ClipboardInteractionService` создаётся как синглтон (`@Service(Level.PROJECT)`) и хранит состояние для _одного_ clipboard - диалога.При наличии нескольких чат -сессий это приводит к конфликту: состояние одного чата "видно" из другого.Текущий workaround : `ChatPanel.loadCurrentSession()` вызывает `resetClipboard()` при каждом переключении сессии . Это уничтожает активный clipboard - диалог при смене вкладки — пользователь теряет контекст .

**2.Состояние принадлежит не тому слою * *
Статус диалога (`IDLE` / `SESSION_ACTIVE` / `AWAITING_PASTE`) — это часть состояния чат -сессии с точки зрения пользователя.Но хранится он в application - сервисе в виде boolean -флагов, не персистируется и не привязан к конкретной сессии .

**3.UI знает о внутренних флагах сервиса * *
        `ChatMessageController.dispatchClipboardMessage()` напрямую вызывает `cs.isWaitingForResponse()` и `cs.hasActiveSession()` и сам принимает решение о роутинге :
```kotlin
when {
    cs.isWaitingForResponse() -> handlePaste()
    cs.hasActiveSession() -> continueDialog()
    else -> startTask()
}
```
UI знает о внутренней структуре состояний сервиса — нарушение инкапсуляции и принципа "UI только отображает".

**4.Нет валидации переходов * *
Ничто не мешает вызвать `handlePastedResponse()` когда `waitingForPaste = false`, или `startTask()` поверх активной сессии.Невалидные переходы не логируются и не отклоняются.

**5.Состояние нетестируемо в изоляции * *
        Логика переходов размазана между `ClipboardInteractionService`(флаги), `ChatMessageController`(роутинг) и `ChatPanel`(
    сброс
).Нет единого места, которое можно покрыть юнит -тестами без IntelliJ.

---

## Цель рефакторинга

        1.* * Per - session статус * * — `ClipboardSessionStatus` хранится в `ChatSession`(domain), персистируется в XML, читается при переключении сессий
        2.* * Единый сервис -владелец * * — `ClipboardSessionManager` — единственное место, которое валидирует и применяет переходы статуса
        3.* * Инкапсуляция * * — UI не читает флаги сервиса; вместо этого вызывает один метод `handleUserInput()` и получает результат
4.* * Тестируемость * * — `ClipboardSessionManager` тестируется без IntelliJ через Gradle

        ---

## Data Flow : до рефакторинга

```
UI: sendMessage()
│
▼
ChatMessageController.dispatchClipboardMessage()
│
├─ cs.isWaitingForResponse() ──► true?  → handlePastedResponse(text)
├─ cs.hasActiveSession()     ──► true?  → continueDialog(text, ...)
└─ else                               → startTask(text, ...)

ClipboardInteractionService:
private var waitingForPaste: Boolean   ← кто угодно может прочитать
private var sessionState: ...?         ← один на весь проект

generateAndCopyJson()  → waitingForPaste = true
handlePastedResponse() → waitingForPaste = false
reset()                → waitingForPaste = false

ChatPanel.loadCurrentSession():
service.clipboardService.reset()  ← глобальный сброс при переключении сессии
```

## Data Flow : после рефакторинга

```
UI: sendMessage()
│
▼
ChatMessageController:
cs.handleUserInput(session.id, userInput, ...)  ← один вызов, никакого роутинга в UI

        ClipboardInteractionService.handleUserInput():
│
├─ sessionManager.statusFor(sessionId)  → AWAITING_PASTE → handlePastedResponse()
├─                                      → SESSION_ACTIVE  → continueDialog()
└─                                      → IDLE            → startTask()

ClipboardSessionManager:
transition(sessionId, event)
│
├─ валидация по матрице переходов
├─ логирование
└─ repository.saveSession(session.withClipboardStatus(newStatus))

ChatSession(domain):
val clipboardStatus: ClipboardSessionStatus  ← персистируется в XML

ChatPanelState:
val clipboardStatus: ClipboardSessionStatus  ← читается из activeSession
(UI рендерит на основе снапшота, не опрашивает сервис)
```

---

## Новые компоненты

### `ClipboardSessionStatus`(domain)
```
enum class ClipboardSessionStatus {
    IDLE,            // нет активного clipboard-диалога
    SESSION_ACTIVE,  // диалог открыт, ждём следующего сообщения от пользователя
    AWAITING_PASTE   // JSON скопирован, ждём вставки ответа LLM
}
```

### `ClipboardEvent`(application)
```
sealed class ClipboardEvent {
    object StartSession    // пользователь отправил первое сообщение
    object JsonCopied      // JSON сгенерирован и скопирован в буфер
    object ResponsePasted  // ответ LLM получен и обработан
    object Reset           // сессия сброшена (новый чат, удаление, явный сброс)
}
```

### `ClipboardSessionManager`(application service)
Конечный автомат . Единственное место, которое пишет `clipboardStatus` в сессию.

**Матрица переходов : * *

| Event \ Status | IDLE | SESSION_ACTIVE | AWAITING_PASTE |
|---|-- - |-- - |-- - |
| StartSession | → SESSION_ACTIVE | ⚠ warn, no - op | ⚠ warn, no - op |
| JsonCopied | ⚠ warn, no - op | → AWAITING_PASTE | → AWAITING_PASTE(ре - отправка разрешена) |
| ResponsePasted | ⚠ warn → false | ⚠ warn → false | → SESSION_ACTIVE |
| Reset | → IDLE(no - op) | → IDLE | → IDLE |

Невалидные переходы : логируются как warn, возвращают `false`, не бросают исключение.

---

## Тесты: где и что покрывать

### Слабые места подхода

1.* * Backward compatibility XML * * — старые файлы без поля `clipboardStatus` должны читаться с дефолтом `IDLE`
        2.* * Невалидные переходы * * — например `ResponsePasted` из `IDLE` не должен ломать систему
3.* * Роутинг в `handleUserInput()` * * — каждый статус должен вызывать правильный внутренний метод
4.* * Смена сессии не сбрасывает статус * * — статус другой сессии читается корректно после переключения
5.* * Ре - отправка JSON * * — `JsonCopied` из `AWAITING_PASTE` должна быть разрешена(recopy)

### Тесты по шагам

**STEP 1(domain):**
-`ChatSession` с дефолтным `clipboardStatus == IDLE`
        -`withClipboardStatus()` возвращает новый иммутабельный объект
-Прочие поля не изменяются при `withClipboardStatus()`

**STEP 3(ClipboardSessionManager):**
-Все валидные переходы из матрицы
-Все невалидные переходы: возвращают `false`, не меняют статус
-`Reset` из любого состояния → `IDLE`
        -`JsonCopied` из `AWAITING_PASTE` → `AWAITING_PASTE`(разрешён)
-Логирование вызывается при warn -переходах
-`statusFor()` для несуществующей сессии → `IDLE`

**STEP 4(ClipboardInteractionService):**
-`handleUserInput()` в статусе `IDLE` → вызывает `startTask` - путь
-`handleUserInput()` в статусе `AWAITING_PASTE` → вызывает `handlePastedResponse` - путь
-`handleUserInput()` в статусе `SESSION_ACTIVE` → вызывает `continueDialog` - путь
-При успешном `generateAndCopyJson()` → статус становится `AWAITING_PASTE`
-При успешном `handlePastedResponse()` → статус становится `SESSION_ACTIVE`

**STEP 2(persistence, ручная проверка):**
-Старый XML без `clipboardStatus` читается с `IDLE`
-После сохранения и перезагрузки статус восстанавливается

        ---

## Шаги реализации

| Шаг | Название | Модуль | Риск |
|---|-- - |-- - |-- - |
| 1 | Domain: ClipboardSessionStatus | maxvibes - domain | минимальный |
| 2 | Persistence: сериализация нового поля | maxvibes - plugin | минимальный |
| 3 | Application: ClipboardSessionManager | maxvibes - application | низкий |
| 4 | Application: рефакторинг ClipboardInteractionService | maxvibes -application | средний |
| 5 | Plugin: DI — подключение менеджера | maxvibes -plugin | низкий |
| 6 | Plugin: ChatPanelState | maxvibes - plugin | низкий |
| 7 | Plugin: UI — убираем прямые обращения к сервису | maxvibes - plugin | средний |
| 8 | Cleanup | все | минимальный |

Каждый шаг заканчивается компилируемым и рабочим состоянием проекта .

---

## Принципы, которым следуем

        -* * Domain ownership * * — статус сессии принадлежит домену, не инфраструктуре
        -* * Single writer * * — только `ClipboardSessionManager` пишет `clipboardStatus`
-* * UI as observer * * — UI читает снапшот `ChatPanelState`, не опрашивает сервис
-* * Fail - safe transitions * * — невалидные переходы логируются, но не падают
-* * Backward compatibility * * — XML -формат расширяется, не ломается
        -* * Testable without IDE * * — `ClipboardSessionManager` тестируется через Gradle
