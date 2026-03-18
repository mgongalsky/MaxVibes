# CopyJSON2: Fix Copy JSON Button for Any Session

## Цель

Кнопка **Copy JSON** должна работать корректно из **любого** чата независимо от того,
какой чат последним вызвал Generate.

## Почему сейчас не работает

`ClipboardInteractionService` хранит один `ClipboardSessionState` (рабочий контекст:
fileTree, содержимое файлов, история, промпты) в поле `sessionState`. При переключении
чатов и нажатии Generate в другом чате — контекст перезаписывается. Нажатие Copy JSON
в первом чате видит `sessionStateOwner != sessionId` и возвращает Error.

## Что такое ClipboardSessionState

Это НЕ доменная сессия. Это временный рабочий контекст одного clipboard-диалога:
- `projectContext` — живой снимок файлового дерева (нельзя сохранить в XML)
- `allGatheredFiles` — содержимое файлов запрошенных ЛЛМ
- `dialogHistory`, `prompts`, etc.

Генерация JSON требует `projectContext.fileTree` — без него JSON не собрать.

## Решение — два сценария в redoLastRequest

**Сценарий A: sessionState принадлежит нужной сессии** (sessionStateOwner == sessionId)
→ Всё готово. Берём существующий sessionState, вызываем `generateAndCopyJson()` напрямую.

**Сценарий B: sessionState принадлежит другой сессии** (sessionStateOwner != sessionId)
→ Пересобираем минимальный рабочий контекст:
1. `contextProvider.getProjectContext()` — свежий fileTree (обязательно)
2. Последнее USER-сообщение из домена — задача для ЛЛМ
3. `requestedFiles` из последнего ASSISTANT-сообщения домена — файлы для gather
4. `promptPort.getPrompts()` — промпты
5. Создаём минимальный `ClipboardSessionState`, устанавливаем как `sessionState`
6. Вызываем `gatherRequestedFiles()` + `generateAndCopyJson()`

Без `startTask`, без переходов state machine, без добавления сообщений в историю.

## Что добавляем в домен

Только одно поле в `ChatMessage`:
```kotlin
val requestedFiles: List<String> = emptyList()
```
Заполняется когда ЛЛМ возвращает `requestedFiles` в ответе. Нужно только для Сценария B.

## Что НЕ меняем

- `ClipboardSessionState` — структура остаётся
- `sessionState` / `sessionStateOwner` — остаются (используются для Сценария A и continueDialog)
- `generateAndCopyJson()` — без изменений
- `startTask` / `continueDialog` / `handlePastedResponse` — без изменений
- `ClipboardSessionManager` — без изменений
- `ChatPanel` / `ChatMessageController` — без изменений

## Шаги

| Шаг | Файл | Что делаем |
|-----|------|------------|
| 1 | STEP_1_Domain.md | Добавить `requestedFiles` в `ChatMessage` |
| 2 | STEP_2_Persistence.md | XML-сериализация нового поля |
| 3 | STEP_3_PersistRequestedFiles.md | Сохранять `requestedFiles` в домен после каждого ответа ЛЛМ |
| 4 | STEP_4_RedoLastRequest.md | Переписать `redoLastRequest` — два сценария |
| 5 | STEP_5_Tests.md | Обновить тесты |

## Сценарий проверки успеха

1. Сессия A: Generate → ЛЛМ запросил `src/Foo.kt` → вставить ответ
2. Сессия B: Generate → sessionStateOwner = B
3. Переключиться на A → Copy JSON
4. **Ожидаемо:** background task запускается, JSON содержит `src/Foo.kt`,
статус A остаётся AWAITING_PASTE

## Коммиты

```
feat(domain): add requestedFiles to ChatMessage
feat(persistence): serialize requestedFiles in ChatHistoryService XML
feat(application): persist requestedFiles from LLM response into domain
feat(application): rewrite redoLastRequest with two-scenario logic
test: update redoLastRequest tests for two-scenario behaviour
```
