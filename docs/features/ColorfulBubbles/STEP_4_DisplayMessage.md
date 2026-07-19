# Step 4 — DisplayMessage + ConversationRenderer

**Место в плане:** Шаг 4 из 6.После шагов 1–3.После этого шага `DisplayMessage` несёт типизированные данные; рендерер маппирует их.UI - изменений пока нет — они в шаге 5.

## Контекст

`DisplayMessage` — это DTO между domain и UI (живёт в `ConversationRenderer.kt`).`ConversationRenderer.render()` маппирует `ChatMessage` → `DisplayMessage`.Сейчас `DisplayMessage` имеет:
-`attachedFiles: List<String>` — отображается как `📁 N files`
-`appliedModificationPaths: List<String>` — кликабельные пути без цвета

## Изменения в `ConversationRenderer.kt`

### 1.Обновить `DisplayMessage` — добавить два поля

```kotlin
data class DisplayMessage(
    val role: MessageRole,
    val content: String,
    val attachedFiles: List<String> = emptyList(),           // legacy fallback
    val appliedModificationPaths: List<String> = emptyList(), // legacy fallback
    val reasoning: String? = null,
    val tokenInfo: String? = null,
    // ── новые поля ──
    val requestedViews: List<RequestedViewInfo> = emptyList(),
    val appliedModifications: List<AppliedModInfo> = emptyList()
)
```

### 2.Обновить `render()` в `ConversationRenderer`

        В блоке `else DisplayMessage(...)` добавить маппинг:

```kotlin
else DisplayMessage(
    role = message.role,
    content = content,
    attachedFiles = message.attachedFiles,
    appliedModificationPaths = message.appliedModificationPaths,
    reasoning = message.reasoning,
    tokenInfo = message.tokenInfo,
    requestedViews = message.requestedViews,
    appliedModifications = message.appliedModifications
)
```

### 3.Необходимые импорты в `ConversationRenderer.kt`

```kotlin
import com . maxvibes . domain . model . code . RequestedViewInfo
        import com . maxvibes . domain . model . modification . AppliedModInfo
```

## Логика fallback в UI (реализуется в шаге 5)

ConversationPanel будет использовать такой приоритет:
-Если `requestedViews` непустой → показывать цветные теги
-Иначе если `attachedFiles` непустой → показывать legacy `📁 N files` (серый)

-Если `appliedModifications` непустой → показывать цветные записи
-Иначе если `appliedModificationPaths` непустой → legacy зелёные ссылки

## Проверка

```bash
    ./ gradlew : maxvibes -plugin:build
```

Ожидаем: BUILD SUCCESSFUL . Визуально ничего не изменилось — UI пока использует
старые поля (изменится в шаге 5).
