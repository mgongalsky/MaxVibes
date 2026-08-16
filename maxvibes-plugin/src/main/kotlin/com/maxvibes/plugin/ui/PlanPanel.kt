package com.maxvibes.plugin.ui

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.maxvibes.domain.model.planning.PlanStep
import com.maxvibes.domain.model.planning.PlanStepStatus
import com.maxvibes.domain.model.planning.TaskPlan
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

private val accentColor = JBColor(Color(0x2196F3), Color(0x64B5F6))
private val doneColor = JBColor(Color(0x777777), Color(0x999999))
private val completeColor = JBColor(Color(0x1E8449), Color(0x58D68D))
private val trackColor = JBColor(Color(0xDEDEDE), Color(0x2B2D30))

/**
 * Pinned planner panel: a collapsible checklist of the session's [TaskPlan].
 *
 * Pure view component — receives snapshots via [update], reports user actions
 * through the two callbacks, and never touches services or repositories.
 *
 * Rendering rules:
 *  - header: collapse arrow, plan title (clickable when the plan has a docPath —
 *    opens PLAN.md), then a progress bar and `N/M ✓` on the right (green when
 *    complete). Both live in the header, so they stay visible after a completed
 *    plan auto-collapses;
 *  - body: one row per step — checkbox + title. Checking PENDING/IN_PROGRESS
 *    marks the step DONE; unchecking DONE/SKIPPED returns it to PENDING.
 *    A step title with a docPath is clickable and opens its STEP_N.md;
 *  - IN_PROGRESS is bold blue, DONE is gray, SKIPPED is gray italic with a
 *    `(skipped)` suffix, PENDING is plain;
 *  - the collapsed flag survives [update] calls; when the plan becomes complete
 *    the panel auto-collapses ONCE (the user can re-expand it afterwards).
 *
 * Threading: EDT only, like every other Swing component in this package.
 */
class PlanPanel(
    private val onToggleStep: (stepId: String, newStatus: PlanStepStatus) -> Unit,
    private val onOpenDoc: (docPath: String) -> Unit
) : JPanel(BorderLayout()) {

    private var collapsed = false
    private var autoCollapsedOnComplete = false
    private var suppressEvents = false

    private val toggleButton = JButton("\u25BE").apply {
        font = font.deriveFont(11f)
        isFocusPainted = false; isContentAreaFilled = false; isBorderPainted = false
        margin = JBUI.emptyInsets()
        toolTipText = "Collapse / expand plan"
        addActionListener {
            collapsed = !collapsed
            stepsPanel.isVisible = !collapsed
            text = if (collapsed) "\u25B8" else "\u25BE"
            revalidate(); repaint()
        }
    }

    private val titleLabel = JBLabel("").apply {
        font = font.deriveFont(Font.BOLD, 12f)
    }

    private val progressBar = PlanProgressBar()

    private val progressLabel = JBLabel("").apply {
        font = font.deriveFont(Font.BOLD, 11f)
        border = JBUI.Borders.emptyRight(4)
    }

    private val stepsPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        background = JBColor.background()
        border = JBUI.Borders.empty(2, 22, 4, 6)
    }

    /** Re-attachable click listener on [titleLabel]; removed before each rebuild. */
    private var titleClickListener: MouseAdapter? = null

    init {
        background = JBColor.background()
        border = JBUI.Borders.compound(
            JBUI.Borders.customLine(accentColor, 2, 0, 0, 0),
            JBUI.Borders.empty(4, 6)
        )
        val headerRow = JPanel(BorderLayout()).apply {
            background = JBColor.background()
            add(JPanel(FlowLayout(FlowLayout.LEFT, 2, 0)).apply {
                background = JBColor.background()
                add(toggleButton)
                add(JBLabel("\uD83D\uDCCB"))
                add(titleLabel)
            }, BorderLayout.WEST)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
                background = JBColor.background()
                add(progressBar)
                add(progressLabel)
            }, BorderLayout.EAST)
        }
        add(headerRow, BorderLayout.NORTH)
        add(stepsPanel, BorderLayout.CENTER)
        isVisible = false
    }

    /** Full re-render from a snapshot; null hides the panel. EDT only. */
    fun update(plan: TaskPlan?) {
        if (plan == null) {
            isVisible = false
            autoCollapsedOnComplete = false
            return
        }
        suppressEvents = true
        try {
            rebuild(plan)
        } finally {
            suppressEvents = false
        }
        isVisible = true
        revalidate(); repaint()
    }

    private fun rebuild(plan: TaskPlan) {
        // Header: title (+ optional doc link) and progress.
        titleLabel.text = plan.title
        titleClickListener?.let { titleLabel.removeMouseListener(it) }
        titleClickListener = null
        if (plan.docPath != null) {
            titleLabel.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            titleLabel.foreground = accentColor
            titleLabel.toolTipText = plan.docPath
            titleClickListener = object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) = onOpenDoc(plan.docPath!!)
            }.also { titleLabel.addMouseListener(it) }
        } else {
            titleLabel.cursor = Cursor.getDefaultCursor()
            titleLabel.foreground = JBColor.foreground()
            titleLabel.toolTipText = null
        }
        progressLabel.text = "${plan.doneCount}/${plan.steps.size} \u2713"
        progressLabel.foreground = if (plan.isComplete) completeColor else JBColor.GRAY
        progressBar.setProgress(plan.doneCount, plan.steps.size, plan.isComplete)

        // Auto-collapse once when the plan reaches completion; re-arm when it reopens.
        if (plan.isComplete && !autoCollapsedOnComplete) {
            collapsed = true
            autoCollapsedOnComplete = true
        } else if (!plan.isComplete) {
            autoCollapsedOnComplete = false
        }
        stepsPanel.isVisible = !collapsed
        toggleButton.text = if (collapsed) "\u25B8" else "\u25BE"

        // Body: rebuilt from scratch — plans are small, and discarding rows wholesale
        // avoids listener-leak bookkeeping.
        stepsPanel.removeAll()
        plan.steps.forEach { step -> stepsPanel.add(stepRow(step)) }
    }

    private fun stepRow(step: PlanStep): JPanel {
        val checkbox = JBCheckBox().apply {
            isSelected = step.status == PlanStepStatus.DONE || step.status == PlanStepStatus.SKIPPED
            addActionListener {
                if (suppressEvents) return@addActionListener
                val newStatus = when (step.status) {
                    PlanStepStatus.PENDING, PlanStepStatus.IN_PROGRESS -> PlanStepStatus.DONE
                    PlanStepStatus.DONE, PlanStepStatus.SKIPPED -> PlanStepStatus.PENDING
                }
                onToggleStep(step.id, newStatus)
            }
        }
        val text = if (step.status == PlanStepStatus.SKIPPED) "${step.title} (skipped)" else step.title
        val label = JBLabel(text).apply {
            font = when (step.status) {
                PlanStepStatus.IN_PROGRESS -> font.deriveFont(Font.BOLD, 12f)
                PlanStepStatus.SKIPPED -> font.deriveFont(Font.ITALIC, 12f)
                else -> font.deriveFont(Font.PLAIN, 12f)
            }
            foreground = when (step.status) {
                PlanStepStatus.IN_PROGRESS -> accentColor
                PlanStepStatus.DONE, PlanStepStatus.SKIPPED -> doneColor
                PlanStepStatus.PENDING -> JBColor.foreground()
            }
            if (step.docPath != null) {
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                toolTipText = step.docPath
                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) = onOpenDoc(step.docPath!!)
                })
            }
        }
        return JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            background = JBColor.background()
            add(checkbox)
            add(label)
        }
    }
}

/**
 * Compact completion bar for the plan header, drawn like the subscription-limits
 * bar: rounded track, severity-free accent fill, subtle top highlight.
 *
 * Fed from the same counts as the `N/M ✓` label, so the two can never disagree.
 * A complete plan fills the whole track even when some steps were SKIPPED rather
 * than DONE — a green half-empty bar would read as a contradiction.
 */
private class PlanProgressBar : JComponent() {

    private var done = 0
    private var total = 0
    private var complete = false

    init {
        preferredSize = Dimension(JBUI.scale(72), JBUI.scale(14))
        minimumSize = Dimension(JBUI.scale(40), JBUI.scale(12))
    }

    fun setProgress(done: Int, total: Int, complete: Boolean) {
        this.done = done
        this.total = total
        this.complete = complete
        toolTipText = if (total > 0) "$done of $total steps done" else null
        repaint()
    }

    override fun paintComponent(g: Graphics) {
        if (total <= 0) return
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            val barH = JBUI.scale(9)
            val barY = (height - barH) / 2
            val barW = width
            val arc = barH
            g2.color = trackColor
            g2.fillRoundRect(0, barY, barW, barH, arc, arc)
            g2.color = JBColor.border()
            g2.drawRoundRect(0, barY, barW - 1, barH - 1, arc, arc)

            val fillW = when {
                complete -> barW
                done <= 0 -> 0
                else -> (barW * done / total).coerceIn(barH, barW)
            }
            if (fillW > 0) {
                g2.color = if (complete) completeColor else accentColor
                g2.fillRoundRect(0, barY, fillW, barH, arc, arc)
                g2.color = Color(255, 255, 255, 30)
                g2.fillRoundRect(0, barY, fillW, barH / 2, arc, arc)
            }
        } finally {
            g2.dispose()
        }
    }
}
