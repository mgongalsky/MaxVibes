# Refactoring Plan : Copy JSON Button

## Контекст

Кнопка * * Copy JSON * * должна генерировать полноценный JSON - запрос — такой же, как кнопка * * Generate * *, — через единый сервис.Сейчас кнопка возвращает закэшированный `lastRequest` из поля сервиса, что приводит к багу : при переключении сессий Copy JSON отдаёт JSON из * * другого чата * *.

## Корень проблемы

        `ClipboardInteractionService` содержит :
-`private var lastRequest: ClipboardRequest?` — глобальный кэш последнего запроса
        -`private var sessionState: ClipboardSessionState?` — глобальный in -memory state сессии

Оба поля не привязаны к конкретной сессии.При переключении сессий они перезаписываются данными новой сессии, не очищая старые данные .

Логика * * сборки * * `ClipboardRequest` захардкожена внутри приватного метода `generateAndCopyJson()` — не переиспользуемого и не тестируемого .

## Цель рефакторинга

        1.Вынести логику сборки `ClipboardRequest` в отдельный * * чистый объект * * `ClipboardRequestBuilder`(
    application layer,
    zero I / O,
    unit - тестируемый
)
2.`generateAndCopyJson()` делегирует в builder — дублирования нет
3.Copy JSON использует тот же путь что и Generate, только без добавления сообщения в историю
        4.Исправить баг переключения сессий через `sessionStateOwner`
        5.Удалить устаревший кэш `lastRequest` и метод `recopyLastRequest()`

## Архитектурная схема после рефакторинга

```
[Generate button][Copy JSON button]
|                                    |
dispatchClipboardMessage() messageController . redoClipboardJson ()
|                                    |
handleUserInput() clipboardService . redoLastRequest ()
|                                    |
startTask() / continueDialog()             |
|                                    |
gatherRequestedFiles() gatherRequestedFiles ()
|                                    |
└──────────────┬─────────────────────┘
↓
ClipboardRequestBuilder.build()
(ЕДИНСТВЕННОЕ место сборки JSON)
↓
clipboardPort.copyRequestToClipboard()
```

## Шаги

| Шаг | Файл | Что делаем |
|-----|------|------------|
| 1 | STEP_1_ClipboardRequestBuilder.md | Создать `ClipboardRequestBuilder` +вынести `ClipboardSessionState` в отдельный файл |
| 2 | STEP_2_DelegateToBuilder.md | `generateAndCopyJson()` делегирует в builder |
| 3 | STEP_3_RedoLastRequest.md | Добавить `sessionStateOwner` +`redoLastRequest()` в сервис |
| 4 | STEP_4_UIWiring.md | `ChatMessageController.redoClipboardJson()` + listener кнопки +удалить старый кэш |

## Принципы

-Каждый шаг оставляет плагин * * компилируемым и запускаемым * *
-Тесты пишутся на `ClipboardRequestBuilder` — без IDE, через Gradle
        -Изменения в UI минимальны (только listener +один новый метод в контроллере)
-`ClipboardInteractionService` остаётся оркестратором I / O; чистая логика уходит в builder

## Файлы затрагиваемые рефакторингом

**Изменяются:**
-`maxvibes-application/.../service/ClipboardInteractionService.kt`
-`maxvibes-plugin/.../ui/ChatPanel.kt`
-`maxvibes-plugin/.../ui/ChatMessageController.kt`

**Создаются:**
-`maxvibes-application/.../service/ClipboardRequestBuilder.kt`
-`maxvibes-application/.../service/ClipboardSessionState.kt`(выносится из `ClipboardInteractionService.kt`)
-`maxvibes-application/.../service/ClipboardRequestBuilderTest.kt`

**Удаляются(логически):**
-поле `private var lastRequest: ClipboardRequest?`
        -метод `fun recopyLastRequest(): Boolean`
