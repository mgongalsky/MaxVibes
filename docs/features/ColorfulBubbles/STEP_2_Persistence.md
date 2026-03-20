# Step 2 — Persistence: сериализация новых полей в XML

**Место в плане:** Шаг 2 из 6.После шага 1(domain).После этого шага новые поля сохраняются и восстанавливаются из XML .

## Контекст

`ChatHistoryService` содержит `XmlChatMessage` — XML DTO для `ChatMessage` .
Паттерн: добавляем XML DTO - классы для новых структур, добавляем XCollection -поля в
        `XmlChatMessage`, обновляем `toDomain()` и `fromDomain()` .

Старые поля `requestedFiles`, `attachedFiles`, `appliedModificationPaths` * * остаются * *
— они нужны для fallback -рендеринга старых сообщений и для совместимости .

## Изменения в `ChatHistoryService.kt`

### 1.Новый XML DTO `XmlRequestedViewInfo`

        Добавить перед классом `XmlChatMessage` :

```kotlin
@Tag("requestedView")
class XmlRequestedViewInfo {
    @Attribute("path")
    var path: String = ""

    /** Enum name of CodeGranularity. Default FULL for forward compat. */
    @Attribute("granularity")
    var granularity: String = "FULL"

    /** Non-null only for ELEMENT granularity. */
    @Attribute("elementPath")
    var elementPath: String? = null

    constructor()

    constructor(path: String, granularity: String, elementPath: String?) {
        this.path = path; this.granularity = granularity; this.elementPath = elementPath
    }

    fun toDomain(): RequestedViewInfo = RequestedViewInfo(
        path = path,
        granularity = try {
            CodeGranularity.valueOf(granularity)
        } catch (_: IllegalArgumentException) {
            CodeGranularity.FULL
        },
        elementPath = elementPath
    )

    companion object {
        fun fromDomain(v: RequestedViewInfo) =
            XmlRequestedViewInfo(v.path, v.granularity.name, v.elementPath)
    }
}
```

### 2.Новый XML DTO `XmlAppliedModInfo`

        Добавить после `XmlRequestedViewInfo`:

```kotlin
@Tag("appliedMod")
class XmlAppliedModInfo {
    @Attribute("path")
    var path: String = ""

    /** Enum name of ModificationCategory. Default ELEMENT_LEVEL for forward compat. */
    @Attribute("category")
    var category: String = "ELEMENT_LEVEL"

    constructor()

    constructor(path: String, category: String) {
        this.path = path; this.category = category
    }

    fun toDomain(): AppliedModInfo = AppliedModInfo(
        path = path,
        category = try {
            ModificationCategory.valueOf(category)
        } catch (_: IllegalArgumentException) {
            ModificationCategory.ELEMENT_LEVEL
        }
    )

    companion object {
        fun fromDomain(m: AppliedModInfo) = XmlAppliedModInfo(m.path, m.category.name)
    }
}
```

### 3.Новые поля в `XmlChatMessage`

        Добавить после поля `appliedModificationPaths` :

```kotlin
/** Typed view requests with granularity. Empty for messages predating this field. */
@XCollection(style = XCollection.Style.v2)
var requestedViews: MutableList<XmlRequestedViewInfo> = mutableListOf()

/** Typed applied modifications with category. Empty for messages predating this field. */
@XCollection(style = XCollection.Style.v2)
var appliedModifications: MutableList<XmlAppliedModInfo> = mutableListOf()
```

### 4.Обновить вторичный конструктор `XmlChatMessage`

        Добавить два параметра в конструктор и присвоение:

```kotlin
requestedViews: List<RequestedViewInfo> = emptyList(),
appliedModifications: List<AppliedModInfo> = emptyList()
// ...
this.requestedViews = requestedViews.map { XmlRequestedViewInfo.fromDomain(it) }.toMutableList()
this.appliedModifications = appliedModifications.map { XmlAppliedModInfo.fromDomain(it) }.toMutableList()
```

### 5.Обновить `toDomain()` в `XmlChatMessage`

        Добавить в вызов конструктора `ChatMessage()`:

```kotlin
requestedViews = requestedViews.map { it.toDomain() },
appliedModifications = appliedModifications.map { it.toDomain() },
```

### 6.Обновить `fromDomain()` в `XmlChatMessage`

        Добавить в конструктор:

```kotlin
requestedViews = msg.requestedViews,
appliedModifications = msg.appliedModifications,
```

## Необходимые импорты в `ChatHistoryService.kt`

```kotlin
import com . maxvibes . domain . model . code . CodeGranularity
        import com . maxvibes . domain . model . code . RequestedViewInfo
        import com . maxvibes . domain . model . modification . AppliedModInfo
        import com . maxvibes . domain . model . modification . ModificationCategory
```

## Проверка

```bash
    ./ gradlew : maxvibes -plugin:build
```

Ожидаем: BUILD SUCCESSFUL . Старые XML - файлы загружаются без ошибок (новые списки
        просто пустые для старых сообщений).
