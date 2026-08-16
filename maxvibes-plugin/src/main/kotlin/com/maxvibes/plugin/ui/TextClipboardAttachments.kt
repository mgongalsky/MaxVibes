package com.maxvibes.plugin.ui

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.text.StringUtil
import java.awt.Component
import java.awt.Dimension
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import javax.swing.Action
import javax.swing.JComponent

internal object TextClipboardAttachments {
    const val AUTO_ATTACH_CHAR_THRESHOLD = 4_000
    const val AUTO_ATTACH_LINE_THRESHOLD = 120

    fun readText(): String? = try {
        Toolkit.getDefaultToolkit().systemClipboard
            .takeIf { it.isDataFlavorAvailable(DataFlavor.stringFlavor) }
            ?.getData(DataFlavor.stringFlavor) as? String
    } catch (_: Exception) {
        null
    }

    fun readText(transferable: Transferable): String? = try {
        transferable.takeIf { it.isDataFlavorSupported(DataFlavor.stringFlavor) }
            ?.getTransferData(DataFlavor.stringFlavor) as? String
    } catch (_: Exception) {
        null
    }

    fun shouldAutoAttach(text: String): Boolean =
        text.length >= AUTO_ATTACH_CHAR_THRESHOLD || countLines(text) >= AUTO_ATTACH_LINE_THRESHOLD

    fun countLines(text: String): Int {
        var lines = 1
        for (index in text.indices) if (text[index] == '\n') lines++
        return lines
    }

    fun showPreview(parent: Component, text: String) {
        PreviewDialog(parent, text).show()
    }

    private class PreviewDialog(parent: Component, text: String) : DialogWrapper(parent, false) {
        private val editorFactory = EditorFactory.getInstance()
        private val editor: Editor = editorFactory
            .createViewer(editorFactory.createDocument(StringUtil.convertLineSeparators(text)))
            .apply {
                settings.isLineNumbersShown = true
                settings.isUseSoftWraps = false
                settings.isAdditionalPageAtBottom = false
            }

        init {
            title = "Text attachment"
            isModal = false
            init()
        }

        override fun createCenterPanel(): JComponent =
            editor.component.apply { preferredSize = Dimension(900, 600) }

        override fun createActions(): Array<Action> = arrayOf(okAction)

        override fun dispose() {
            editorFactory.releaseEditor(editor)
            super.dispose()
        }
    }
}
