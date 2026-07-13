package com.maxvibes.plugin.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.maxvibes.application.port.output.AgentStreamEvent
import com.maxvibes.application.port.output.SubscriptionUsage
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
 * Subscription usage block (Set 9): one row per rate-limit window (5h session /
 * 7d week), Claude-style severity colors.
 *
 * Two complementary data sources, merged per row:
 *  - OAuth usage polling ([onUsage], every ~60s): authoritative percents and
 *    reset times, present even between turns;
 *  - CLI rate_limit_events ([onRateLimit], during turns): status escalation
 *    (allowed_warning / rejected) and occasional percents - observed on 2.1.138
 *    to omit `utilization` entirely at times, hence merge-not-overwrite: a
 *    percent once seen is never erased by an event without one (drawn as ~NN%
 *    until the next authoritative value).
 * A window with no data from either source renders gray with an em dash -
 * unknown must never look like zero. Hidden until the first data of any kind;
 * in-memory only (v1).
 */
class LimitsBarPanel : JPanel(GridLayout(2, 1, 0, JBUI.scale(3))) {

    private val fiveHour = UsageRow("Session (5h)")
    private val week = UsageRow("Week (7d)")

    init {
        isOpaque = false
        border = JBUI.Borders.empty(6, 2, 2, 2)
        isVisible = false
        add(fiveHour)
        add(week)
    }

    /** CLI rate_limit_event entry point - safe to call from the transport reader thread. */
    fun onRateLimit(e: AgentStreamEvent.RateLimitUpdate) {
        SwingUtilities.invokeLater {
            val row = when (e.kind) {
                "five_hour" -> fiveHour
                "seven_day" -> week
                else -> return@invokeLater
            }
            row.mergeEvent(e.utilizationPct, e.status, e.resetsAtEpochSec)
            reveal()
        }
    }

    /** OAuth usage snapshot entry point - safe to call from any thread. */
    fun onUsage(usage: SubscriptionUsage) {
        SwingUtilities.invokeLater {
            usage.fiveHour?.let { fiveHour.mergeUsage(it.utilizationPct, it.resetsAtEpochSec) }
            usage.sevenDay?.let { week.mergeUsage(it.utilizationPct, it.resetsAtEpochSec) }
            if (usage.fiveHour != null || usage.sevenDay != null) reveal()
        }
    }

    private fun reveal() {
        if (!isVisible) {
            isVisible = true
            parent?.revalidate()
            parent?.repaint()
        }
    }
}

/** One usage window: label + rounded severity-colored bar + right-aligned details. */
private class UsageRow(private val label: String) : JComponent() {

    /** False until the first data (event or usage snapshot) for this window. */
    private var hasData = false

    /** Last KNOWN percent - sticky across CLI events that omit `utilization`. */
    private var pct: Int? = null

    /** True when the latest CLI event omitted the percent while an older one is shown. */
    private var pctStale = false

    private var status: String = "allowed"
    private var resetsAtEpochSec: Long? = null

    init {
        preferredSize = Dimension(JBUI.scale(220), JBUI.scale(17))
        minimumSize = Dimension(JBUI.scale(120), JBUI.scale(15))
        toolTipText = "$label: no usage data yet"
    }

    /** CLI event merge: status always refreshes; percent and reset time stick until replaced. */
    fun mergeEvent(newPct: Int?, newStatus: String, newResetsAt: Long?) {
        hasData = true
        if (newPct != null) {
            pct = newPct
            pctStale = false
        } else if (pct != null) {
            pctStale = true
        }
        status = newStatus
        if (newResetsAt != null) resetsAtEpochSec = newResetsAt
        refreshTooltip()
        repaint()
    }

    /** OAuth snapshot merge: authoritative percent/reset; status untouched (CLI owns it). */
    fun mergeUsage(newPct: Int?, newResetsAt: Long?) {
        if (newPct == null && newResetsAt == null) return
        hasData = true
        if (newPct != null) {
            pct = newPct
            pctStale = false
        }
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
