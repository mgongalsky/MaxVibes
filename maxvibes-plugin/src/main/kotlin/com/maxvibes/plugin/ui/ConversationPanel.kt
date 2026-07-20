package com.maxvibes.plugin.ui

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.EditorTextField
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.maxvibes.domain.model.code.CodeGranularity
import com.maxvibes.domain.model.code.RequestedViewInfo
import com.maxvibes.domain.model.modification.AppliedModInfo
import com.maxvibes.domain.model.modification.ModificationCategory
import com.maxvibes.domain.model.modification.ModificationResult
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import com.maxvibes.domain.model.interaction.AttachedImage

sealed class MessageSegment {
    data class Text(val content: String) : MessageSegment()
    data class Code(val lang: String, val code: String) : MessageSegment()
}

/**
 * Mutable view handle for a command block: the controller drives it through
 * pending → running → result / declined. [setQueued] marks a command that is
 * waiting for its turn in a Run all sequence.
 */
interface CommandBlockView {
    fun setRunning()
    fun setQueued()
    fun setResult(headline: String, output: String, ok: Boolean)
    fun setDeclined(comment: String?)
}

/**
 * Mutable view handle for an interactive question block: the controller freezes it
 * once answered (chosen option shown) or dismissed (the user typed a reply instead).
 */
interface QuestionBlockView {
    fun setAnswered(answer: String)
    fun setDismissed()
}

/** View handle for the Run all / Decline all bar above a command batch. */
interface CommandBatchBarView {
    fun dismiss()
}

/** View handle for the post-apply errors block. */
interface PostApplyErrorsView {
    fun setSent()
    fun setDismissed()
}

class ConversationPanel(
    private val project: Project,
    private val onNavigateToPath: (String) -> Unit
) : JPanel(BorderLayout()) {

    private val messagesPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        background = JBColor.background()
        border = JBUI.Borders.empty(8, 10)
    }

    val scrollPane = JBScrollPane(messagesPanel).apply {
        border = JBUI.Borders.empty()
        verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
    }

    /**
     * Appends a status icon to the visually last piece of message content.
     * For the user bubble this edits the plain JBTextArea text; for assistant
     * bubbles it re-renders the last markdown segment with the icon appended.
     */
    private var appendToLast: ((String) -> Unit)? = null

    init {
        background = JBColor.background()
        add(scrollPane, BorderLayout.CENTER)
    }

    fun clearMessages() {
        messagesPanel.removeAll(); appendToLast = null
        messagesPanel.revalidate(); messagesPanel.repaint()
    }

    fun addUserBubble(text: String, images: List<AttachedImage> = emptyList()) {
        if (images.isEmpty()) {
            addComp(userBubble(text)); return
        }
        val bg = JBColor(Color(0xEBF5FB), Color(0x1B2A3B))
        val area = contentArea(text, bg)
        appendToLast = { icon -> area.text = area.text.trimEnd() + "  " + icon }
        val thumbs = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4)).apply {
            background = bg
            images.forEach { img ->
                add(JBLabel(ImageAttachments.thumbnail(img, 280, 180) ?: javax.swing.ImageIcon()).apply {
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    toolTipText = "Click to open full size"
                    addMouseListener(object : MouseAdapter() {
                        override fun mouseClicked(e: MouseEvent) = ImageAttachments.openInViewer(project, img)
                    })
                })
            }
        }
        addComp(bubble(bg, JBColor(Color(0x2E86C1), Color(0x5DADE2))).also { p ->
            p.add(roleLabel("\uD83D\uDC64 You", JBColor(Color(0x1A5276), Color(0x85C1E9))), BorderLayout.NORTH)
            p.add(JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                background = bg
                area.alignmentX = Component.LEFT_ALIGNMENT
                thumbs.alignmentX = Component.LEFT_ALIGNMENT
                add(area); add(thumbs)
            }, BorderLayout.CENTER)
        })
    }

    fun addAssistantBubble(
        text: String,
        tokenInfo: String? = null,
        modifications: List<ModificationResult> = emptyList(),
        metaFiles: List<String> = emptyList(),
        reasoning: String? = null,
        requestedViews: List<RequestedViewInfo> = emptyList(),
        appliedModifications: List<AppliedModInfo> = emptyList()
    ) = addComp(
        assistantBubble(
            text,
            tokenInfo,
            modifications,
            metaFiles,
            reasoning,
            requestedViews,
            appliedModifications
        )
    )

    fun addSystemBubble(text: String) {
        if (text.isNotBlank()) addComp(systemBubble(text))
    }

    fun appendIconToLastBubble(icon: String) {
        SwingUtilities.invokeLater {
            val append = appendToLast
            if (append != null) append(icon) else addSystemBubble(icon)
            messagesPanel.revalidate(); messagesPanel.repaint()
        }
    }

    private fun addComp(c: JComponent) {
        c.alignmentX = Component.LEFT_ALIGNMENT
        messagesPanel.add(c)
        messagesPanel.add(
            Box.createVerticalStrut(5).also { (it as? JComponent)?.alignmentX = Component.LEFT_ALIGNMENT })
        messagesPanel.revalidate(); messagesPanel.repaint()
        SwingUtilities.invokeLater { scrollPane.verticalScrollBar.value = scrollPane.verticalScrollBar.maximum }
    }

    // ==================== Segment Parser ====================

    private fun parseSegments(text: String): List<MessageSegment> {
        val segments = mutableListOf<MessageSegment>()
        val lines = text.lines()
        var inCode = false
        var lang = ""
        val codeLines = mutableListOf<String>()
        val textLines = mutableListOf<String>()

        fun flushText() {
            val t = textLines.joinToString("\n").trim()
            if (t.isNotBlank()) segments += MessageSegment.Text(t)
            textLines.clear()
        }

        fun flushCode() {
            val c = codeLines.joinToString("\n")
            segments += MessageSegment.Code(lang, c)
            codeLines.clear()
        }

        for (line in lines) {
            if (!inCode) {
                val m = Regex("^```(\\S*)").find(line.trim())
                if (m != null) {
                    flushText()
                    inCode = true
                    lang = m.groupValues[1]
                } else {
                    textLines += line
                }
            } else {
                if (line.trim() == "```") {
                    flushCode()
                    inCode = false
                    lang = ""
                } else {
                    codeLines += line
                }
            }
        }
        if (inCode) flushCode() else flushText()
        return segments
    }

    // ==================== Bubble builders ====================

    private fun userBubble(text: String): JPanel {
        val bg = JBColor(Color(0xEBF5FB), Color(0x1B2A3B))
        val area = contentArea(text, bg)
        appendToLast = { icon -> area.text = area.text.trimEnd() + "  " + icon }
        return bubble(bg, JBColor(Color(0x2E86C1), Color(0x5DADE2))).also { p ->
            p.add(roleLabel("\uD83D\uDC64 You", JBColor(Color(0x1A5276), Color(0x85C1E9))), BorderLayout.NORTH)
            p.add(area, BorderLayout.CENTER)
        }
    }

    private fun assistantBubble(
        text: String,
        tokenInfo: String?,
        mods: List<ModificationResult>,
        metaFiles: List<String> = emptyList(),
        reasoning: String? = null,
        requestedViews: List<RequestedViewInfo> = emptyList(),
        appliedModifications: List<AppliedModInfo> = emptyList()
    ): JPanel {
        val bg = JBColor(Color(0xEAF7EA), Color(0x1B2B1E))
        val ok = mods.filterIsInstance<ModificationResult.Success>()
        val fail = mods.filterIsInstance<ModificationResult.Failure>()
        // Reasoning lives in its own collapsible sub-panel (ThinkingBubble feature) —
        // it no longer participates in the footer or its hasDetails check.
        val hasFooter = !tokenInfo.isNullOrBlank()
                || ok.isNotEmpty() || fail.isNotEmpty()
                || metaFiles.isNotEmpty()
                || requestedViews.isNotEmpty()
                || appliedModifications.isNotEmpty()
        val hasReasoning = !reasoning.isNullOrBlank()

        val segments = parseSegments(text)
        val contentPanel = buildSegmentsPanel(segments, bg)

        return bubble(bg, JBColor(Color(0x239B56), Color(0x58D68D))).also { p ->
            p.add(roleLabel("\uD83E\uDD16 MaxVibes", JBColor(Color(0x1D6A39), Color(0x82E0AA))), BorderLayout.NORTH)
            p.add(contentPanel, BorderLayout.CENTER)
            if (hasReasoning || hasFooter) {
                val south = JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    background = bg
                }
                if (!reasoning.isNullOrBlank()) {
                    south.add(
                        collapsibleReasoningPanel(reasoning, bg).also { it.alignmentX = Component.LEFT_ALIGNMENT }
                    )
                }
                if (hasFooter) {
                    south.add(
                        collapsibleFooter(tokenInfo, ok, fail, bg, metaFiles, requestedViews, appliedModifications)
                            .also { it.alignmentX = Component.LEFT_ALIGNMENT }
                    )
                }
                p.add(south, BorderLayout.SOUTH)
            }
        }
    }

    private fun buildSegmentsPanel(segments: List<MessageSegment>, bg: Color): JPanel {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = bg
            alignmentX = Component.LEFT_ALIGNMENT
        }
        for (seg in segments) {
            when (seg) {
                is MessageSegment.Text -> {
                    val pane = MarkdownRenderer.createPane(seg.content, onNavigateToPath)
                    pane.alignmentX = Component.LEFT_ALIGNMENT
                    var source = seg.content
                    appendToLast = { icon ->
                        source = source.trimEnd() + "  " + icon
                        pane.text = MarkdownRenderer.toHtml(source)
                    }
                    panel.add(pane)
                    panel.add(Box.createVerticalStrut(4))
                }

                is MessageSegment.Code -> {
                    val block = collapsibleCodeBlock(seg.lang, seg.code)
                    block.alignmentX = Component.LEFT_ALIGNMENT
                    panel.add(block)
                    panel.add(Box.createVerticalStrut(6))
                }
            }
        }
        return panel
    }

    private fun collapsibleCodeBlock(lang: String, code: String, startCollapsed: Boolean = false): JPanel {
        val editorBg = EditorColorsManager.getInstance().globalScheme.defaultBackground
        val borderColor = JBColor(Color(0x3A3A3A), Color(0x4A4A4A))
        val lineCount = code.lines().size
        val langLabel = if (lang.isNotBlank()) lang else "code"

        val fileType: FileType = when (lang.lowercase()) {
            "kotlin", "kt" -> FileTypeManager.getInstance().getFileTypeByExtension("kt")
            "java" -> FileTypeManager.getInstance().getFileTypeByExtension("java")
            "json" -> FileTypeManager.getInstance().getFileTypeByExtension("json")
            "xml" -> FileTypeManager.getInstance().getFileTypeByExtension("xml")
            "yaml", "yml" -> FileTypeManager.getInstance().getFileTypeByExtension("yaml")
            "gradle", "kts" -> FileTypeManager.getInstance().getFileTypeByExtension("kts")
            "sql" -> FileTypeManager.getInstance().getFileTypeByExtension("sql")
            "bash", "sh" -> FileTypeManager.getInstance().getFileTypeByExtension("sh")
            else -> PlainTextFileType.INSTANCE
        }.let { ft ->
            if (ft.name == "UNKNOWN" || ft == FileTypeManager.getInstance()
                    .getFileTypeByExtension("__x")
            ) PlainTextFileType.INSTANCE else ft
        }

        val document = com.intellij.openapi.editor.EditorFactory.getInstance().createDocument(code)
        val editor = EditorTextField(document, project, fileType, true, false).apply {
            background = editorBg
            border = JBUI.Borders.empty(6, 8)
            maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
            alignmentX = Component.LEFT_ALIGNMENT
        }

        var expanded = !startCollapsed
        editor.isVisible = expanded
        val toggleBtn = JButton().apply {
            text = (if (expanded) "\u25BC" else "\u25BA") + " $langLabel  ($lineCount lines)"
            font = Font(Font.MONOSPACED, Font.PLAIN, 10)
            foreground = JBColor(Color(0xAAAAAA), Color(0xAAAAAA))
            background = JBColor(Color(0x2D2D2D), Color(0x2D2D2D))
            isOpaque = true
            isFocusPainted = false
            isContentAreaFilled = true
            isBorderPainted = false
            horizontalAlignment = SwingConstants.LEFT
            border = JBUI.Borders.empty(3, 8, 3, 8)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        }

        val container = JPanel(BorderLayout()).apply {
            background = editorBg
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(borderColor, 1),
                JBUI.Borders.empty(0)
            )
            maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
            add(toggleBtn, BorderLayout.NORTH)
            add(editor, BorderLayout.CENTER)
        }

        toggleBtn.addActionListener {
            expanded = !expanded
            editor.isVisible = expanded
            toggleBtn.text =
                if (expanded) "\u25BC $langLabel  ($lineCount lines)" else "\u25BA $langLabel  ($lineCount lines)"
            container.revalidate(); container.repaint()
            messagesPanel.revalidate(); messagesPanel.repaint()
        }

        return container
    }

    @Suppress("unused")
    private fun codeBlock(lang: String, code: String): JPanel = collapsibleCodeBlock(lang, code)

    private fun bubble(bg: Color, accent: Color) = JPanel(BorderLayout(0, 3)).apply {
        background = bg
        border = JBUI.Borders.compound(
            JBUI.Borders.customLine(accent, 0, 3, 0, 0),
            JBUI.Borders.empty(6, 10, 6, 8)
        )
        maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
    }

    private fun roleLabel(text: String, color: Color) = JBLabel(text).apply {
        font = font.deriveFont(Font.BOLD, 10f); foreground = color
        border = JBUI.Borders.empty(0, 0, 3, 0)
    }

    private fun contentArea(text: String, bg: Color) = JBTextArea(text).apply {
        isEditable = false; lineWrap = true; wrapStyleWord = true
        font = JBFont.label(); background = bg; border = null
    }

    private fun systemBubble(text: String) = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
        background = JBColor.background()
        maximumSize = Dimension(Int.MAX_VALUE, 22)
        add(JBLabel(text).apply {
            font = font.deriveFont(Font.ITALIC, 10f)
            foreground = JBColor(Color(0x888888), Color(0x666666))
        })
    }

    // ==================== Command block ====================

    /**
     * Renders an interactive shell-command block: command line, LLM reason, soft
     * warnings, and Run/Decline controls. Returns a [CommandBlockView] handle the
     * controller uses to drive the block through running → result / declined.
     *
     * Decline opens an input dialog for an optional comment (forwarded to the LLM);
     * Cancel in that dialog keeps the block active.
     */
    fun addCommandBubble(
        command: String,
        reason: String?,
        warnings: List<String>,
        onRun: () -> Unit,
        onDecline: (String?) -> Unit
    ): CommandBlockView {
        val bg = JBColor(Color(0xFDF3E3), Color(0x2B2214))
        val accent = JBColor(Color(0xD68910), Color(0xF5B041))

        val body = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = bg
            alignmentX = Component.LEFT_ALIGNMENT
        }
        body.add(JBTextArea(command).apply {
            isEditable = false; lineWrap = true; wrapStyleWord = false
            font = Font(Font.MONOSPACED, Font.PLAIN, 12)
            background = bg; border = JBUI.Borders.empty(2, 0, 4, 0)
            alignmentX = Component.LEFT_ALIGNMENT
        })
        if (!reason.isNullOrBlank()) {
            body.add(JBTextArea(reason).apply {
                isEditable = false; lineWrap = true; wrapStyleWord = true
                font = JBFont.label().deriveFont(Font.ITALIC)
                foreground = JBColor(Color(0x6E5B3C), Color(0xC9A96A))
                background = bg; border = JBUI.Borders.empty(0, 0, 4, 0)
                alignmentX = Component.LEFT_ALIGNMENT
            })
        }
        warnings.forEach { w ->
            body.add(JBLabel("\u26A0 $w").apply {
                font = font.deriveFont(Font.BOLD, 10f)
                foreground = JBColor(Color(0xB9770E), Color(0xF8C471))
                alignmentX = Component.LEFT_ALIGNMENT
                border = JBUI.Borders.empty(1, 0)
            })
        }

        val controls = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            background = bg; alignmentX = Component.LEFT_ALIGNMENT
        }
        val statusRow = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = bg; alignmentX = Component.LEFT_ALIGNMENT
        }
        val runBtn = JButton("\u25B6 Run")
        val declineBtn = JButton("\u2716 Decline")
        controls.add(runBtn); controls.add(declineBtn)
        runBtn.addActionListener {
            runBtn.isEnabled = false; declineBtn.isEnabled = false
            onRun()
        }
        declineBtn.addActionListener {
            val comment = Messages.showInputDialog(
                project, "Optional comment for the LLM:", "Decline Command", null
            ) // null = Cancel — the block stays active
            if (comment != null) {
                runBtn.isEnabled = false; declineBtn.isEnabled = false
                onDecline(comment.ifBlank { null })
            }
        }

        addComp(bubble(bg, accent).also { p ->
            p.add(roleLabel("\u26A1 Terminal command", accent), BorderLayout.NORTH)
            p.add(body, BorderLayout.CENTER)
            p.add(JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS); background = bg
                add(controls); add(statusRow)
            }, BorderLayout.SOUTH)
        })

        fun refresh() {
            messagesPanel.revalidate(); messagesPanel.repaint()
        }

        fun statusLabel(text: String, color: Color) = JBLabel(text).apply {
            font = font.deriveFont(Font.BOLD, 11f); foreground = color
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.empty(3, 0, 2, 0)
        }

        return object : CommandBlockView {
            override fun setRunning() = SwingUtilities.invokeLater {
                controls.isVisible = false
                statusRow.removeAll()
                statusRow.add(statusLabel("\u23F3 Running\u2026", JBColor(Color(0x7D6608), Color(0xF7DC6F))))
                refresh()
            }

            override fun setQueued() = SwingUtilities.invokeLater {
                controls.isVisible = false
                statusRow.removeAll()
                statusRow.add(statusLabel("\u23F8 Queued\u2026", JBColor(Color(0x7D6608), Color(0xF7DC6F))))
                refresh()
            }

            override fun setResult(headline: String, output: String, ok: Boolean) = SwingUtilities.invokeLater {
                controls.isVisible = false
                statusRow.removeAll()
                val color = if (ok) JBColor(Color(0x1E8449), Color(0x58D68D))
                else JBColor(Color(0xC0392B), Color(0xEC7063))
                statusRow.add(statusLabel(headline, color))
                if (output.isNotBlank()) {
                    statusRow.add(
                        collapsibleCodeBlock("output", output, startCollapsed = output.lines().size > 25)
                            .also { it.alignmentX = Component.LEFT_ALIGNMENT }
                    )
                }
                refresh()
            }

            override fun setDeclined(comment: String?) = SwingUtilities.invokeLater {
                controls.isVisible = false
                statusRow.removeAll()
                val text = "\u2716 Declined" + (comment?.let { " \u2014 $it" } ?: "")
                statusRow.add(statusLabel(text, JBColor(Color(0x922B21), Color(0xD98880))))
                refresh()
            }
        }
    }

    /**
     * Renders an interactive question block: bold question text, one button per option
     * (vertical stack) and a free-form answer field with its own send button. Any choice
     * locks the block and reports the answer via [onAnswer]; the returned handle lets
     * the controller freeze it externally ([QuestionBlockView.setDismissed]).
     */
    fun addQuestionBubble(
        question: String,
        options: List<String>,
        onAnswer: (String) -> Unit
    ): QuestionBlockView {
        val bg = JBColor(Color(0xEAF2F8), Color(0x16222E))
        val accent = JBColor(Color(0x2E86C1), Color(0x5DADE2))

        val body = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = bg
            alignmentX = Component.LEFT_ALIGNMENT
        }
        body.add(JBTextArea(question).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            font = JBFont.label().deriveFont(Font.BOLD)
            background = bg
            border = JBUI.Borders.empty(2, 0, 6, 0)
            alignmentX = Component.LEFT_ALIGNMENT
        })

        val optionButtons = mutableListOf<JButton>()
        val customField = com.intellij.ui.components.JBTextField()
        val customSend = JButton("\u21A9 Answer")

        fun lockAll() {
            optionButtons.forEach { it.isEnabled = false }
            customField.isEnabled = false
            customSend.isEnabled = false
        }

        val controls = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = bg
            alignmentX = Component.LEFT_ALIGNMENT
        }
        options.forEach { option ->
            val button = JButton(option).apply {
                alignmentX = Component.LEFT_ALIGNMENT
            }
            button.addActionListener {
                lockAll()
                onAnswer(option)
            }
            optionButtons.add(button)
            controls.add(button)
            controls.add(javax.swing.Box.createVerticalStrut(4))
        }

        val submitCustom = {
            val text = customField.text.trim()
            if (text.isNotBlank()) {
                lockAll()
                onAnswer(text)
            }
        }
        customSend.addActionListener { submitCustom() }
        customField.addActionListener { submitCustom() }

        val customRow = JPanel(BorderLayout(6, 0)).apply {
            background = bg
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.empty(4, 0, 0, 0)
            add(customField, BorderLayout.CENTER)
            add(customSend, BorderLayout.EAST)
        }
        customRow.maximumSize = java.awt.Dimension(Int.MAX_VALUE, customRow.preferredSize.height)

        val statusRow = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = bg
            alignmentX = Component.LEFT_ALIGNMENT
        }

        addComp(bubble(bg, accent).also { panel ->
            panel.add(roleLabel("\u2753 Question", accent), BorderLayout.NORTH)
            panel.add(body, BorderLayout.CENTER)
            panel.add(JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                background = bg
                add(controls)
                add(customRow)
                add(statusRow)
            }, BorderLayout.SOUTH)
        })

        fun refresh() {
            messagesPanel.revalidate()
            messagesPanel.repaint()
        }

        fun statusLabel(text: String, color: Color) = JBLabel(text).apply {
            font = font.deriveFont(Font.BOLD, 11f)
            foreground = color
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.empty(3, 0, 2, 0)
        }

        return object : QuestionBlockView {
            override fun setAnswered(answer: String) = SwingUtilities.invokeLater {
                controls.isVisible = false
                customRow.isVisible = false
                statusRow.removeAll()
                statusRow.add(
                    statusLabel("\u2713 $answer", JBColor(Color(0x1E8449), Color(0x58D68D)))
                )
                refresh()
            }

            override fun setDismissed() = SwingUtilities.invokeLater {
                controls.isVisible = false
                customRow.isVisible = false
                statusRow.removeAll()
                statusRow.add(
                    statusLabel("\u2013 answered in chat", JBColor(Color(0x707B7C), Color(0x99A3A4)))
                )
                refresh()
            }
        }
    }

    /** Renders a Run all / Decline all control bar for a multi-command batch. */
    fun addCommandBatchBar(count: Int, onRunAll: () -> Unit, onDeclineAll: () -> Unit): CommandBatchBarView {
        val bg = JBColor(Color(0xFDF3E3), Color(0x2B2214))
        val accent = JBColor(Color(0xD68910), Color(0xF5B041))
        val runAllBtn = JButton("\u25B6\u25B6 Run all ($count)")
        val declineAllBtn = JButton("\u2716 Decline all")
        val row = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            background = bg
            add(runAllBtn); add(declineAllBtn)
        }
        runAllBtn.addActionListener { runAllBtn.isEnabled = false; declineAllBtn.isEnabled = false; onRunAll() }
        declineAllBtn.addActionListener { runAllBtn.isEnabled = false; declineAllBtn.isEnabled = false; onDeclineAll() }
        addComp(bubble(bg, accent).also { p ->
            p.add(roleLabel("\u26A1 Command batch", accent), BorderLayout.NORTH)
            p.add(row, BorderLayout.CENTER)
        })
        return object : CommandBatchBarView {
            override fun dismiss() = SwingUtilities.invokeLater {
                runAllBtn.isEnabled = false
                declineAllBtn.isEnabled = false
            }
        }
    }

    /** Post-apply errors: summary, collapsible details, Send to model / Dismiss. */
    fun addPostApplyErrorsBubble(
        summary: String,
        details: String,
        onSend: () -> Unit,
        onDismiss: () -> Unit
    ): PostApplyErrorsView {
        val bg = JBColor(Color(0xFDEDEC), Color(0x2B1A18))
        val accent = JBColor(Color(0xC0392B), Color(0xEC7063))

        val body = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = bg
            alignmentX = Component.LEFT_ALIGNMENT
        }
        body.add(
            collapsibleCodeBlock("errors", details, startCollapsed = details.lines().size > 15)
                .also { it.alignmentX = Component.LEFT_ALIGNMENT }
        )

        val sendBtn = JButton("\u27A4 Send to model")
        val dismissBtn = JButton("\u2716 Dismiss")
        val controls = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            background = bg; alignmentX = Component.LEFT_ALIGNMENT
            add(sendBtn); add(dismissBtn)
        }
        val statusRow = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = bg; alignmentX = Component.LEFT_ALIGNMENT
        }
        sendBtn.addActionListener { sendBtn.isEnabled = false; dismissBtn.isEnabled = false; onSend() }
        dismissBtn.addActionListener { sendBtn.isEnabled = false; dismissBtn.isEnabled = false; onDismiss() }

        addComp(bubble(bg, accent).also { p ->
            p.add(roleLabel("\u26D4 $summary", accent), BorderLayout.NORTH)
            p.add(body, BorderLayout.CENTER)
            p.add(JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS); background = bg
                add(controls); add(statusRow)
            }, BorderLayout.SOUTH)
        })

        fun refresh() { messagesPanel.revalidate(); messagesPanel.repaint() }
        fun statusLabel(text: String, color: Color) = JBLabel(text).apply {
            font = font.deriveFont(Font.BOLD, 11f); foreground = color
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.empty(3, 0, 2, 0)
        }
        return object : PostApplyErrorsView {
            override fun setSent() = SwingUtilities.invokeLater {
                controls.isVisible = false
                statusRow.add(statusLabel("\u27A4 Sent to model", JBColor(Color(0x1E8449), Color(0x58D68D))))
                refresh()
            }
            override fun setDismissed() = SwingUtilities.invokeLater {
                controls.isVisible = false
                statusRow.add(statusLabel("\u2716 Dismissed", JBColor(Color(0x922B21), Color(0xD98880))))
                refresh()
            }
        }
    }

    // ==================== Footer ====================

    private fun collapsibleFooter(
        tokenInfo: String?,
        ok: List<ModificationResult.Success>,
        fail: List<ModificationResult.Failure>,
        bg: Color,
        metaFiles: List<String> = emptyList(),
        requestedViews: List<RequestedViewInfo> = emptyList(),
        appliedModifications: List<AppliedModInfo> = emptyList()
    ): JPanel {
        val summaryHtml =
            buildSummaryHtml("&#9658;", tokenInfo, ok, fail, metaFiles, requestedViews, appliedModifications)
        val expandedHtml =
            buildSummaryHtml("&#9660;", tokenInfo, ok, fail, metaFiles, requestedViews, appliedModifications)
        val details = detailsPanel(
            tokenInfo,
            ok,
            fail,
            bg,
            metaFiles,
            requestedViews,
            appliedModifications
        ).also { it.isVisible = false }
        val btn = JButton(summaryHtml).apply {
            font = font.deriveFont(Font.PLAIN, 12f)
            isFocusPainted = false; isContentAreaFilled = false; isBorderPainted = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            horizontalAlignment = SwingConstants.LEFT
            border = JBUI.Borders.empty(5, 0, 2, 0)
        }
        btn.addActionListener {
            details.isVisible = !details.isVisible
            btn.text = if (details.isVisible) expandedHtml else summaryHtml
            messagesPanel.revalidate(); messagesPanel.repaint()
        }
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS); background = bg
            border = JBUI.Borders.empty(4, 0, 0, 0)
            add(btn.also { it.alignmentX = Component.LEFT_ALIGNMENT })
            add(details.also { it.alignmentX = Component.LEFT_ALIGNMENT })
        }
    }

    /**
     * Collapsible reasoning sub-panel ("\uD83D\uDCAD Reasoning") shown between the message body
     * and the file/modification footer of an assistant bubble.
     *
     * Kept separate from [collapsibleFooter] because full Claude Code thinking can run to
     * thousands of characters — mixed into the footer it drowned the file/modification
     * breakdown. Collapsed by default: a long chain of thought costs one header line of
     * vertical space until the user expands it.
     *
     * Header is deliberately PLAIN TEXT, not HTML: Swing's HTML renderer may wrap button
     * text at regular spaces and miscomputes preferred width for non-BMP emoji glyphs
     * (font fallback), which produced a broken two-line header. Plain text never wraps.
     */
    private fun collapsibleReasoningPanel(reasoning: String, bg: Color): JPanel {
        val lineCount = reasoning.lines().size
        val headerCollapsed = "\u25BA  \uD83D\uDCAD Reasoning ($lineCount lines)"
        val headerExpanded = "\u25BC  \uD83D\uDCAD Reasoning ($lineCount lines)"

        val body = contentArea(reasoning, bg).apply {
            font = font.deriveFont(11f)
            foreground = JBColor(Color(0x444444), Color(0xAAAAAA))
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.empty(0, 10, 6, 0)
            isVisible = false
        }

        val btn = JButton(headerCollapsed).apply {
            font = font.deriveFont(Font.PLAIN, 12f)
            foreground = JBColor(Color(0x7D3C98), Color(0xBB8FCE))
            isFocusPainted = false; isContentAreaFilled = false; isBorderPainted = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            horizontalAlignment = SwingConstants.LEFT
            border = JBUI.Borders.empty(4, 0, 2, 0)
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        }
        btn.addActionListener {
            body.isVisible = !body.isVisible
            btn.text = if (body.isVisible) headerExpanded else headerCollapsed
            messagesPanel.revalidate(); messagesPanel.repaint()
        }

        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS); background = bg
            border = JBUI.Borders.empty(2, 0, 0, 0)
            add(btn.also { it.alignmentX = Component.LEFT_ALIGNMENT })
            add(body.also { it.alignmentX = Component.LEFT_ALIGNMENT })
        }
    }

    private fun buildSummaryHtml(
        arrow: String,
        tokenInfo: String?,
        ok: List<ModificationResult.Success>,
        fail: List<ModificationResult.Failure>,
        metaFiles: List<String>,
        requestedViews: List<RequestedViewInfo> = emptyList(),
        appliedModifications: List<AppliedModInfo> = emptyList()
    ): String {
        val parts = mutableListOf<String>()
        if (!tokenInfo.isNullOrBlank()) parts += "<font color='#D4821A'>&#128290; $tokenInfo</font>"

        // requestedViews breakdown (new) or legacy metaFiles
        if (requestedViews.isNotEmpty()) {
            val full = requestedViews.count { it.granularity == CodeGranularity.FULL }
            val sigs = requestedViews.count {
                it.granularity == CodeGranularity.SIGNATURES || it.granularity == CodeGranularity.OUTLINE
            }
            val elem = requestedViews.count { it.granularity == CodeGranularity.ELEMENT }
            val skills = requestedViews.count { it.granularity == CodeGranularity.SKILL }
            val usages = requestedViews.count { it.granularity == CodeGranularity.USAGES }
            val callers = requestedViews.count { it.granularity == CodeGranularity.CALLERS }
            val callees = requestedViews.count { it.granularity == CodeGranularity.CALLEES }
            val segments = buildList {
                if (full > 0) add("<font color='#2980B9'>$full full</font>")
                if (sigs > 0) add("<font color='#D4AC0D'>$sigs sig</font>")
                if (elem > 0) add("<font color='#27AE60'>$elem elem</font>")
                if (skills > 0) add("<font color='#8E44AD'>$skills skill</font>")
                if (usages > 0) add("<font color='#CA6F1E'>$usages usages</font>")
                if (callers > 0) add("<font color='#148F77'>$callers callers</font>")
                if (callees > 0) add("<font color='#1F618D'>$callees callees</font>")
            }
            parts += "&#128193; ${segments.joinToString(" &middot; ")}"
        } else if (metaFiles.isNotEmpty()) {
            parts += "<font color='#888888'>&#128193; ${metaFiles.size} files</font>"
        }

        // appliedModifications breakdown (new) or legacy ok/fail
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
            if (ok.isNotEmpty()) parts += "<font color='#1E8449'>&#9989; ${ok.size}</font>"
            if (fail.isNotEmpty()) parts += "<font color='#C0392B'>&#10060; ${fail.size}</font>"
        }

        val body = parts.joinToString("  &middot;  ")
        return "<html><font color='#7D3C98'>$arrow</font>&nbsp;&nbsp;$body</html>"
    }

    private fun detailsPanel(
        tokenInfo: String?,
        ok: List<ModificationResult.Success>,
        fail: List<ModificationResult.Failure>,
        bg: Color,
        metaFiles: List<String> = emptyList(),
        requestedViews: List<RequestedViewInfo> = emptyList(),
        appliedModifications: List<AppliedModInfo> = emptyList()
    ) = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS); background = bg
        border = JBUI.Borders.empty(2, 8, 2, 0)
        maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)

        if (!tokenInfo.isNullOrBlank()) {
            add(JBLabel(tokenInfo).apply {
                font = font.deriveFont(9f); foreground = JBColor.GRAY
                alignmentX = Component.LEFT_ALIGNMENT
                border = JBUI.Borders.empty(0, 0, 4, 0)
            })
        }

        // requestedViews (new) or legacy metaFiles
        if (requestedViews.isNotEmpty()) {
            add(sectionLabel("\uD83D\uDCC1 Requested:"))
            requestedViews.forEach { view ->
                val (color, lightColor, label) = when (view.granularity) {
                    CodeGranularity.FULL -> Triple(Color(0x1A5276), Color(0x5DADE2), "[full]")
                    CodeGranularity.SIGNATURES -> Triple(Color(0x9A7D0A), Color(0xF4D03F), "[sig]")
                    CodeGranularity.OUTLINE -> Triple(Color(0x9A7D0A), Color(0xF4D03F), "[outline]")
                    CodeGranularity.ELEMENT -> Triple(Color(0x1E8449), Color(0x58D68D), "[elem]")
                    CodeGranularity.USAGES -> Triple(Color(0xAF601A), Color(0xEB984E), "[usages]")
                    CodeGranularity.CALLERS -> Triple(Color(0x117A65), Color(0x76D7C4), "[callers]")
                    CodeGranularity.CALLEES -> Triple(Color(0x1F618D), Color(0x7FB3D5), "[callees]")
                    CodeGranularity.SKILL -> Triple(Color(0x6C3483), Color(0xBB8FCE), "[skill]")
                }
                val displayText = if (view.elementPath != null)
                    "  \u2022 ${view.path} / ${view.elementPath}  $label"
                else
                    "  \u2022 ${view.path}  $label"
                val navigateTo = if (view.elementPath != null)
                    "file:${view.path}/${view.elementPath}"
                else
                    view.path
                add(clickableLabel(displayText, color, lightColor) { onNavigateToPath(navigateTo) })
            }
        } else if (metaFiles.isNotEmpty()) {
            add(sectionLabel("\uD83D\uDCC1 Gathered files:"))
            metaFiles.forEach { name ->
                add(JBLabel("   \u2022 $name").apply {
                    font = Font(Font.MONOSPACED, Font.PLAIN, 10)
                    foreground = JBColor(Color(0x888888), Color(0x666666))
                    alignmentX = Component.LEFT_ALIGNMENT
                })
            }
        }

// appliedModifications (new) or legacy ok/fail
        if (appliedModifications.isNotEmpty()) {
            add(sectionLabel("\u2705 Applied modifications:"))
            appliedModifications.forEach { mod ->
                val (color, lightColor) = when (mod.category) {
                    ModificationCategory.FILE_LEVEL -> Pair(Color(0x1A5276), Color(0x5DADE2))
                    ModificationCategory.ELEMENT_LEVEL -> Pair(Color(0x1E8449), Color(0x58D68D))
                    ModificationCategory.IMPORT -> Pair(Color(0x9A7D0A), Color(0xF4D03F))
                }
                val displayText = "  \u2022 ${ChatNavigationHelper.formatElementPath(mod.path)}"
                add(clickableLabel(displayText, color, lightColor) { onNavigateToPath(mod.path) })
            }
        } else {
            if (ok.isNotEmpty()) {
                add(sectionLabel("\u2705 Applied modifications:"))
                ok.forEach { mod ->
                    val pathStr = mod.affectedPath.toString()
                    add(clickableLabel(
                        "  \u2022 ${ChatNavigationHelper.formatElementPath(mod.affectedPath)}",
                        Color(0x2471A3), Color(0x7FB3D3)
                    ) { onNavigateToPath(pathStr) })
                }
            }
            if (fail.isNotEmpty()) {
                add(sectionLabel("\u274C Failed modifications:"))
                fail.forEach { mod ->
                    add(JBLabel("  \u2717 ${mod.error.message}").apply {
                        font = font.deriveFont(10f)
                        foreground = JBColor(Color(0xC0392B), Color(0xEC7063))
                        alignmentX = Component.LEFT_ALIGNMENT
                    })
                }
            }
        }
    }

    // ==================== Helpers ====================

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

    /**
     * Adds a small "Схема" button block under the last message bubble.
     * Rendered only for assistant turns whose response carried a plan diagram;
     * clicking invokes [onOpen] (the panel knows nothing about the diagram itself).
     */
    fun addDiagramButton(onOpen: () -> Unit) {
        val panel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            background = JBColor.background()
            border = JBUI.Borders.empty(0, 14, 6, 0)
        }
        panel.add(JButton("\uD83D\uDDFA Схема").apply {
            font = font.deriveFont(11f)
            isFocusPainted = false
            toolTipText = "Открыть схему плана в отдельном окне"
            addActionListener { onOpen() }
        })
        addComp(panel)
    }
}
