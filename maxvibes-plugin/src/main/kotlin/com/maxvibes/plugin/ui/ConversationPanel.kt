package com.maxvibes.plugin.ui

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

/**
 * Scrollable conversation view — каждое сообщение рендерится как карточка-«пузырь».
 * Пользователь — синяя полоска, MaxVibes — зелёная.
 * В ответах MaxVibes токены и изменённые файлы скрыты под кнопку-кат.
 */
class ConversationPanel(
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

    init {
        background = JBColor.background()
        add(scrollPane, BorderLayout.CENTER)
    }

    fun clearMessages() {
        messagesPanel.removeAll(); messagesPanel.revalidate(); messagesPanel.repaint()
    }

    fun addUserBubble(text: String) = addComp(userBubble(text))

    fun addAssistantBubble(
        text: String,
        tokenInfo: String? = null,
        modifications: List<ModificationResult> = emptyList(),
        metaFiles: List<String> = emptyList()
    ) = addComp(assistantBubble(text, tokenInfo, modifications, metaFiles))

    fun addSystemBubble(text: String) {
        if (text.isNotBlank()) addComp(systemBubble(text))
    }

    private fun addComp(c: JComponent) {
        c.alignmentX = Component.LEFT_ALIGNMENT
        messagesPanel.add(c)
        messagesPanel.add(
            Box.createVerticalStrut(5).also { (it as? JComponent)?.alignmentX = Component.LEFT_ALIGNMENT })
        messagesPanel.revalidate(); messagesPanel.repaint()
        SwingUtilities.invokeLater { scrollPane.verticalScrollBar.value = scrollPane.verticalScrollBar.maximum }
    }

    // ── Bubble builders ───────────────────────────────────────────────

    private fun userBubble(text: String): JPanel {
        val bg = JBColor(Color(0xEBF5FB), Color(0x1B2A3B))
        return bubble(bg, JBColor(Color(0x2E86C1), Color(0x5DADE2))).also { p ->
            p.add(roleLabel("\uD83D\uDC64 You", JBColor(Color(0x1A5276), Color(0x85C1E9))), BorderLayout.NORTH)
            p.add(contentArea(text, bg), BorderLayout.CENTER)
        }
    }

    private fun assistantBubble(
        text: String,
        tokenInfo: String?,
        mods: List<ModificationResult>,
        metaFiles: List<String> = emptyList()
    ): JPanel {
        val bg = JBColor(Color(0xEAF7EA), Color(0x1B2B1E))
        val ok = mods.filterIsInstance<ModificationResult.Success>()
        val fail = mods.filterIsInstance<ModificationResult.Failure>()
        val hasDetails = !tokenInfo.isNullOrBlank() || ok.isNotEmpty() || fail.isNotEmpty() || metaFiles.isNotEmpty()
        return bubble(bg, JBColor(Color(0x239B56), Color(0x58D68D))).also { p ->
            p.add(roleLabel("\uD83E\uDD16 MaxVibes", JBColor(Color(0x1D6A39), Color(0x82E0AA))), BorderLayout.NORTH)
            p.add(contentArea(text, bg), BorderLayout.CENTER)
            if (hasDetails) p.add(collapsibleFooter(tokenInfo, ok, fail, bg, metaFiles), BorderLayout.SOUTH)
        }
    }

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

    // ── Collapsible footer ────────────────────────────────────────────

    private fun collapsibleFooter(
        tokenInfo: String?,
        ok: List<ModificationResult.Success>,
        fail: List<ModificationResult.Failure>,
        bg: Color,
        metaFiles: List<String> = emptyList()
    ): JPanel {
        val summaryParts = mutableListOf<String>()
        if (!tokenInfo.isNullOrBlank()) summaryParts += tokenInfo
        if (ok.isNotEmpty()) summaryParts += "\u2705 ${ok.size}"
        if (fail.isNotEmpty()) summaryParts += "\u274C ${fail.size}"
        if (metaFiles.isNotEmpty()) summaryParts += "\uD83D\uDCC1 ${metaFiles.size} files"
        val summary = summaryParts.joinToString("  \u00B7  ")

        val details = detailsPanel(tokenInfo, ok, fail, bg, metaFiles).also { it.isVisible = false }

        val btn = JButton("\u25B6 $summary").apply {
            font = font.deriveFont(9f); isFocusPainted = false
            isContentAreaFilled = false; isBorderPainted = false
            foreground = JBColor.GRAY
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            horizontalAlignment = SwingConstants.LEFT
            border = JBUI.Borders.empty(3, 0, 0, 0)
        }
        btn.addActionListener {
            details.isVisible = !details.isVisible
            btn.text = "${if (details.isVisible) "\u25BC" else "\u25B6"} $summary"
            messagesPanel.revalidate(); messagesPanel.repaint()
            SwingUtilities.invokeLater { scrollPane.verticalScrollBar.value = scrollPane.verticalScrollBar.maximum }
        }

        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS); background = bg
            add(btn.also { it.alignmentX = Component.LEFT_ALIGNMENT })
            add(details.also { it.alignmentX = Component.LEFT_ALIGNMENT })
        }
    }

    private fun detailsPanel(
        tokenInfo: String?,
        ok: List<ModificationResult.Success>,
        fail: List<ModificationResult.Failure>,
        bg: Color,
        metaFiles: List<String> = emptyList()
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

        ok.forEach { mod ->
            val labelText = "  \u2022 ${ChatNavigationHelper.formatElementPath(mod.affectedPath)}"
            val pathStr = mod.affectedPath.toString()
            add(JBLabel(labelText).apply {
                font = Font(Font.MONOSPACED, Font.PLAIN, 10)
                foreground = JBColor(Color(0x2471A3), Color(0x7FB3D3))
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                alignmentX = Component.LEFT_ALIGNMENT
                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) {
                        onNavigateToPath(pathStr)
                    }

                    override fun mouseEntered(e: MouseEvent) {
                        foreground = JBColor(Color(0x1A5276), Color(0xAED6F1))
                    }

                    override fun mouseExited(e: MouseEvent) {
                        foreground = JBColor(Color(0x2471A3), Color(0x7FB3D3))
                    }
                })
            })
        }
        fail.forEach { mod ->
            add(JBLabel("  \u2717 ${mod.error.message}").apply {
                font = font.deriveFont(10f)
                foreground = JBColor(Color(0xC0392B), Color(0xEC7063))
                alignmentX = Component.LEFT_ALIGNMENT
            })
        }
    }
}
