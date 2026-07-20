package com.maxvibes.plugin.diagram

import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.util.ui.JBUI
import com.maxvibes.application.port.output.DiagramRenderer
import com.maxvibes.domain.model.planning.PlanDiagram
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import javax.swing.Action
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Non-modal window that renders a [PlanDiagram] of the task plan.
 *
 * Toolbar: renderer selector (combo box — one Mermaid entry for now, the ELK/Swing
 * renderer plugs in later) and a "Copy source" button that puts the renderer output
 * on the clipboard (debugging aid and the escape hatch when rendering fails).
 *
 * Rendering: when JCEF is available AND the bundled `/diagram/mermaid.min.js`
 * resource exists, the Mermaid source is embedded into an offline HTML page and
 * loaded via [JBCefBrowser.loadHTML] (which internally defers until the browser is
 * ready). In every other case the dialog degrades to a read-only text area showing
 * the renderer source — the window never fails to open.
 *
 * Window size persists across sessions via [getDimensionServiceKey].
 */
class DiagramViewerDialog(
    project: Project,
    private val diagram: PlanDiagram
) : DialogWrapper(project, false) {

    private val renderers: List<DiagramRenderer> = listOf(MermaidDiagramRenderer())
    private val rendererCombo = ComboBox(renderers.map { it.displayName }.toTypedArray()).apply {
        toolTipText = "Diagram renderer"
    }
    private val fallbackArea = JBTextArea().apply {
        isEditable = false
        font = JBUI.Fonts.create("Monospaced", 12)
    }
    private val fallbackNote = JBLabel("").apply { foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND }
    private var browser: JBCefBrowser? = null

    /** Bundled mermaid.min.js, read once from plugin resources; null when not bundled. */
    private val mermaidJs: String? by lazy {
        javaClass.getResourceAsStream("/diagram/mermaid.min.js")
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
    }

    init {
        title = diagram.title?.takeIf { it.isNotBlank() }?.let { "Схема: $it" } ?: "Схема плана"
        init()
        renderCurrent()
    }

    override fun getDimensionServiceKey(): String = "MaxVibes.DiagramViewerDialog"

    override fun createActions(): Array<Action> = arrayOf(okAction)

    override fun createCenterPanel(): JComponent {
        val root = JPanel(BorderLayout(0, JBUI.scale(6)))
        root.preferredSize = Dimension(JBUI.scale(900), JBUI.scale(600))

        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0))
        toolbar.add(JBLabel("Renderer:"))
        toolbar.add(rendererCombo)
        toolbar.add(JButton("Copy source").apply {
            toolTipText = "Copy renderer source (mermaid text) to clipboard"
            addActionListener { copySource() }
        })
        rendererCombo.addActionListener { renderCurrent() }
        root.add(toolbar, BorderLayout.NORTH)

        val content = JPanel(BorderLayout())
        if (JBCefApp.isSupported() && mermaidJs != null) {
            val b = JBCefBrowser()
            Disposer.register(disposable, b)
            browser = b
            content.add(b.component, BorderLayout.CENTER)
        } else {
            fallbackNote.text = if (!JBCefApp.isSupported())
                "JCEF недоступен в этой IDE — показан исходник диаграммы:"
            else
                "mermaid.min.js не найден в ресурсах плагина — показан исходник диаграммы:"
            content.add(fallbackNote, BorderLayout.NORTH)
            content.add(JBScrollPane(fallbackArea), BorderLayout.CENTER)
        }
        root.add(content, BorderLayout.CENTER)
        return root
    }

    private fun currentRenderer(): DiagramRenderer =
        renderers[rendererCombo.selectedIndex.coerceIn(0, renderers.size - 1)]

    private fun renderCurrent() {
        val source = currentRenderer().render(diagram)
        val b = browser
        val js = mermaidJs
        if (b != null && js != null) {
            b.loadHTML(buildHtml(source, js))
        } else {
            fallbackArea.text = source
            fallbackArea.caretPosition = 0
        }
    }

    private fun copySource() {
        CopyPasteManager.getInstance().setContents(StringSelection(currentRenderer().render(diagram)))
    }

    /**
     * Offline HTML shell: mermaid.min.js inlined into a <script> (JCEF cannot read
     * jar: resource URLs without a custom scheme handler), diagram source HTML-escaped
     * inside <pre class="mermaid"> (Mermaid reads its textContent). Render errors are
     * surfaced into the #err div instead of a blank page.
     */
    private fun buildHtml(mermaidSource: String, js: String): String {
        val escaped = mermaidSource
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
        return """
            <!DOCTYPE html>
            <html><head><meta charset="utf-8">
            <style>
              body { margin: 0; padding: 8px; background: #ffffff; }
              #err { color: #b00020; font-family: monospace; white-space: pre-wrap; }
            </style>
            </head><body>
            <pre class="mermaid">$escaped</pre>
            <div id="err"></div>
            <script>$js</script>
            <script>
              try {
                mermaid.initialize({ startOnLoad: true, securityLevel: 'strict' });
              } catch (e) {
                document.getElementById('err').textContent = String(e);
              }
              window.addEventListener('error', function (e) {
                document.getElementById('err').textContent = String(e.message || e);
              });
            </script>
            </body></html>
        """.trimIndent()
    }
}
