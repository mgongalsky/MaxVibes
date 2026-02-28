package com.maxvibes.plugin.ui

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.ui.EditorTextField
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.maxvibes.domain.model.modification.ModificationResult
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

sealed class MessageSegment {
    data class Text(val content: String) : MessageSegment()
    data class Code(val lang: String, val code: String) : MessageSegment()
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

    private var lastContentArea: JBTextArea? = null

    init {
        background = JBColor.background()
        add(scrollPane, BorderLayout.CENTER)
    }

    fun clearMessages() {
        messagesPanel.removeAll(); lastContentArea = null
        messagesPanel.revalidate(); messagesPanel.repaint()
    }

    fun addUserBubble(text: String) = addComp(userBubble(text))

    fun addAssistantBubble(
        text: String,
        tokenInfo: String? = null,
        modifications: List<ModificationResult> = emptyList(),
        metaFiles: List<String> = emptyList(),
        reasoning: String? = null
    ) = addComp(assistantBubble(text, tokenInfo, modifications, metaFiles, reasoning))

    fun addSystemBubble(text: String) {
        if (text.isNotBlank()) addComp(systemBubble(text))
    }

    fun appendIconToLastBubble(icon: String) {
        val area = lastContentArea
        SwingUtilities.invokeLater {
            if (area != null) {
                val current = area.text.trimEnd()
                area.text = "$current  $icon"
            } else {
                // Если текста нет (например, только код), добавляем системный пузырек
                addSystemBubble(icon)
            }
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
        lastContentArea = area
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
        reasoning: String? = null
    ): JPanel {
        val bg = JBColor(Color(0xEAF7EA), Color(0x1B2B1E))
        val ok = mods.filterIsInstance<ModificationResult.Success>()
        val fail = mods.filterIsInstance<ModificationResult.Failure>()
        val hasDetails =
            !tokenInfo.isNullOrBlank() || ok.isNotEmpty() || fail.isNotEmpty() || metaFiles.isNotEmpty() || !reasoning.isNullOrBlank()

        val segments = parseSegments(text)
        val contentPanel = buildSegmentsPanel(segments, bg)

        return bubble(bg, JBColor(Color(0x239B56), Color(0x58D68D))).also { p ->
            p.add(roleLabel("\uD83E\uDD16 MaxVibes", JBColor(Color(0x1D6A39), Color(0x82E0AA))), BorderLayout.NORTH)
            p.add(contentPanel, BorderLayout.CENTER)
            if (hasDetails) p.add(collapsibleFooter(tokenInfo, ok, fail, bg, metaFiles, reasoning), BorderLayout.SOUTH)
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
                    val formatted = formatTextSegment(seg.content)
                    if (formatted.isNotBlank()) {
                        val area = contentArea(formatted, bg)
                        lastContentArea = area
                        area.alignmentX = Component.LEFT_ALIGNMENT
                        panel.add(area)
                        panel.add(Box.createVerticalStrut(4))
                    }
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

    private fun formatTextSegment(text: String): String {
        return text.lines().joinToString("\n") { line ->
            var l = line
            val h3 = Regex("^###\\s+(.+)").find(l)
            if (h3 != null) return@joinToString "  \u2500\u2500\u2500 ${h3.groupValues[1].trim()} \u2500\u2500\u2500"
            val h2 = Regex("^##\\s+(.+)").find(l)
            if (h2 != null) return@joinToString "\u2550\u2550 ${h2.groupValues[1].trim().uppercase()} \u2550\u2550"
            val h1 = Regex("^#\\s+(.+)").find(l)
            if (h1 != null) return@joinToString "\u2550\u2550\u2550 ${
                h1.groupValues[1].trim().uppercase()
            } \u2550\u2550\u2550"
            if (l.trim().matches(Regex("^[-*_]{3,}$"))) return@joinToString "\u2500".repeat(40)
            l = l.replace(Regex("^(\\s*)[-*]\\s+"), "$1\u2022 ")
            l = l.replace(Regex("\\*{3}(.+?)\\*{3}")) { it.groupValues[1].uppercase() }
            l = l.replace(Regex("\\*{2}(.+?)\\*{2}")) { it.groupValues[1].uppercase() }
            l = l.replace(Regex("(?<![*])\\*([^*]+?)\\*(?![*])")) { it.groupValues[1] }
            // Убрал замену обратных кавычек на скобки, чтобы не портить вид путей и имен
            l
        }
    }

    private fun collapsibleCodeBlock(lang: String, code: String): JPanel {
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

        var expanded = true
        val toggleBtn = JButton().apply {
            text = "\u25BC $langLabel  ($lineCount lines)"
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
        font = Font(Font.MONOSPACED, Font.PLAIN, 12); background = bg; border = null
    }

    private fun systemBubble(text: String) = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
        background = JBColor.background()
        maximumSize = Dimension(Int.MAX_VALUE, 22)
        add(JBLabel(text).apply {
            font = font.deriveFont(Font.ITALIC, 10f)
            foreground = JBColor(Color(0x888888), Color(0x666666))
        })
    }

    private fun collapsibleFooter(
        tokenInfo: String?,
        ok: List<ModificationResult.Success>,
        fail: List<ModificationResult.Failure>,
        bg: Color,
        metaFiles: List<String> = emptyList(),
        reasoning: String? = null
    ): JPanel {
        val summaryHtml = buildSummaryHtml("&#9658;", tokenInfo, ok, fail, metaFiles, reasoning)
        val expandedHtml = buildSummaryHtml("&#9660;", tokenInfo, ok, fail, metaFiles, reasoning)
        val details = detailsPanel(tokenInfo, ok, fail, bg, metaFiles, reasoning).also { it.isVisible = false }
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
            SwingUtilities.invokeLater { scrollPane.verticalScrollBar.value = scrollPane.verticalScrollBar.maximum }
        }
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS); background = bg
            border = JBUI.Borders.empty(4, 0, 0, 0)
            add(btn.also { it.alignmentX = Component.LEFT_ALIGNMENT })
            add(details.also { it.alignmentX = Component.LEFT_ALIGNMENT })
        }
    }

    private fun buildSummaryHtml(
        arrow: String,
        tokenInfo: String?,
        ok: List<ModificationResult.Success>,
        fail: List<ModificationResult.Failure>,
        metaFiles: List<String>,
        reasoning: String?
    ): String {
        val parts = mutableListOf<String>()
        if (!reasoning.isNullOrBlank()) parts += "<font color='#7D3C98'>&#129504;</font>"
        if (!tokenInfo.isNullOrBlank()) parts += "<font color='#D4821A'>&#128290; $tokenInfo</font>"
        if (metaFiles.isNotEmpty()) parts += "<font color='#2980B9'>&#128193; ${metaFiles.size} files</font>"
        if (ok.isNotEmpty()) parts += "<font color='#1E8449'>&#9989; ${ok.size}</font>"
        if (fail.isNotEmpty()) parts += "<font color='#C0392B'>&#10060; ${fail.size}</font>"
        val body = parts.joinToString("  &middot;  ")
        return "<html><font color='#7D3C98'>$arrow</font>&nbsp;&nbsp;$body</html>"
    }

    private fun detailsPanel(
        tokenInfo: String?,
        ok: List<ModificationResult.Success>,
        fail: List<ModificationResult.Failure>,
        bg: Color,
        metaFiles: List<String> = emptyList(),
        reasoning: String? = null
    ) = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS); background = bg
        border = JBUI.Borders.empty(2, 8, 2, 0)
        maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)

        if (!reasoning.isNullOrBlank()) {
            add(JBLabel("\uD83E\uDDE0 Reasoning:").apply {
                font = font.deriveFont(Font.BOLD, 9f); foreground = JBColor.GRAY
                alignmentX = Component.LEFT_ALIGNMENT
                border = JBUI.Borders.empty(2, 0, 2, 0)
            })
            add(contentArea(reasoning, bg).apply {
                font = font.deriveFont(11f); foreground = JBColor(Color(0x444444), Color(0xAAAAAA))
                alignmentX = Component.LEFT_ALIGNMENT
                border = JBUI.Borders.empty(0, 10, 8, 0)
            })
        }

        if (!tokenInfo.isNullOrBlank()) {
            add(JBLabel(tokenInfo).apply {
                font = font.deriveFont(9f); foreground = JBColor.GRAY
                alignmentX = Component.LEFT_ALIGNMENT
                border = JBUI.Borders.empty(0, 0, 4, 0)
            })
        }

        if (metaFiles.isNotEmpty()) {
            add(JBLabel("\uD83D\uDCC1 Gathered files:").apply {
                font = font.deriveFont(Font.BOLD, 9f); foreground = JBColor.GRAY
                alignmentX = Component.LEFT_ALIGNMENT
                border = JBUI.Borders.empty(2, 0, 2, 0)
            })
            metaFiles.forEach { name ->
                add(JBLabel("   \u2022 $name").apply {
                    font = Font(Font.MONOSPACED, Font.PLAIN, 10)
                    foreground = JBColor(Color(0x888888), Color(0x666666))
                    alignmentX = Component.LEFT_ALIGNMENT
                })
            }
        }

        if (ok.isNotEmpty()) {
            add(JBLabel("\u2705 Applied modifications:").apply {
                font = font.deriveFont(Font.BOLD, 9f); foreground = JBColor.GRAY
                alignmentX = Component.LEFT_ALIGNMENT
                border = JBUI.Borders.empty(4, 0, 2, 0)
            })
            ok.forEach { mod ->
                val labelText = "  \u2022 ${ChatNavigationHelper.formatElementPath(mod.affectedPath)}"
                val pathStr = mod.affectedPath.toString()
                add(JBLabel(labelText).apply {
                    font = Font(Font.MONOSPACED, Font.PLAIN, 10)
                    foreground = JBColor(Color(0x2471A3), Color(0x7FB3D3))
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    alignmentX = Component.LEFT_ALIGNMENT
                    addMouseListener(object : MouseAdapter() {
                        override fun mouseClicked(e: MouseEvent) = onNavigateToPath(pathStr)
                        override fun mouseEntered(e: MouseEvent) {
                            foreground = JBColor(Color(0x1A5276), Color(0xAED6F1))
                        }

                        override fun mouseExited(e: MouseEvent) {
                            foreground = JBColor(Color(0x2471A3), Color(0x7FB3D3))
                        }
                    })
                })
            }
        }

        if (fail.isNotEmpty()) {
            add(JBLabel("\u274C Failed modifications:").apply {
                font = font.deriveFont(Font.BOLD, 9f); foreground = JBColor.GRAY
                alignmentX = Component.LEFT_ALIGNMENT
                border = JBUI.Borders.empty(4, 0, 2, 0)
            })
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
