# Step 3 — Application Wiring : заполнение новых полей при сохранении сообщений

**Место в плане:** Шаг 3 из 6.После шагов 1 и 2(domain + persistence).После этого шага новые поля реально заполняются данными в новых сообщениях.

## Контекст

После шагов 1–2 поля `requestedViews` и `appliedModifications` существуют и
сериализуются, но всегда пустые — их никто не заполняет .

Два места, где `ChatMessage` создаётся / обновляется с metadata:
1.* * API mode * * — `ContextAwareModifyService` (application layer)
2.* * Clipboard mode * * — `ClipboardInteractionService` / `ChatMessageController` (plugin / ui)

Перед реализацией этого шага * * обязательно прочитать * * :
-`maxvibes-application/src/main/kotlin/com/maxvibes/application/service/ContextAwareModifyService.kt`(FULL)
-`maxvibes-application/src/main/kotlin/com/maxvibes/application/service/ClipboardInteractionService.kt`(FULL)
-`maxvibes-plugin/src/main/kotlin/com/maxvibes/plugin/ui/ChatMessageController.kt`(FULL)

## Что нужно сделать

### requestedViews

В LLM -ответе уже есть `requestedViews` (через `ClipboardRequest` / JSON -протокол).Они декодируются в `CodeViewRequest` объекты.Нужно при сохранении ассистентского
        `ChatMessage` преобразовывать их в `List<RequestedViewInfo>`.Паттерн для `ContextAwareModifyService`:
```kotlin
val requestedViews = llmResponse.requestedViews.map { rv ->
    RequestedViewInfo(
        path = rv.path,
        granularity = rv.granularity,  // уже CodeGranularity
        elementPath = rv.elementPath
    )
}
// ... передать в ChatMessage(..., requestedViews = requestedViews)
```

Для clipboard mode — аналогично в `ClipboardInteractionService` или
        `ChatMessageController` в зависимости от того, где собирается `ChatMessage`.

### appliedModifications

`ModificationResult.Success` содержит `modification: Modification` и
        `affectedPath: ElementPath`.Используем `Modification.toCategory()` (добавлен в шаге 1):

```kotlin
val appliedMods = modResults
    .filterIsInstance<ModificationResult.Success>()
    .map { result ->
        AppliedModInfo(
            path = result.affectedPath.toString(),
            category = result.modification.toCategory()
        )
    }
// ... передать в ChatMessage(..., appliedModifications = appliedMods)
```

Необходимый импорт :
```kotlin
import com . maxvibes . domain . model . modification . toCategory
```

## Важно

Старые поля `requestedFiles` и `appliedModificationPaths` продолжают заполняться
**параллельно * * — они нужны для других частей логики (clipboard workflow и т . п .).Мы только * * добавляем * * заполнение новых полей, ничего не удаляем.

## Проверка

```bash
    ./ gradlew : maxvibes -plugin:build
```

Функциональная проверка : отправить сообщение с requestedViews → убедиться, что
футер показывает цветные записи (после шагов 4–5).
