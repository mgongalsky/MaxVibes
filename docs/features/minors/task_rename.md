# Переименование поля `task` -> `currentMessage` в протоколе MaxVibes

## Контекст

В JSON -протоколе MaxVibes (clipboard, API, CheapAPI) существовало поле `task`, которое передавало
        текущее сообщение пользователя к LLM.Название `task` было неоднозначным : LLM могла интерпретировать
        его как общее описание задачи / проекта, а не как конкретное последнее сообщение в диалоге .

## Цель

Переименовать поле в `current_message` на уровне JSON - протокола и в `currentMessage` на уровне
        Kotlin - кода, чтобы:

-Устранить неоднозначность : LLM однозначно понимает, что это * * последнее сообщение * * в диалоге
        -Обеспечить единообразие : одно имя для одного концепта во всех режимах(API, CheapAPI, Clipboard)
-Следовать принципу Ubiquitous Language : имя точно отражает семантику поля

## Data flow

```
[ChatMessageController]
task / fullTask(локальная переменная — не переименовывалась)
|
v
[ContextAwareRequest.currentMessage]-- API / CheapAPI режимы
[ClipboardRequest.currentMessage]-- Clipboard режим
|
v
[ContextAwareModifyService]-- API / CheapAPI
        request.currentMessage-- > planContext() / chat()

[ClipboardInteractionService]-- Clipboard
        startTask(currentMessage)-- > ClipboardSessionState.currentMessage
|
v
[JsonClipboardProtocolCodec.encode()]
put(FIELD_CURRENT_MESSAGE, request.currentMessage)
|
v
JSON: { "current_message": "..." } < --видит LLM
```

## Изменённые файлы

| Файл | Что изменено |
|------|--------------|
| `ClipboardRequestSchema.kt` | `FIELD_TASK = "task"` -> `FIELD_CURRENT_MESSAGE = "current_message"` |
| `ClipboardProtocol.kt` | `ClipboardRequest.task` -> `ClipboardRequest.currentMessage` |
| `ContextAwareModifyUseCase.kt` | `ContextAwareRequest.task` -> `ContextAwareRequest.currentMessage` |
| `ContextAwareModifyService.kt` | Все обращения `request.task` -> `request.currentMessage`(3 места) |
| `ClipboardInteractionService.kt` | Параметр `startTask`, `ClipboardSessionState.task`, все `state.task`, `ClipboardRequest(task = ...)` |
| `JsonClipboardProtocolCodec.kt` | `request.task` -> `request.currentMessage` в методе `encode()` |
| `ChatMessageController.kt` | Named arguments `ContextAwareRequest(task = ...)` -> `(currentMessage = ...)`(2 места) |

## Что НЕ изменялось

-Локальные переменные `task`, `fullTask` внутри методов `ChatMessageController` — внутренние имена, на протокол не влияют
        -`ModifyCodeRequest.instruction` и `AnalyzeCodeRequest.question` — разные порты, семантически иные
        -Параметры `sendApiMessage(task)`, `sendCheapApiMessage(task)`, `runApiRequest(task)` — локальные параметры методов контроллера, на JSON -протокол не влияют
-`continueDialog(message: String, ...)` — параметр уже назывался `message`
