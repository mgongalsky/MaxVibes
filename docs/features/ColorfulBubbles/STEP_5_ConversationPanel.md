# Step 5 — ConversationPanel: цветной рендеринг

**Место в плане:** Шаг 5 из 6.Финальный UI -шаг, после шагов 1–4.После этого шага пользователь видит цветные бабблы.

## Контекст

Весь рендеринг баблов — в `ConversationPanel.kt` .
Ключевые методы :
-`buildSummaryHtml(...)` — компактная строка в свёрнутом футере
-`detailsPanel(...)` — развёрнутый список с кликабельными записями
-`addAssistantBubble(...)` — точка входа, передаёт `metaFiles` ( = attachedFiles)

## Изменения в сигнатурах

### `addAssistantBubble`

Добавить параметры для новых типизированных данных :

```kotlin
fun addAssistantBubble(
    text: String,
    tokenInfo: String? = null,
    modifications: List<ModificationResult> = emptyList(),
    metaFiles: List<String> = emptyList(),           // legacy
    reasoning: String? = null,
    requestedViews: List<RequestedViewInfo> = emptyList(),
    appliedModifications: List<AppliedModInfo> = emptyList()
)
```

Передавать их далее в `assistantBubble(...)` → `collapsibleFooter(...)` →
`buildSummaryHtml(...)` и `detailsPanel(...)`.

## `buildSummaryHtml` — breakdown по типам

Заменить блок `metaFiles` на цветной breakdown :

```kotlin
// requestedViews breakdown
if (requestedViews.isNotEmpty()) {
    val full = requestedViews.count { it.granularity == CodeGranularity.FULL }
    val sigs =
        requestedViews.count { it.granularity == CodeGranularity.SIGNATURES || it.granularity == CodeGranularity.OUTLINE }
    val elem = requestedViews.count { it.granularity == CodeGranularity.ELEMENT }
    val segments = buildList {
        if (full > 0) add("<font color='#2980B9'>$full full</font>")
        if (sigs > 0) add("<font color='#D4AC0D'>$sigs sig</font>")
        if (elem > 0) add("<font color='#27AE60'>$elem elem</font>")
    }
    parts += "&#128193; ${segments.joinToString(" &middot; ")}"
} else if (metaFiles.isNotEmpty()) {
    // legacy fallback
    parts += "<font color='#888888'>&#128193; ${metaFiles.size} files</font>"
}

// appliedModifications breakdown
if (appliedModifications.isNotEmpty()) {
    val fileLevel = appliedModifications.count { it.category == ModificationCategory.FILE_LEVEL }
    val elemLevel = appliedModifications.count { it.category == ModificationCategory.ELEMENT_LEVEL }
    val imports = appliedModifications.count { it.category == ModificationCategory.IMPORT }
    val segments = buildList {
        if (fileLevel > 0) add("<font color='#1A5276'>$fileLevel file</font>")
        if (elemLevel > 0) add("<font color='#1E8449'>$elemLevel elem</font>")
        if (imports > 0) add("<font color='#B7950B'>$imports imp</font>")
    }
    parts += "&#9989; ${segments.joinToString(" &middot; ")}"
} else {
    // legacy: используем ok/fail как раньше
    if (ok.isNotEmpty()) parts += "<font color='#1E8449'>&#9989; ${ok.size}</font>"
    if (fail.isNotEmpty()) parts += "<font color='#C0392B'>&#10060; ${fail.size}</font>"
}
```

## `detailsPanel` — цветные кликабельные записи

### Блок requestedViews (заменяет "Gathered files")

```kotlin
if (requestedViews.isNotEmpty()) {
    add(sectionLabel("\uD83D\uDCC1 Requested:"))
    requestedViews.forEach { view ->
        val (color, lightColor, label) = when (view.granularity) {
            CodeGranularity.FULL -> Triple(Color(0x1A5276), Color(0x5DADE2), "[full]")
            CodeGranularity.SIGNATURES -> Triple(Color(0x9A7D0A), Color(0xF4D03F), "[sig]")
            CodeGranularity.OUTLINE -> Triple(Color(0x9A7D0A), Color(0xF4D03F), "[outline]")
            CodeGranularity.ELEMENT -> Triple(Color(0x1E8449), Color(0x58D68D), "[elem]")
        }
        val displayText = if (view.elementPath != null)
            "  • ${view.path} / ${view.elementPath}  $label"
        else
            "  • ${view.path}  $label"
        val navigateTo = if (view.elementPath != null)
            "file:${view.path}/${view.elementPath}"
        else
            view.path
        add(clickableLabel(displayText, color, lightColor) { onNavigateToPath(navigateTo) })
    }
} else if (metaFiles.isNotEmpty()) {
    // legacy
    add(sectionLabel("\uD83D\uDCC1 Gathered files:"))
    metaFiles.forEach { name ->
        add(JBLabel("   • $name").apply {
            font = Font(Font.MONOSPACED, Font.PLAIN, 10)
            foreground = JBColor(Color(0x888888), Color(0x666666))
            alignmentX = Component.LEFT_ALIGNMENT
        })
    }
}
```

### Блок appliedModifications (заменяет "Applied modifications")

```kotlin
if (appliedModifications.isNotEmpty()) {
    add(sectionLabel("\u2705 Applied modifications:"))
    appliedModifications.forEach { mod ->
        val (color, lightColor) = when (mod.category) {
            ModificationCategory.FILE_LEVEL -> Pair(Color(0x1A5276), Color(0x5DADE2))
            ModificationCategory.ELEMENT_LEVEL -> Pair(Color(0x1E8449), Color(0x58D68D))
            ModificationCategory.IMPORT -> Pair(Color(0x9A7D0A), Color(0xF4D03F))
        }
        val displayText = "  • ${ChatNavigationHelper.formatElementPath(mod.path)}"
        add(clickableLabel(displayText, color, lightColor) { onNavigateToPath(mod.path) })
    }
} else if (ok.isNotEmpty()) {
    // legacy
    // ... существующий код для ok/fail
}
```

## Вспомогательные функции

        Добавить private -хелперы в `ConversationPanel`:

```kotlin
private fun sectionLabel(text: String) = JBLabel(text).apply {
    font = font.deriveFont(Font.BOLD, 9f)
    foreground = JBColor.GRAY
    alignmentX = Component.LEFT_ALIGNMENT
    border = JBUI.Borders.empty(2, 0, 2, 0)
}

private fun clickableLabel(
    text: String,
    normalColor: Color,
    hoverColor: Color,
    onClick: () -> Unit
) = JBLabel(text).apply {
    font = Font(Font.MONOSPACED, Font.PLAIN, 10)
    foreground = JBColor(normalColor, hoverColor)
    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    alignmentX = Component.LEFT_ALIGNMENT
    addMouseListener(object : MouseAdapter() {
        override fun mouseClicked(e: MouseEvent) = onClick()
        override fun mouseEntered(e: MouseEvent) {
            foreground = JBColor(hoverColor, normalColor)
        }

        override fun mouseExited(e: MouseEvent) {
            foreground = JBColor(normalColor, hoverColor)
        }
    })
}
```

## Вызов из ChatPanel / ChatMessageController

В месте, где вызывается `conversationPanel.addAssistantBubble(...)`,
добавить передачу новых полей из `DisplayMessage` :

```kotlin
conversationPanel.addAssistantBubble(
    text = msg.content,
    tokenInfo = msg.tokenInfo,
    modifications = ...,
metaFiles = msg.attachedFiles,
reasoning = msg.reasoning,
requestedViews = msg.requestedViews,
appliedModifications = msg.appliedModifications
)
```

## Необходимые импорты

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

Функциональная проверка :
1.Старые сессии — legacy отображение(серый, без краша)
2.Новые сообщения с requestedViews — цветные теги в summary и детали
3.Клик на ELEMENT → навигация к элементу в файле
