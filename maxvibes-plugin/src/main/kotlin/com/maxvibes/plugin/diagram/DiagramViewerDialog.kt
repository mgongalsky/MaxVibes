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
import com.intellij.ui.JBColor

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
     *
     * Readability: `useMaxWidth: false` renders the SVG at natural size (the default
     * squeezes a wide graph into the window width, making labels unreadable) — the page
     * scrolls instead. A zoom bar (−/+/1:1) plus Ctrl+wheel scales the diagram 0.2x–8x.
     * Mermaid theme and page background follow the IDE theme via [JBColor.isBright].
     */
    private fun buildHtml(mermaidSource: String, js: String): String {
        val dark = !JBColor.isBright()
        val bg = if (dark) "#2b2b2b" else "#ffffff"
        val fg = if (dark) "#bbbbbb" else "#000000"
        val theme = if (dark) "dark" else "default"
        val escaped = mermaidSource
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
        return """
        <!DOCTYPE html>
        <html><head><meta charset="utf-8">
        <style>
          body { margin: 0; padding: 8px; background: $bg; color: $fg; }
          #zoombar { position: fixed; top: 8px; right: 16px; z-index: 10; user-select: none; }
          #zoombar button { font-size: 15px; width: 36px; height: 30px; margin-left: 4px; }
          #wrap { transform-origin: 0 0; width: max-content; }
          #err { color: #e05555; font-family: monospace; white-space: pre-wrap; }
        </style>
        </head><body>
        <div id="zoombar">
          <button onclick="zoomBy(0.8)" title="Zoom out (Ctrl+wheel)">&minus;</button>
          <button onclick="zoomBy(1.25)" title="Zoom in (Ctrl+wheel)">+</button>
          <button onclick="zoomReset()" title="Reset zoom">1:1</button>
        </div>
        <div id="wrap"><pre class="mermaid">$escaped</pre></div>
        <div id="err"></div>
        <script>$js</script>
        <script>
          var scale = 1;
          function applyZoom() { document.getElementById('wrap').style.transform = 'scale(' + scale + ')'; }
          function zoomBy(f) { scale = Math.min(8, Math.max(0.2, scale * f)); applyZoom(); }
          function zoomReset() { scale = 1; applyZoom(); }
          window.addEventListener('wheel', function (e) {
            if (!e.ctrlKey) return;
            e.preventDefault();
            zoomBy(e.deltaY < 0 ? 1.15 : 0.87);
          }, { passive: false });
          try {
            mermaid.initialize({
              startOnLoad: true,
              securityLevel: 'strict',
              theme: '$theme',
              flowchart: { useMaxWidth: false },
              themeVariables: { fontSize: '16px' }
            });
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
