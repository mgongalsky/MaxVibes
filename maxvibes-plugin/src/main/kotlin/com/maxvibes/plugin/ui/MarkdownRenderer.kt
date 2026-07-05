package com.maxvibes.plugin.ui

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.util.ui.HTMLEditorKitBuilder
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import java.awt.Color
import javax.swing.JEditorPane
import javax.swing.event.HyperlinkEvent

/**
 * Renders prose (non-code) segments of chat messages as styled HTML.
 *
 * Fenced code blocks never reach this renderer — [ConversationPanel.parseSegments]
 * routes them to EditorTextField with real syntax highlighting. This object only
 * handles headings, paragraphs, lists, inline code, links, tables etc.
 *
 * Design notes:
 * - escapeHtml(true): raw HTML coming from the LLM is shown as text, not interpreted.
 *   Predictability over cleverness.
 * - softbreak("<br/>"): a single newline in the source becomes a visible line break,
 *   matching how the plain-text chat behaved before.
 * - Word-wrap view factory: long unbreakable tokens (file paths, element paths)
 *   wrap instead of blowing up the bubble width — the conversation scroll pane
 *   never shows a horizontal scrollbar.
 * - Colors go through JBColor/ColorUtil so light and dark themes both work; the
 *   CSS is rebuilt per pane, so new bubbles pick up a theme switch.
 */
object MarkdownRenderer {

    private val extensions = listOf(TablesExtension.create())
    private val parser = Parser.builder().extensions(extensions).build()
    private val renderer = HtmlRenderer.builder()
        .extensions(extensions)
        .escapeHtml(true)
        .softbreak("<br/>")
        .build()

    fun toHtml(markdown: String): String {
        val body = renderer.render(parser.parse(markdown))
        return "<html><head><style>${css()}</style></head><body>$body</body></html>"
    }

    /**
     * Creates a non-editable, transparent HTML pane for one prose segment.
     *
     * @param onLink receives non-http hrefs (relative file paths or `file:` element
     *   paths) — wire it to the same navigation used by footer links. http/https
     *   links open in the external browser.
     */
    fun createPane(markdown: String, onLink: (String) -> Unit): JEditorPane =
        JEditorPane().apply {
            editorKit = HTMLEditorKitBuilder().withWordWrapViewFactory().build()
            isEditable = false
            isOpaque = false
            putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
            font = JBFont.label()
            text = toHtml(markdown)
            caretPosition = 0
            addHyperlinkListener { e ->
                if (e.eventType == HyperlinkEvent.EventType.ACTIVATED) {
                    val href = e.description.orEmpty()
                    if (href.startsWith("http://") || href.startsWith("https://")) {
                        BrowserUtil.browse(href)
                    } else {
                        onLink(href)
                    }
                }
            }
        }

    private fun css(): String {
        val base = JBFont.label().size
        val editorFont = EditorColorsManager.getInstance().globalScheme.editorFontName
        val codeBg = ColorUtil.toHtmlColor(JBColor(Color(0xE8E8E8), Color(0x2B2D30)))
        val linkColor = ColorUtil.toHtmlColor(JBUI.CurrentTheme.Link.Foreground.ENABLED)
        val borderColor = ColorUtil.toHtmlColor(JBColor(Color(0xCCCCCC), Color(0x4A4A4A)))
        val quoteColor = ColorUtil.toHtmlColor(JBColor(Color(0x666666), Color(0x999999)))
        return """
            body { font-size: ${base}pt; }
            p { margin: 4px 0 6px 0; }
            h1 { font-size: ${base + 5}pt; margin: 10px 0 4px 0; }
            h2 { font-size: ${base + 3}pt; margin: 9px 0 4px 0; }
            h3 { font-size: ${base + 1}pt; margin: 8px 0 3px 0; }
            h4, h5, h6 { font-size: ${base}pt; margin: 8px 0 3px 0; }
            ul, ol { margin: 4px 0 6px 16px; }
            li { margin: 2px 0; }
            code { font-family: "$editorFont"; font-size: ${base - 1}pt; background-color: $codeBg; }
            pre { font-family: "$editorFont"; font-size: ${base - 1}pt; background-color: $codeBg; margin: 6px 0; }
            a { color: $linkColor; }
            blockquote { margin: 4px 0 4px 8px; color: $quoteColor; }
            table { border-collapse: collapse; margin: 6px 0; }
            th, td { border: 1px solid $borderColor; padding: 3px 8px; }
            hr { border: 1px solid $borderColor; }
        """.trimIndent()
    }
}