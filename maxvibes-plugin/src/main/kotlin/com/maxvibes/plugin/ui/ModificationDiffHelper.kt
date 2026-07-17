package com.maxvibes.plugin.ui

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffDialogHints
import com.intellij.diff.DiffManager
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.maxvibes.adapter.psi.operation.PsiNavigator
import com.maxvibes.domain.model.code.ElementPath
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Font
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants
import com.intellij.diff.util.DiffUtil

/**
 * Opens the standard IntelliJ diff window for pending modifications
 * (ModApproval feature, Step 4).
 *
 * "Before" side:
 *  - element-level ops (REPLACE_ELEMENT / DELETE_ELEMENT / SAFE_DELETE) — current element
 *    text resolved via [PsiNavigator] under a read action (same approach as
 *    [ChatNavigationHelper]); a missing element degrades to an empty side;
 *  - file-level ops (REPLACE_FILE / DELETE_FILE) — current file text, read through the
 *    Document first so unsaved editor edits are visible;
 *  - CREATE_* — empty.
 *
 * "After" side: the proposed content; empty for DELETE_*.
 *
 * The modification's `explanation` (when present) is attached to the request via
 * [DiffUserDataKeys.NOTIFICATIONS] and rendered as a banner ABOVE the diff panes —
 * in a chain every request carries its own banner, so paging with Alt+Left/Right
 * always shows the reasoning for the change on screen.
 *
 * [showAll] builds a [SimpleDiffRequestChain] over every diffable row and opens ONE
 * window — the user pages through the changes with the diff toolbar arrows (Alt+Left /
 * Alt+Right) or the chain dropdown instead of opening a tab per modification.
 *
 * Ops with no meaningful text diff (imports, rename, move) fall back to a plain
 * info dialog in [show] and are skipped in [showAll].
 *
 * EDT-only (invoked from the approval card's buttons).
 */
object ModificationDiffHelper {

    /** Shows the diff for a single pending modification. */
    fun show(project: Project, row: PendingModRowUi) {
        val request = buildRequest(project, row)
        if (request == null) {
            // No meaningful diff for this op type (or path failed to parse) — show raw content.
            Messages.showInfoMessage(project, row.content.take(4000), "Proposed content — ${row.path}")
            return
        }
        DiffManager.getInstance().showDiff(project, request)
    }

    /** Shows all diffable modifications as one navigable diff chain. */
    fun showAll(project: Project, rows: List<PendingModRowUi>) {
        val requests = rows.mapNotNull { buildRequest(project, it) }
        when {
            requests.isEmpty() -> return
            requests.size == 1 -> DiffManager.getInstance().showDiff(project, requests.first())
            else -> DiffManager.getInstance()
                .showDiff(project, SimpleDiffRequestChain(requests), DiffDialogHints.DEFAULT)
        }
    }

    private fun buildRequest(project: Project, row: PendingModRowUi): SimpleDiffRequest? {
        val type = row.type.uppercase()
        val before: String = try {
            when (type) {
                "CREATE_FILE", "CREATE_ELEMENT" -> ""
                "REPLACE_ELEMENT", "DELETE_ELEMENT", "SAFE_DELETE" -> currentElementText(project, row.path) ?: ""
                "REPLACE_FILE", "DELETE_FILE" -> currentFileText(project, row.path) ?: ""
                else -> return null
            }
        } catch (e: Exception) {
            return null
        }

        val after = when (type) {
            "DELETE_ELEMENT", "DELETE_FILE", "SAFE_DELETE" -> ""
            else -> row.content
        }

        val fileName = try {
            ElementPath(row.path).filePath.substringAfterLast('/')
        } catch (e: Exception) {
            row.path.substringAfterLast('/')
        }
        val fileType = FileTypeManager.getInstance().getFileTypeByFileName(fileName)
        val factory = DiffContentFactory.getInstance()
        val request = SimpleDiffRequest(
            "${row.type} — ${ChatNavigationHelper.formatElementPath(row.path)}",
            factory.create(project, before, fileType),
            factory.create(project, after, fileType),
            "Current",
            "Proposed"
        )
        if (row.explanation.isNotBlank()) {
            val explanation = row.explanation
            // Provider-based notification API: the platform renders the component above the diff panes.
            DiffUtil.addNotification({ _ -> explanationBanner(explanation) }, request)
        }
        return request
    }

    /** Banner with the LLM's explanation, shown above the diff panes. */
    private fun explanationBanner(text: String): JComponent {
        val bg = JBColor(Color(0xE8F0FE), Color(0x1E2A38))
        return JPanel(BorderLayout()).apply {
            background = bg
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor(Color(0x2E86C1), Color(0x5DADE2)), 0, 3, 0, 0),
                JBUI.Borders.empty(6, 10)
            )
            add(JBLabel("\uD83D\uDCA1").apply {
                border = JBUI.Borders.emptyRight(6)
                verticalAlignment = SwingConstants.TOP
            }, BorderLayout.WEST)
            add(JBTextArea(text).apply {
                isEditable = false
                lineWrap = true
                wrapStyleWord = true
                background = bg
                font = JBFont.label().deriveFont(Font.ITALIC)
            }, BorderLayout.CENTER)
        }
    }

    private fun currentElementText(project: Project, path: String): String? {
        val elementPath = ElementPath(path)
        if (elementPath.segments.isEmpty()) return currentFileText(project, path)
        val navigator = PsiNavigator(project)
        return runReadAction { navigator.findElement(elementPath)?.text }
    }

    private fun currentFileText(project: Project, path: String): String? {
        val filePath = try {
            ElementPath(path).filePath
        } catch (e: Exception) {
            path
        }
        val basePath = project.basePath ?: return null
        val vf = LocalFileSystem.getInstance().findFileByPath("$basePath/$filePath") ?: return null
        return runReadAction {
            FileDocumentManager.getInstance().getDocument(vf)?.text
                ?: String(vf.contentsToByteArray(), vf.charset)
        }
    }
}
