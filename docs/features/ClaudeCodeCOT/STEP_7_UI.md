# STEP 7 — ChatPanel live bubble + minimal animation

## Цель

Показать transient live bubble под последним сообщением во время Claude Code
send.Минимальная анимация (точки), elapsed time, опционально preview последнего
chunk'а. Disappear при `clear`.

## Изменения

### 1.Расширить `ChatPanelState`

**Файл:** `maxvibes-plugin/src/main/kotlin/com/maxvibes/plugin/ui/ChatPanelState.kt`

Добавить поле :

```kotlin
/**
 * Transient live activity from Claude Code transport. Non-null while a send is in
 * flight and emitting events. Driven by [ClaudeCodeActivityTracker]; cleared when
 * the send completes (success or failure).
 */
val liveActivity: com.maxvibes.domain.model.interaction.ClaudeCodeActivity? = null,
```

(Добавить как последнее опциональное поле data class — порядок не критичен,
дефолт null обеспечивает back - compat для всех `buildState` сайтов.)

### 2.Создать `LiveActivityBubble` (новый компонент)

**Создать:** `maxvibes-plugin/src/main/kotlin/com/maxvibes/plugin/ui/LiveActivityBubble.kt`

```kotlin
package com.maxvibes.plugin.ui

import com . intellij . ui . JBColor
        import com . intellij . ui . components . JBLabel
        import com . intellij . util . ui . JBUI
        import com . maxvibes . domain . model . interaction . ClaudeCodeActivity
        import java . awt . BorderLayout
        import java . awt . Color
        import java . awt . Font
        import javax . swing . JPanel
        import javax . swing . Timer

/**
 * Transient bubble shown beneath the conversation while a Claude Code send is in
 * flight. Displays minimal animated dots, elapsed time, and (when meaningful)
 * a short preview of the latest assistant chunk.
 *
 * The bubble owns a single Swing Timer for the dot pulse (500ms). Elapsed time
 * is recomputed on each tick from [ClaudeCodeActivity.startedAtMs]. The component
 * is created once and updated via [setActivity] — null hides it, non-null shows
 * and refreshes content.
 *
 * Lifecycle: [dispose] stops the timer. Callers MUST invoke it when the parent
 * panel is being torn down (e.g. tool window closed).
 */
class LiveActivityBubble : JPanel(BorderLayout()) {

    private val mainLine = JBLabel("").apply {
        font = font.deriveFont(Font.PLAIN, 12f)
        foreground = JBColor(Color(0x2196F3), Color(0x64B5F6))
        border = JBUI.Borders.emptyLeft(8)
    }
    private val previewLine = JBLabel("").apply {
        font = font.deriveFont(Font.ITALIC, 11f)
        foreground = JBColor.GRAY
        border = JBUI.Borders.empty(0, 8, 4, 8)
        isVisible = false
    }

    private var dotsFrame = 0
    private var current: ClaudeCodeActivity? = null

    private val pulseTimer = Timer(500) {
        dotsFrame = (dotsFrame + 1) % 4
        refreshMainLine()
    }

    init {
        background = JBColor.background()
        border = JBUI.Borders.empty(6, 4, 6, 4)
        val inner = JPanel(BorderLayout()).apply {
            background = JBColor.background()
            add(mainLine, BorderLayout.NORTH)
            add(previewLine, BorderLayout.CENTER)
        }
        add(inner, BorderLayout.CENTER)
        isVisible = false
    }

    fun setActivity(activity: ClaudeCodeActivity?) {
        current = activity
        if (activity == null) {
            pulseTimer.stop()
            isVisible = false
            return
        }
        if (!pulseTimer.isRunning) pulseTimer.start()
        isVisible = true
        refreshMainLine()
        refreshPreview()
        revalidate()
        repaint()
    }

    fun dispose() {
        pulseTimer.stop()
    }

    private fun refreshMainLine() {
        val act = current ?: return
        val dots = ".".repeat(dotsFrame + 1).padEnd(4, ' ')
        val elapsedS = ((System.currentTimeMillis() - act.startedAtMs) / 1000).coerceAtLeast(0)
        val label = when (act) {
            is ClaudeCodeActivity.Started -> "\uD83E\uDD16 Claude Code started"
            is ClaudeCodeActivity.Thinking -> "\uD83E\uDD16 Claude Code is thinking"
            is ClaudeCodeActivity.RateLimit -> "\u23F3 Rate limit — ${act.info}"
        }
        mainLine.text = "$label $dots (${elapsedS}s)"
    }

    private fun refreshPreview() {
        val act = current
        if (act !is ClaudeCodeActivity.Thinking) {
            previewLine.isVisible = false
            return
        }
        val sanitized = sanitizePreview(act.previewText)
        if (sanitized.isBlank()) {
            previewLine.isVisible = false
            return
        }
        previewLine.text = sanitized
        previewLine.isVisible = true
    }

    /**
     * Hides JSON-fragment previews. Since the system prompt forces JSON output,
     * raw streaming chunks often look like `{"message":"...` — not useful for a user.
     * Heuristic: if the chunk starts with `{` or contains a JSON-key marker, hide it.
     * Otherwise truncate to a single line of reasonable length.
     */
    private fun sanitizePreview(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ""
        if (trimmed.startsWith("{")) return ""
        if (trimmed.contains("\"message\":")) return ""
        if (trimmed.contains("\"modifications\":")) return ""
        val singleLine = trimmed.replace('\n', ' ').replace(Regex("\\s+"), " ")
        return if (singleLine.length > 90) singleLine.take(87) + "..." else singleLine
    }
}
```

### 3.Подключить в `ChatPanel`

**Файл:** `maxvibes-plugin/src/main/kotlin/com/maxvibes/plugin/ui/ChatPanel.kt`

#### 3.1.Поле bubble +listener

Добавить рядом с остальными полями класса :

```kotlin
private val liveActivityBubble = LiveActivityBubble()

/**
 * Lazy reference to the activity tracker — fetched from service on first use,
 * not in init{} because MaxVibesService initialisation order is sensitive.
 */
private val activityTracker by lazy { service.claudeCodeActivityTracker }

/** Listener that flags the panel as dirty when activity changes. */
private val activityListener = ClaudeCodeActivityTracker.Listener { sessionId, _ ->
    val currentId = chatTreeService.getActiveSessionId() ?: return@Listener
    if (sessionId != currentId) return@Listener
    SwingUtilities.invokeLater { render(buildState()) }
}

/**
 * 200ms poll timer — re-renders during active activity so elapsed time ticks even
 * if no new events arrive. Lazily started by [render] when liveActivity != null,
 * stopped when null.
 */
private val activityPollTimer = Timer(200) {
    render(buildState())
}.apply { isRepeats = true }
```

Импорты добавить :

```kotlin
import com . maxvibes . application . service . ClaudeCodeActivityTracker
        import javax . swing . Timer
        import javax . swing . SwingUtilities
```

#### 3.2.Регистрация listener в `init` блоке

В `init { ... }` добавить в самый конец :

```kotlin
activityTracker.addListener(activityListener)
```

#### 3.3.Cleanup

Добавить метод `dispose()`(или дополнить существующий, если есть):

```kotlin
fun dispose() {
    activityTracker.removeListener(activityListener)
    activityPollTimer.stop()
    liveActivityBubble.dispose()
}
```

**Важно:** убедиться, что `MaxVibesToolWindowFactory` (или то, что создаёт ChatPanel)
вызывает `dispose()` через `Disposer` или эквивалент при закрытии tool window .

#### 3.4.Размещение bubble в layout

        В `setupUI()` поместить `liveActivityBubble` под `conversationPanel` :

Если conversation расположена в центре через `JBScrollPane`, добавить bubble
        как footer of the same wrapper :

```kotlin
val conversationWrapper = JPanel(BorderLayout()).apply {
    add(JBScrollPane(conversationPanel), BorderLayout.CENTER)
    add(liveActivityBubble, BorderLayout.SOUTH)
}
add(conversationWrapper, BorderLayout.CENTER)
```

(Точное место зависит от текущего setupUI — placement : непосредственно под лентой
        сообщений, над input area.)

#### 3.5.Обновление в `render`

В метод `render(state: ChatPanelState)` добавить в конце :

```kotlin
liveActivityBubble.setActivity(state.liveActivity)
if (state.liveActivity != null) {
    if (!activityPollTimer.isRunning) activityPollTimer.start()
} else {
    if (activityPollTimer.isRunning) activityPollTimer.stop()
}
```

#### 3.6.Обновление `buildState`

        В `buildState()` добавить:

```kotlin
val sid = chatTreeService.getActiveSessionId()
val liveActivity = sid?.let { activityTracker.currentFor(it) }
```

и передать `liveActivity = liveActivity` в `ChatPanelState(...)`.

## Проверка

```bash
    ./ gradlew : maxvibes -plugin:compileKotlin
```

UI smoke — см . STEP 9.

## Backward compatibility

        -В режимах не - CLAUDE_CODE `tracker.currentFor(...)` всегда null → bubble не
        показывается, ничего не ломается.
-Polling timer стартует только когда `state.liveActivity != null`, в idle CPU
не тратится .

## Commit

```
feat: show live activity bubble in ChatPanel
```
