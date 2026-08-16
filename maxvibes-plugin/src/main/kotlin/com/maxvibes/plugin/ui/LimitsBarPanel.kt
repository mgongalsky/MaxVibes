package com.maxvibes.plugin.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.maxvibes.application.port.output.AgentStreamEvent
import com.maxvibes.application.port.output.SubscriptionUsage
import com.maxvibes.application.port.output.UsageWindow
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridLayout
import java.awt.RenderingHints
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.UIManager

/**
 * Subscription usage block: one row per rate-limit window the agent actually
 * reports, Claude-style severity colors.
 *
 * Rows are created on demand and keyed by [UsageWindow.id], because the set of
 * windows is agent-specific: Claude reports a 5h session plus weekly windows,
 * Codex a single weekly one. Labels are derived from the window length rather
 * than a fixed vocabulary, so an unseen window still renders sensibly.
 *
 * Two complementary data sources, merged per row:
 *  - usage snapshots ([onUsage]): authoritative percents and reset times,
 *    present even between turns;
 *  - stream rate-limit events ([onRateLimit], during turns): status escalation
 *    (allowed_warning / rejected) and occasional percents - Claude 2.1.138 was
 *    observed to omit `utilization` entirely at times, hence merge-not-overwrite:
 *    a percent once seen is never erased by an event without one (drawn as ~NN%
 *    until the next authoritative value).
 * A window with no data from either source renders gray with an em dash -
 * unknown must never look like zero. Hidden until the first data of any kind;
 * in-memory only.
 */
class LimitsBarPanel : JPanel(GridLayout(0, 1, 0, JBUI.scale(3))) {

    private val rows = LinkedHashMap<String, UsageRow>()

    init {
        isOpaque = false
        border = JBUI.Borders.empty(6, 2, 2, 2)
        isVisible = false
    }

    /** Stream rate-limit event entry point - safe to call from the transport reader thread. */
    fun onRateLimit(e: AgentStreamEvent.RateLimitUpdate) {
        SwingUtilities.invokeLater {
            merge(
                UsageWindow(
                    id = e.kind,
                    windowMinutes = e.windowMinutes ?: NAMED_WINDOW_MINUTES[e.kind],
                    utilizationPct = e.utilizationPct,
                    resetsAtEpochSec = e.resetsAtEpochSec,
                    status = e.status
                )
            )
            reveal()
        }
    }

    /** Usage snapshot entry point - safe to call from any thread. */
    fun onUsage(usage: SubscriptionUsage) {
        SwingUtilities.invokeLater {
            usage.windows.forEach { merge(it) }
            if (usage.windows.isNotEmpty()) reveal()
        }
    }

    /** Drops every row: one agent's windows must never linger in another agent's bar. */
    fun reset() {
        SwingUtilities.invokeLater {
            rows.clear()
            removeAll()
            isVisible = false
            revalidate()
            repaint()
        }
    }

    private fun merge(window: UsageWindow) {
        val row = rows.getOrPut(window.id) {
            UsageRow(labelFor(window)).also {
                add(it)
                revalidate()
            }
        }
        row.merge(window.utilizationPct, window.resetsAtEpochSec, window.status)
    }

    private fun reveal() {
        if (!isVisible) {
            isVisible = true
            parent?.revalidate()
            parent?.repaint()
        }
    }

    private companion object {
        /** Claude names its windows instead of measuring them; these are the known lengths. */
        private val NAMED_WINDOW_MINUTES = mapOf(
            "five_hour" to 300,
            "seven_day" to 10_080,
            "seven_day_opus" to 10_080
        )
    }
}

private fun labelFor(window: UsageWindow): String {
    val minutes = window.windowMinutes
    val base = when {
        minutes == null -> "Limit"
        minutes >= 1440 -> if (minutes / 1440 == 7) "Week (7d)" else "Limit (${minutes / 1440}d)"
        minutes >= 60 -> "Session (${minutes / 60}h)"
        else -> "Window (${minutes}m)"
    }
    return if (window.name.isNullOrBlank()) base else "$base ${window.name}"
}

/** One usage window: label + rounded severity-colored bar + right-aligned details. */
private class UsageRow(private val label: String) : JComponent() {

    /** False until the first data (event or usage snapshot) for this window. */
    private var hasData = false

    /** Last KNOWN percent - sticky across stream events that omit `utilization`. */
    private var pct: Int? = null

    /** True when the latest stream event omitted the percent while an older one is shown. */
    private var pctStale = false

    private var status: String = "allowed"
    private var resetsAtEpochSec: Long? = null

    init {
        preferredSize = Dimension(JBUI.scale(220), JBUI.scale(17))
        minimumSize = Dimension(JBUI.scale(120), JBUI.scale(15))
        toolTipText = "$label: no usage data yet"
    }

    /**
     * Percent and reset time stick until replaced. [newStatus] is non-null only for
     * stream events, and a status-carrying update without a percent means the old
     * percent is now merely the last known one.
     */
    fun merge(newPct: Int?, newResetsAt: Long?, newStatus: String?) {
        if (newPct == null && newResetsAt == null && newStatus == null) return
        hasData = true
        if (newPct != null) {
            pct = newPct
            pctStale = false
        } else if (newStatus != null && pct != null) {
            pctStale = true
        }
        if (newStatus != null) status = newStatus
        if (newResetsAt != null) resetsAtEpochSec = newResetsAt
        refreshTooltip()
        repaint()
    }

    private fun refreshTooltip() {
        toolTipText = buildString {
            append(label).append(": ")
            val p = pct
            if (p != null) {
                append(p).append("% used")
                if (pctStale) append(" (last known)")
            } else {
                append("status ").append(statusWord())
            }
            fmtReset(resetsAtEpochSec)?.let { append(", resets ").append(it) }
        }
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

            val h = height
            val baseFont = font ?: UIManager.getFont("Label.font")
            val labelFont = baseFont.deriveFont(Font.BOLD, 11f)
            val detailFont = baseFont.deriveFont(11f)

            // Left: window label (gray while the window is still unknown).
            g2.font = labelFont
            val fm = g2.fontMetrics
            val ty = (h + fm.ascent - fm.descent) / 2
            g2.color = if (hasData) JBColor.foreground() else JBColor.GRAY
            g2.drawString(label, 0, ty)
            val labelW = JBUI.scale(78).coerceAtLeast(fm.stringWidth(label) + JBUI.scale(8))

            // Right: percent (or status word / em dash) + reset time.
            val severity = if (hasData) severityColor() else JBColor.GRAY
            val pctText = when {
                !hasData -> "\u2014"
                pct != null -> (if (pctStale) "~" else "") + "${pct}%"
                else -> statusWord()
            }
            val resetText = if (hasData) fmtReset(resetsAtEpochSec)?.let { " \u00B7 resets $it" } ?: "" else ""
            g2.font = labelFont
            val pctW = g2.fontMetrics.stringWidth(pctText)
            g2.font = detailFont
            val resetW = g2.fontMetrics.stringWidth(resetText)
            val rightX = (width - pctW - resetW).coerceAtLeast(labelW)
            g2.font = labelFont
            g2.color = severity
            g2.drawString(pctText, rightX, ty)
            if (resetText.isNotEmpty()) {
                g2.font = detailFont
                g2.color = JBColor.GRAY
                g2.drawString(resetText, rightX + pctW, ty)
            }

            // Middle: the bar itself.
            val barH = JBUI.scale(9)
            val barY = (h - barH) / 2
            val barX = labelW
            val barW = (rightX - JBUI.scale(10)) - barX
            if (barW > JBUI.scale(24)) {
                val arc = barH
                g2.color = TRACK
                g2.fillRoundRect(barX, barY, barW, barH, arc, arc)
                g2.color = JBColor.border()
                g2.drawRoundRect(barX, barY, barW - 1, barH - 1, arc, arc)

                val p = pct
                val fillW = when {
                    !hasData -> 0
                    p != null && p > 0 -> (barW * p.coerceIn(0, 100) / 100).coerceAtLeast(barH)
                    p == null && isEscalated() -> barW
                    else -> 0
                }
                if (fillW > 0) {
                    g2.color = severity
                    g2.fillRoundRect(barX, barY, fillW, barH, arc, arc)
                    // Subtle top highlight for a hint of depth in both themes.
                    g2.color = Color(255, 255, 255, 30)
                    g2.fillRoundRect(barX, barY, fillW, barH / 2, arc, arc)
                }
            }
        } finally {
            g2.dispose()
        }
    }

    private fun isEscalated(): Boolean =
        status == "rejected" || status == "exceeded" || status == "allowed_warning"

    private fun statusWord(): String = when (status) {
        "rejected", "exceeded" -> "limit!"
        "allowed_warning" -> "warn"
        else -> "ok"
    }

    /** Claude-style severity ramp: green -> yellow -> orange -> red. */
    private fun severityColor(): Color {
        val p = pct ?: 0
        return when {
            status == "rejected" || status == "exceeded" || p >= 95 -> RED
            status == "allowed_warning" || p >= 80 -> ORANGE
            p >= 60 -> YELLOW
            else -> GREEN
        }
    }

    private companion object {
        private val TRACK = JBColor(Color(0xDEDEDE), Color(0x2B2D30))
        private val GREEN = JBColor(Color(0x4CAF50), Color(0x549159))
        private val YELLOW = JBColor(Color(0xC9A227), Color(0xD9A343))
        private val ORANGE = JBColor(Color(0xE08D2B), Color(0xCE8235))
        private val RED = JBColor(Color(0xD64540), Color(0xCF5B56))
    }
}

private fun fmtReset(epochSec: Long?): String? = epochSec?.let {
    val t = Instant.ofEpochSecond(it).atZone(ZoneId.systemDefault())
    val pattern = if (t.toLocalDate() == LocalDate.now()) "HH:mm" else "EEE HH:mm"
    t.format(DateTimeFormatter.ofPattern(pattern))
}
