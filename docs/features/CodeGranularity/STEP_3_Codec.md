# STEP 3 — Парсинг requestedViews в Codec

## Цель

Научить `JsonClipboardProtocolCodec` парсить новое поле `requestedViews`
        из JSON -ответа LLM и возвращать `List<CodeViewRequest>` вместе с уже
        существующими `requestedFiles` .

## Модуль

`maxvibes-plugin`(класс `JsonClipboardProtocolCodec`)

## Предварительные условия

        -STEP 1 и STEP 2 выполнены

## Формат в JSON - ответе LLM

```json
{
    "message": "Нужно посмотреть структуру сервиса",
    "requestedViews": [
    { "path": "src/main/kotlin/.../ClipboardSessionManager.kt", "granularity": "SIGNATURES" },
    { "path": "src/main/kotlin/.../ChatSession.kt", "granularity": "ELEMENT", "elementPath": "class[ChatSession]/property[tokenUsage]" },
    { "path": "src/main/kotlin/.../ChatPanel.kt" }
    ],
    "requestedFiles": ["src/main/kotlin/.../OldStyle.kt"]
}
```

Правила:
-`granularity` необязателен → дефолт `FULL`
-`elementPath` необязателен → `null`
        -Старый `requestedFiles` → каждый элемент превращается в `CodeViewRequest(path, FULL)`
        -Дубликаты между `requestedViews` и `requestedFiles` по пути — оставить один (победа `requestedViews`)

## Что изменить в `JsonClipboardProtocolCodec`

### Метод decode ()

После парсинга `requestedFiles` добавить парсинг `requestedViews` :

```kotlin
// 1. Старые requestedFiles → CodeViewRequest(path, FULL)
val fromFiles: List<CodeViewRequest> = json
    .optJSONArray(ClipboardRequestSchema.REQUESTED_FILES)
    ?.toStringList()
    ?.map { CodeViewRequest(it, CodeGranularity.FULL) }
    ?: emptyList()

// 2. Новые requestedViews → CodeViewRequest с гранулярностью
val fromViews: List<CodeViewRequest> = json
    .optJSONArray(ClipboardRequestSchema.REQUESTED_VIEWS)
    ?.toCodeViewRequests()
    ?: emptyList()

// 3. Мёрж: requestedViews побеждает при дубликате по пути
val mergedRequests: List<CodeViewRequest> = (fromViews + fromFiles)
    .distinctBy { it.filePath }
```

### Вспомогательный парсер (private extension)

```kotlin
/**
 * Парсит JSONArray из элементов requestedViews.
 * Каждый элемент: { path, granularity?, elementPath? }
 */
private fun JSONArray.toCodeViewRequests(): List<CodeViewRequest> =
    (0 until length()).mapNotNull { i ->
        val obj = optJSONObject(i) ?: return@mapNotNull null
        val path = obj.optString(ClipboardRequestSchema.VIEW_PATH)
            .takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val granularity = obj.optString(ClipboardRequestSchema.VIEW_GRANULARITY)
            .let { raw ->
                runCatching { CodeGranularity.valueOf(raw) }.getOrDefault(CodeGranularity.FULL)
            }
        val elementPath = obj.optString(ClipboardRequestSchema.VIEW_ELEMENT_PATH)
            .takeIf { it.isNotBlank() }
        CodeViewRequest(path, granularity, elementPath)
    }
```

## Как результат попадает в плагин

Результат `mergedRequests: List<CodeViewRequest>` нужно передать дальше через
        существующий data flow обработки ответа LLM . Конкретная схема зависит от
того, как `ClipboardInteractionService` / `ClipboardResponseValidator` сейчас
обрабатывает `requestedFiles` — адаптируем аналогично.На этом шаге достаточно что codec * * парсит * * корректно — вызов `getCodeView()`
можно временно заглушить логом .

## После шага

### Проверка компиляции
```bash
    ./ gradlew : maxvibes -plugin:compileKotlin
```

### Unit - тест(написать в STEP 6, но описать здесь)

Тест `JsonClipboardProtocolCodecTest` :

| Сценарий | JSON | Ожидание |
|---------|------|----------|
| Только старый requestedFiles | `{"requestedFiles":["A.kt"]}` | `[CodeViewRequest("A.kt", FULL)]` |
| Только requestedViews | `{"requestedViews":[{"path":"B.kt","granularity":"SIGNATURES"}]}` | `[CodeViewRequest("B.kt", SIGNATURES)]` |
| Оба поля, без дубликатов | файлы A.kt и B.kt из разных полей | список из двух элементов |
| Дубликат пути | A . kt в обоих полях, requestedViews с SIGNATURES | победа requestedViews : `SIGNATURES` |
| Без granularity | `{"requestedViews":[{"path":"C.kt"}]}` | `[CodeViewRequest("C.kt", FULL)]` |
| Невалидная granularity | `"granularity": "UNKNOWN"` | дефолт `FULL`, не падает |
| ELEMENT без elementPath | `"granularity": "ELEMENT"` | `elementPath = null`, не падает |
