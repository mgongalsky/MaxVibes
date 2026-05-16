package com.maxvibes.plugin.ui

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.maxvibes.domain.model.interaction.ClaudeCodeActivity
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Font
import javax.swing.JPanel
import javax.swing.Timer

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

    /**
     * Updates the bubble with the latest activity, or hides it when [activity] is null.
     * Safe to call from EDT only.
     */
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

    /** Stops the internal pulse timer. MUST be called when the parent is being disposed. */
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
            is ClaudeCodeActivity.RateLimit -> "\u23F3 Rate limit \u2014 ${act.info}"
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
     * Hides JSON-fragment previews so we don't show the user a half-streamed
     * `{"message":"...` blob. Thinking and tool-use previews come prefixed with
     * 💭 / 🔧 emojis and are passed through as-is (they're already curated by
     * StreamJsonProtocol.extractThinkingPreview / extractToolUseName).
     *
     * Heuristic for raw text chunks: if the chunk starts with `{` or contains a
     * JSON-key marker, hide it. Otherwise truncate to a single line of reasonable
     * length.
     */
    private fun sanitizePreview(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ""
        // Curated thinking / tool-use previews — always show.
        if (trimmed.startsWith("\uD83D\uDCAD") || trimmed.startsWith("\uD83D\uDD27")) {
            val singleLine = trimmed.replace('\n', ' ').replace(Regex("\\s+"), " ")
            return if (singleLine.length > 110) singleLine.take(107) + "..." else singleLine
        }
        if (trimmed.startsWith("{")) return ""
        if (trimmed.contains("\"message\":")) return ""
        if (trimmed.contains("\"modifications\":")) return ""
        val singleLine = trimmed.replace('\n', ' ').replace(Regex("\\s+"), " ")
        return if (singleLine.length > 90) singleLine.take(87) + "..." else singleLine
    }
}
