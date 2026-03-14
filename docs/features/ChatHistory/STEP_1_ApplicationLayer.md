# STEP 1: Application Layer — addHistory в ClipboardInteractionService ✅ DONE

## Контекст

`ClipboardInteractionService` формирует `ClipboardRequest` в методе `generateAndCopyJson()`.

**Цель минимизации токенов**: по умолчанию `previouslyGatheredPaths` всегда пуст —
LLM в текущем чате уже знает контекст и не нуждается в повторном перечислении файлов.

Галка **"Add History"** нужна только при переходе в **новый LLM-чат**, чтобы
сообщить ему, какие файлы уже были в рамках задачи. LLM сама решит, что запросить.

Файл: `maxvibes-application/src/main/kotlin/com/maxvibes/application/service/ClipboardInteractionService.kt`

> Внутренний параметр в коде: `addHistory: Boolean` — implementation detail.
> Пользователь видит его как галку **"Add History"** в UI (реализуется в STEP 2).

## Что реализовано

### 1.1 — `startTask()`: параметр добавлен

```kotlin
suspend fun startTask(
task: String,
history: List<ChatMessageDTO> = emptyList(),
attachedContext: String? = null,
planOnly: Boolean = false,
ideErrors: String? = null,
globalContextFiles: List<String> = emptyList(),
addHistory: Boolean = false  // UI: "Add History"
): ClipboardStepResult
```

Пробрасывается в `generateAndCopyJson(freshFiles, isFirstMessage = true, addHistory = addHistory)`.

> На первом сообщении `allGatheredFiles` пуст — addHistory не имеет эффекта,
> но параметр сохранён для консистентности API.

### 1.2 — `continueDialog()`: параметр добавлен

```kotlin
suspend fun continueDialog(
message: String,
attachedContext: String? = null,
planOnly: Boolean? = null,
ideErrors: String? = null,
globalContextFiles: List<String> = emptyList(),
addHistory: Boolean = false  // UI: "Add History"
): ClipboardStepResult
```

Пробрасывается в `generateAndCopyJson(freshFiles, isFirstMessage = false, addHistory = addHistory)`.

### 1.3 — `generateAndCopyJson()`: логика

```kotlin
// По умолчанию (addHistory=false): previouslyGatheredPaths = [] — минимум токенов.
// addHistory=true: передаём пути всех ранее собранных файлов — LLM в новом чате
// может сама запросить что нужно через requestedFiles.
// Содержимое файлов НЕ дублируется ни в каком случае.
val previousPaths: List<String> =
if (addHistory) state.allGatheredFiles.keys.toList() else emptyList()
```

Логирование:
```kotlin
log(
"Generating JSON: freshFiles=${freshFiles.size}, previousPaths=${previousPaths.size}, " +
"historySize=${state.dialogHistory.size}, planOnly=${state.planOnly}, addHistory=$addHistory"
)
```

## Статус: ✅ Реализовано и протестировано

18 тестов в `ClipboardInteractionServiceTest` — все зелёные.

Ключевые сценарии:
- `addHistory=false` (дефолт): `previouslyGatheredPaths` всегда пуст
- `addHistory=true`: `previouslyGatheredPaths` содержит все пути из `allGatheredFiles`
- На первом сообщении: no-op (нечего сообщать)
- Per-message toggle: галка влияет только на текущий запрос

## Коммит

```
feat(clipboard): add addHistory param — share file list with LLM on demand

Default (addHistory=false): previouslyGatheredPaths is always empty,
minimising token usage. When addHistory=true (UI: "Add History" checkbox),
previously gathered file paths are shared so the LLM can re-request them
without the caller re-uploading full content.
```
