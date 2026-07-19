# Step 10: UI — Панель выбора промпта в ChatPanel

## Цель

Добавить в `ChatPanel` двухстрочную нижнюю панель :
-Верхняя строка : широкое поле с именем текущего промпта + кнопка - стрелка для выбора
-Нижняя строка : существующие кнопки(errors, trace, send и т.д.) — без изменений

        При переключении промпта — обновляем сессию и перерисовываем UI через `render(buildState())`.

## Затрагиваемые файлы

| Файл | Действие |
|------|-------- - |
| `maxvibes-plugin/src/main/kotlin/com/maxvibes/plugin/ui/ChatPanel.kt` | MODIFY |

**Перед изменениями прочитать файл целиком(`FULL`) — он большой, но нужен полностью
для понимания layout и точного места вставки.* *

## Задание

### 1.Добавить UI -компоненты(свойства класса)

Добавить рядом с другими кнопками:
```kotlin
/** Label showing the currently selected specific prompt name. */
private val promptNameLabel = JBLabel("Just Code").apply {
    font = font.deriveFont(11f)
    foreground = JBColor(Color(0x616161), Color(0x9E9E9E))
    toolTipText = "Currently active task prompt"
    minimumSize = Dimension(200, 0)
    preferredSize = Dimension(300, 20)
}

/** Button to open the prompt selection popup. */
private val promptSelectButton = JButton("▾").apply {
    font = font.deriveFont(11f)
    toolTipText = "Select task prompt"
    preferredSize = Dimension(28, 20)
    isFocusPainted = false
}
```

### 2.Создать метод buildPromptPanel()

Добавить private метод, который строит панель выбора промпта:
```kotlin
private fun buildPromptPanel(): JPanel {
    return JPanel(BorderLayout()).apply {
        border = JBUI.Borders.empty(2, 4)
        add(promptNameLabel, BorderLayout.CENTER)
        add(promptSelectButton, BorderLayout.EAST)
    }
}
```

### 3.Встроить в setupUI()

В методе `setupUI()` найти место, где собирается нижняя панель (bottomPanel или аналог).Обернуть существующую панель кнопок и добавить `buildPromptPanel()` выше :

```kotlin
// Prompt selector row (above action buttons)
val promptRow = buildPromptPanel()

// Existing action buttons panel
val actionRow = /* existing bottom buttons panel */

// Two-row bottom panel
val bottomPanel = JPanel(BorderLayout()).apply {
    add(promptRow, BorderLayout.NORTH)
    add(actionRow, BorderLayout.CENTER)
}
```

Точное место вставки зависит от текущей структуры `setupUI()` — агент должен прочитать
        метод и встроить аккуратно без переписывания всего.

### 4.Добавить listener на promptSelectButton в setupListeners ()

```kotlin
promptSelectButton.addActionListener {
    showPromptSelectionPopup()
}
```

### 5.Добавить метод showPromptSelectionPopup()

```kotlin
private fun showPromptSelectionPopup() {
    val state = buildState()
    val items = mutableListOf("Just Code") + state.availablePrompts
    val popup = JPopupMenu()
    items.forEach { name ->
        val item = JMenuItem(name).apply {
            val isSelected = if (name == "Just Code")
                state.selectedSpecificPromptName == null
            else
                name == state.selectedSpecificPromptName
            font = if (isSelected) font.deriveFont(Font.BOLD) else font
            addActionListener {
                val selectedName = if (name == "Just Code") null else name
                messageController.selectSpecificPrompt(selectedName)
            }
        }
        popup.add(item)
    }
    popup.show(promptSelectButton, 0, promptSelectButton.height)
}
```

### 6.Обновить render () для отображения текущего промпта

В методе `render(state: ChatPanelState)` добавить обновление label :
```kotlin
val displayName = state.selectedSpecificPromptName ?: "Just Code"
promptNameLabel.text = displayName
promptNameLabel.toolTipText = if (state.selectedSpecificPromptName != null)
    "Active prompt: $displayName"
else
    "No specific prompt active (Just Code)"
```

## Поведение «Just Code » fallback

        -Если папка `.maxvibes/prompts/specific/` не существует → `availablePrompts` пустой
        -Дропдаун содержит только «Just Code » → пользователь не видит ошибки
        -Плагин работает как раньше

## Проверка

```bash
    ./ gradlew : maxvibes -plugin:build
```

Затем запустить плагин в IDE(Run Plugin) и убедиться:
1.Внизу ChatPanel появилась строка с «Just Code » и кнопкой ▾
2.При клике на ▾ появляется список промптов(пустой если папки нет)
3.При выборе промпта — label обновляется
        4.После перезапуска IDE — выбранный промпт восстанавливается
5.В Clipboard -режиме при отправке сообщения — в JSON присутствует поле `specificPrompt`
