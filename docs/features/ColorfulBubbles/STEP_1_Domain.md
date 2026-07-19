# Step 1 — Domain: новые модели RequestedViewInfo и AppliedModInfo

**Место в плане:** Шаг 1 из 6.Фундамент всего остального.После этого шага компилируется всё; новые поля в ChatMessage просто пустые .

## Контекст

Сейчас `ChatMessage` хранит:
-`requestedFiles: List<String>` — что LLM попросил(просто пути, без гранулярности)
-`appliedModificationPaths: List<String>` — что было применено(просто пути, без типа)

Нам нужны типизированные структуры, чтобы UI мог раскрашивать по гранулярности / категории .

## Файлы для создания

### 1.`RequestedViewInfo.kt`

Путь: `maxvibes-domain/src/main/kotlin/com/maxvibes/domain/model/code/RequestedViewInfo.kt`

```kotlin
package com.maxvibes.domain.model.code

/**
 * Describes a single code view requested by the LLM in its response.
 *
 * Carries granularity so the UI can colour-code requests by "weight":
 * FULL (heavy) → blue, SIGNATURES/OUTLINE (medium) → yellow, ELEMENT (light) → green.
 *
 * @param path          File path as returned in the LLM response (e.g. "src/main/kotlin/Foo.kt").
 * @param granularity   How much of the file was requested.
 * @param elementPath   Non-null only when [granularity] is [CodeGranularity.ELEMENT];
 *                      identifies the specific element (e.g. "class[Foo]/function[bar]").
 */
data class RequestedViewInfo(
    val path: String,
    val granularity: CodeGranularity,
    val elementPath: String? = null
)
```

### 2.`AppliedModInfo.kt`

Путь: `maxvibes-domain/src/main/kotlin/com/maxvibes/domain/model/modification/AppliedModInfo.kt`

```kotlin
package com.maxvibes.domain.model.modification

/**
 * Category of a [Modification] for display purposes.
 *
 * FILE_LEVEL  → blue  (CreateFile, ReplaceFile, DeleteFile)
 * ELEMENT_LEVEL → green (CreateElement, ReplaceElement, DeleteElement)
 * IMPORT      → yellow (AddImport, RemoveImport)
 */
enum class ModificationCategory { FILE_LEVEL, ELEMENT_LEVEL, IMPORT }

/**
 * Lightweight record of a successfully applied modification, persisted in [ChatMessage]
 * so the bubble footer can be reconstructed after IDE restart.
 *
 * @param path     String representation of the affected [ElementPath].
 * @param category Visual category used for colour-coding in the UI.
 */
data class AppliedModInfo(
    val path: String,
    val category: ModificationCategory
)

/** Derives the [ModificationCategory] from a [Modification] instance. */
fun Modification.toCategory(): ModificationCategory = when (this) {
    is Modification.CreateFile, is Modification.ReplaceFile, is Modification.DeleteFile ->
        ModificationCategory.FILE_LEVEL

    is Modification.CreateElement, is Modification.ReplaceElement, is Modification.DeleteElement ->
        ModificationCategory.ELEMENT_LEVEL

    is Modification.AddImport, is Modification.RemoveImport ->
        ModificationCategory.IMPORT
}
```

## Файлы для изменения

### 3.`ChatMessage.kt` — добавить два новых поля

        Добавляем в конец параметров data class :

    ```kotlin
/**
 * Typed view requests made by the LLM in this ASSISTANT message.
 * Supersedes [requestedFiles] — carries granularity for colour-coded display.
 * Empty for messages created before this field was introduced.
 */
val requestedViews: List<RequestedViewInfo> = emptyList(),

/**
 * Typed record of applied modifications with category for colour-coded display.
 * Supersedes [appliedModificationPaths] — carries ModificationCategory.
 * Empty for messages created before this field was introduced.
 */
val appliedModifications: List<AppliedModInfo> = emptyList(),
```

## Импорты для ChatMessage.kt

```kotlin
import com . maxvibes . domain . model . code . RequestedViewInfo
        import com . maxvibes . domain . model . modification . AppliedModInfo
```

## Проверка

```bash
    ./ gradlew : maxvibes -domain:build
```

Ожидаем: BUILD SUCCESSFUL . Остальные модули не трогаем на этом шаге .
