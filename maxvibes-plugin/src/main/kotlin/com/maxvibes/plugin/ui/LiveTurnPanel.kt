package com.maxvibes.plugin.ui

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.maxvibes.application.port.output.AgentStreamEvent
import com.maxvibes.application.port.output.InteractionRequestSchema
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JScrollBar
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * Live "in progress" block for one Claude Code turn.
 *
 * Threading contract: [onEvent] is callable from ANY thread (the transport reader
 * calls it directly). Events land in a lock-free queue; a single EDT Swing Timer
 * (~100 ms) drains the queue and mutates the UI. No per-event invokeLater.
 *
 * Rendering rules (per spec):
 *  - header: model, turn N (distinct message ids), M:SS elapsed, last-event age;
 *    >60 s of silence shows a yellow stalled marker;
 *  - narration is PLAIN TEXT (no markdown re-render), tail-capped at ~200 KB,
 *    autoscrolls only while the scrollbar is pinned to the bottom;
 *  - reasoning streams into a collapsed toggle section;
 *  - tools and notices are one-liners in a small feed;
 *  - once the accumulated narration hits a protocol-JSON marker, the live view is
 *    cut at the marker and replaced with a placeholder (raw streaming JSON is
 *    worse than silence);
 *  - Completed hides the panel (the real bubble is rendered by the controller);
 *    Failed flushes the visible partial into the conversation via [onPartialFlush].
 *
 * NarrationMessage is an authoritative replacement of the delta buffer for its
 * (messageId, thinking) segment - self-healing against lost deltas and the whole
 * story on CLIs without --include-partial-messages.
 */
class LiveTurnPanel(
    private val onStop: () -> Unit,
    private val onPartialFlush: (partialText: String, reason: String) -> Unit
) : JPanel(BorderLayout()) {

    private companion object {
        const val DRAIN_MS = 100
        const val STALLED_MS = 60_000L
        const val TAIL_CAP = 200_000
        const val SEGMENT_CAP = 300_000
        const val FEED_MAX = 300
        const val PLACEHOLDER = "\u23F3 assembling structured response..."
    }

    // ---- cross-thread state ----
    private val queue = ConcurrentLinkedQueue<AgentStreamEvent>()
    private val pumpRequested = AtomicBoolean(false)
    @Volatile private var lastEventAtMs = 0L

    // ---- EDT-only state ----
    private var turnStartedAtMs = 0L
    private var model = "?"
    private val messageIds = LinkedHashSet<String>()
    private val segments = LinkedHashMap<String, StringBuilder>()
    private class ToolRow(val startedAtMs: Long, val label: JBLabel, val base: String)
    private val toolRows = LinkedHashMap<String, ToolRow>()
    private var lastNarr = ""
    private var lastThink = ""
    private var thinkExpanded = true

    /** Cumulative hidden-thinking token estimate for the current turn (header counter). */
    private var thinkingTokens = 0

    // ---- components ----
    private val headerLabel = JBLabel("").apply {
        font = font.deriveFont(Font.BOLD, 11f)
        foreground = JBColor(Color(0x2196F3), Color(0x64B5F6))
    }
    private val stalledLabel = JBLabel("\u26A0 stalled - see agent log").apply {
        font = font.deriveFont(Font.BOLD, 11f)
        foreground = JBColor(Color(0xB7950B), Color(0xF4D03F))
        border = JBUI.Borders.emptyLeft(8)
        isVisible = false
    }
    private val stopButton = JButton("\u25A0 Stop").apply {
        font = font.deriveFont(11f)
        isFocusPainted = false
        toolTipText = "Kill the coding agent process tree; partial output stays in chat"
    }
    private val feedPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        background = JBColor.background()
    }
    private val feedScroll = object : JBScrollPane(feedPanel) {
        // BorderLayout.NORTH honours preferred height: track the feed's actual content
        // height up to a cap instead of reserving a fixed 84px band for one notice line.
        override fun getPreferredSize(): Dimension {
            val content = feedPanel.preferredSize.height + insets.top + insets.bottom
            return Dimension(10, minOf(content, JBUI.scale(96)))
        }
    }.apply {
        border = JBUI.Borders.empty()
        isVisible = false
    }
    private val narrArea = JBTextArea().apply {
        isEditable = false; lineWrap = true; wrapStyleWord = true
        font = font.deriveFont(12f)
        background = JBColor.background()
        border = JBUI.Borders.empty(2, 8)
    }
    private val narrScroll = JBScrollPane(narrArea).apply {
        border = JBUI.Borders.empty()
        // Narration is usually a one-line placeholder once the protocol JSON starts
        // streaming - keep it compact; reasoning gets the free space instead.
        preferredSize = Dimension(10, 72)
        maximumSize = Dimension(Int.MAX_VALUE, 72)
        // Hidden while empty: an empty SOUTH band otherwise reserves 72px for nothing.
        // Visibility is driven by a document listener registered in init.
        isVisible = false
    }
    private val thinkArea = JBTextArea().apply {
        isEditable = false; lineWrap = true; wrapStyleWord = true
        font = font.deriveFont(11f)
        foreground = JBColor(Color(0x444444), Color(0xAAAAAA))
        background = JBColor.background()
        border = JBUI.Borders.empty(2, 8)
    }
    private val thinkScroll = JBScrollPane(thinkArea).apply {
        border = JBUI.Borders.empty()
        // No fixed height: mounted as BorderLayout.CENTER it fills whatever the
        // splitter gives the live panel.
        isVisible = true
    }
    private val thinkToggle = JButton().apply {
        font = font.deriveFont(Font.PLAIN, 11f)
        foreground = JBColor(Color(0x7D3C98), Color(0xBB8FCE))
        isFocusPainted = false; isContentAreaFilled = false; isBorderPainted = false
        horizontalAlignment = javax.swing.SwingConstants.LEFT
        isVisible = false
        addActionListener {
            thinkExpanded = !thinkExpanded
            thinkScroll.isVisible = thinkExpanded
            refreshThinkToggleText()
            revalidate(); repaint()
        }
    }

    private val drainTimer = Timer(DRAIN_MS) { tick() }.apply { isRepeats = true }

    init {
        background = JBColor.background()
        border = JBUI.Borders.compound(
            JBUI.Borders.customLine(JBColor(Color(0x2196F3), Color(0x64B5F6)), 2, 0, 0, 0),
            JBUI.Borders.empty(4, 6)
        )
        val headerRow = JPanel(BorderLayout()).apply {
            background = JBColor.background()
            add(JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0)).apply {
                background = JBColor.background()
                add(headerLabel); add(stalledLabel)
            }, BorderLayout.WEST)
            add(stopButton, BorderLayout.EAST)
        }
        // Layout: feed pinned on top (capped), reasoning fills the middle (the only
        // stream worth watching live), narration pinned at the bottom (capped - it
        // collapses to a placeholder once protocol JSON starts).
        val body = JPanel(BorderLayout()).apply {
            background = JBColor.background()
            add(feedScroll, BorderLayout.NORTH)
            add(JPanel(BorderLayout()).apply {
                background = JBColor.background()
                add(thinkToggle, BorderLayout.NORTH)
                add(thinkScroll, BorderLayout.CENTER)
            }, BorderLayout.CENTER)
            add(narrScroll, BorderLayout.SOUTH)
        }

        // Narration band exists only while there is narration to show (including the
        // "assembling structured response" placeholder) and vanishes when cleared.
        narrArea.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            private fun sync() {
                val hasText = narrArea.text.isNotBlank()
                if (narrScroll.isVisible != hasText) {
                    narrScroll.isVisible = hasText
                    revalidate(); repaint()
                }
            }
            override fun insertUpdate(e: javax.swing.event.DocumentEvent) = sync()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) = sync()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) = sync()
        })
        add(headerRow, BorderLayout.NORTH)
        add(body, BorderLayout.CENTER)
        stopButton.addActionListener {
            stopButton.isEnabled = false
            onStop()
        }
        isVisible = false
    }

    /** Entry point for the stream listener. Any thread. */
    fun onEvent(event: AgentStreamEvent) {
        queue.add(event)
        lastEventAtMs = System.currentTimeMillis()
        if (pumpRequested.compareAndSet(false, true)) {
            SwingUtilities.invokeLater { if (!drainTimer.isRunning) drainTimer.start() }
        }
    }

    /** Stops the drain timer. Call from the owning panel's dispose(). */
    fun dispose() {
        drainTimer.stop()
    }

    // ---- EDT pump ----

    private fun tick() {
        var changed = false
        while (true) {
            val ev = queue.poll() ?: break
            changed = true
            if (apply(ev)) return // turn finished; state already reset
        }
        if (changed) renderContent()
        if (turnStartedAtMs != 0L) renderHeader()
    }

    /** @return true when the event terminated the turn. */
    private fun apply(event: AgentStreamEvent): Boolean {
        if (turnStartedAtMs == 0L) activate()
        when (event) {
            is AgentStreamEvent.SessionStarted -> {
                model = event.model
                addFeedLine("session ${event.sessionId.take(8)} \u00B7 ${event.model}", neutralColor)
            }

            is AgentStreamEvent.NarrationDelta -> {
                messageIds.add(event.messageId)
                appendSegment(keyOf(event.messageId, event.thinking), event.text, replace = false)
            }

            is AgentStreamEvent.NarrationMessage -> {
                messageIds.add(event.messageId)
                appendSegment(keyOf(event.messageId, event.thinking), event.text, replace = true)
            }

            is AgentStreamEvent.ThinkingProgress -> thinkingTokens = event.estimatedTokens
            is AgentStreamEvent.ToolStarted -> {
                val base = "${event.name} ${event.summary}".trim()
                val label = feedLabel("\u23F3 $base", neutralColor)
                toolRows[event.toolUseId] = ToolRow(System.currentTimeMillis(), label, base)
                addFeedComponent(label)
            }

            is AgentStreamEvent.RateLimitUpdate -> Unit
            is AgentStreamEvent.ToolFinished -> {
                val row = toolRows.remove(event.toolUseId)
                val dur = row?.let {
                    " \u00B7 ${String.format("%.1f", (System.currentTimeMillis() - it.startedAtMs) / 1000.0)}s"
                } ?: ""
                val base = row?.base ?: (event.summary ?: event.toolUseId)
                val text = (if (event.ok) "\u2713 " else "\u2717 ") + base + dur
                val color = if (event.ok) okColor else errColor
                if (row != null) {
                    row.label.text = text; row.label.foreground = color
                } else addFeedComponent(feedLabel(text, color))
            }

            is AgentStreamEvent.Notice -> addFeedLine("\u2139 ${event.text}", noticeColor)
            is AgentStreamEvent.Completed -> {
                reset(); return true
            }

            is AgentStreamEvent.Failed -> {
                val partial = narrationDisplay()
                reset()
                onPartialFlush(partial, event.reason)
                return true
            }
        }
        return false
    }

    private fun activate() {
        turnStartedAtMs = System.currentTimeMillis()
        stopButton.isEnabled = true
        isVisible = true
        parent?.revalidate(); parent?.repaint()
    }

    private fun reset() {
        turnStartedAtMs = 0L
        model = "?"
        messageIds.clear(); segments.clear(); toolRows.clear()
        lastNarr = ""; lastThink = ""
        thinkingTokens = 0
        narrArea.text = ""; thinkArea.text = ""
        feedPanel.removeAll(); feedScroll.isVisible = false
        thinkExpanded = true; thinkScroll.isVisible = true; thinkToggle.isVisible = false
        stalledLabel.isVisible = false
        isVisible = false
        drainTimer.stop()
        pumpRequested.set(false)
        // Events already queued for the NEXT turn (fast auto-continues) must restart the pump.
        if (queue.isNotEmpty() && pumpRequested.compareAndSet(false, true)) drainTimer.start()
        parent?.revalidate(); parent?.repaint()
    }

    // ---- rendering ----

    private fun renderHeader() {
        val now = System.currentTimeMillis()
        val elapsed = ((now - turnStartedAtMs) / 1000).coerceAtLeast(0)
        val age = ((now - lastEventAtMs) / 1000).coerceAtLeast(0)
        val thinkPart = if (thinkingTokens > 0) " \u00B7 \uD83D\uDCAD ~$thinkingTokens tok" else ""
        headerLabel.text = "$model \u00B7 turn ${messageIds.size.coerceAtLeast(1)} \u00B7 " +
                String.format("%d:%02d", elapsed / 60, elapsed % 60) +
                " \u00B7 last event ${age}s ago" + thinkPart
        stalledLabel.isVisible = age * 1000 >= STALLED_MS
    }

    private fun renderContent() {
        val narr = narrationDisplay()
        if (narr != lastNarr) {
            val pinned = isPinned(narrScroll.verticalScrollBar)
            if (narr.startsWith(lastNarr)) narrArea.append(narr.substring(lastNarr.length))
            else narrArea.text = narr
            lastNarr = narr
            if (pinned) SwingUtilities.invokeLater {
                narrScroll.verticalScrollBar.value = narrScroll.verticalScrollBar.maximum
            }
        }
        val think = thinkingDisplay()
        if (think != lastThink) {
            val pinned = isPinned(thinkScroll.verticalScrollBar)
            if (think.startsWith(lastThink)) thinkArea.append(think.substring(lastThink.length))
            else thinkArea.text = think
            lastThink = think
            thinkToggle.isVisible = think.isNotBlank()
            refreshThinkToggleText()
            if (pinned && thinkExpanded) SwingUtilities.invokeLater {
                thinkScroll.verticalScrollBar.value = thinkScroll.verticalScrollBar.maximum
            }
        }
        feedScroll.isVisible = feedPanel.componentCount > 0
    }

    private fun refreshThinkToggleText() {
        val lines = thinkArea.text.lines().size
        thinkToggle.text = (if (thinkExpanded) "\u25BC" else "\u25BA") +
                "  \uD83D\uDCAD Reasoning \u00B7 live ($lines lines)"
    }

    // ---- text assembly ----

    private fun keyOf(messageId: String, thinking: Boolean) = messageId + if (thinking) "#t" else "#n"

    private fun appendSegment(key: String, text: String, replace: Boolean) {
        val b = segments.getOrPut(key) { StringBuilder() }
        if (replace) { b.setLength(0); b.append(if (text.length <= SEGMENT_CAP) text else text.take(SEGMENT_CAP)) }
        else if (b.length < SEGMENT_CAP) b.append(text)
    }

    private fun joined(suffix: String): String {
        val sb = StringBuilder()
        for ((key, b) in segments) {
            if (!key.endsWith(suffix)) continue
            if (sb.isNotEmpty()) sb.append("\n\n")
            sb.append(b)
        }
        return sb.toString()
    }

    private fun narrationDisplay(): String {
        var text = joined("#n")
        val cut = protocolMarkerIndex(text)
        if (cut >= 0) {
            // Instead of hiding the whole protocol block behind a placeholder, stream
            // the `message` field value out of the partial JSON as it arrives.
            val streamedMessage = extractJsonStringField(text.substring(cut), InteractionRequestSchema.RESP_MESSAGE)
                ?.takeIf { it.isNotBlank() }
            val prose = if (cut == 0) "" else text.take(cut).trimEnd() + "\n\n"
            text = prose + (streamedMessage ?: PLACEHOLDER)
        }
        return capTail(text)
    }

    private fun thinkingDisplay(): String {
        // CLI thinking deltas (empty on CLIs that redact thinking text) plus the
        // `reasoning` field value streamed out of the partial protocol JSON.
        val thinking = joined("#t")
        val narr = joined("#n")
        val cut = protocolMarkerIndex(narr)
        val jsonReasoning = if (cut >= 0)
            extractJsonStringField(narr.substring(cut), InteractionRequestSchema.RESP_REASONING)
                ?.takeIf { it.isNotBlank() }
        else null
        val combined = listOfNotNull(thinking.takeIf { it.isNotBlank() }, jsonReasoning).joinToString("\n\n")
        return capTail(combined)
    }

    /**
     * Earliest index of a protocol-block marker in the visible narration, or -1.
     * Markers come from the response schema (same source as the codec) plus the
     * json fence the model actually wraps the protocol block in.
     */
    private fun protocolMarkerIndex(text: String): Int {
        if (text.isBlank()) return -1
        val markers = listOf(
            "```json",
            "\"${InteractionRequestSchema.RESP_MESSAGE}\"",
            "\"${InteractionRequestSchema.RESP_MODIFICATIONS}\"",
            "\"${InteractionRequestSchema.REQUESTED_VIEWS}\""
        )
        var best = -1
        for (m in markers) {
            val i = text.indexOf(m)
            if (i >= 0 && (best < 0 || i < best)) best = i
        }
        if (best < 0 && text.trimStart().startsWith("{")) best = 0
        return best
    }

    /**
     * Streams the value of a top-level string field out of a PARTIAL protocol-JSON
     * block: finds `"field"`, skips the colon, then decodes the string literal up to
     * the closing quote — or up to the end of the received prefix while the value is
     * still being streamed. Returns null while the field has not appeared yet.
     * Tolerant of an escape sequence truncated at the stream cut point.
     */
    private fun extractJsonStringField(json: String, field: String): String? {
        val keyIdx = json.indexOf("\"$field\"")
        if (keyIdx < 0) return null
        var i = keyIdx + field.length + 2
        while (i < json.length && (json[i] == ':' || json[i].isWhitespace())) i++
        if (i >= json.length || json[i] != '"') return null
        i++
        val sb = StringBuilder()
        while (i < json.length) {
            val c = json[i]
            if (c == '\\') {
                if (i + 1 >= json.length) break
                when (json[i + 1]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> Unit
                    'u' -> {
                        if (i + 6 > json.length) {
                            i = json.length - 2
                        } else {
                            json.substring(i + 2, i + 6).toIntOrNull(16)?.let { code -> sb.append(code.toChar()) }
                            i += 4
                        }
                    }

                    else -> sb.append(json[i + 1])
                }
                i += 2
            } else if (c == '"') {
                break
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    private fun capTail(s: String): String =
        if (s.length <= TAIL_CAP) s
        else "...[trimmed ${s.length - TAIL_CAP} chars]\n" + s.substring(s.length - TAIL_CAP)

    // ---- feed helpers ----

    private val neutralColor = JBColor(Color(0x555555), Color(0x999999))
    private val noticeColor = JBColor(Color(0xB7950B), Color(0xF4D03F))
    private val okColor = JBColor(Color(0x1E8449), Color(0x58D68D))
    private val errColor = JBColor(Color(0xC0392B), Color(0xEC7063))

    private fun feedLabel(text: String, color: Color) = JBLabel(text).apply {
        font = Font(Font.MONOSPACED, Font.PLAIN, 10)
        foreground = color
        alignmentX = Component.LEFT_ALIGNMENT
    }

    private fun addFeedLine(text: String, color: Color) = addFeedComponent(feedLabel(text, color))

    private fun addFeedComponent(label: JBLabel) {
        if (feedPanel.componentCount >= FEED_MAX) feedPanel.remove(0)
        feedPanel.add(label)
        feedScroll.isVisible = true
        feedPanel.revalidate(); feedPanel.repaint()
        SwingUtilities.invokeLater {
            feedScroll.verticalScrollBar.value = feedScroll.verticalScrollBar.maximum
        }
    }

    private fun isPinned(bar: JScrollBar) = bar.value + bar.visibleAmount >= bar.maximum - 16
}