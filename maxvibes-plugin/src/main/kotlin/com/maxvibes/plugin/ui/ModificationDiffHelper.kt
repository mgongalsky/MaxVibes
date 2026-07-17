package com.maxvibes.plugin.ui

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.maxvibes.adapter.psi.operation.PsiNavigator
import com.maxvibes.domain.model.code.ElementPath

/**
 * Opens the standard IntelliJ diff window for one pending modification
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
 * Ops with no meaningful text diff (imports, rename, move) fall back to a plain
 * info dialog — the approval card normally hides the Diff button for those anyway.
 *
 * EDT-only (invoked from the approval card's Diff button).
 */
object ModificationDiffHelper {

    fun show(project: Project, row: PendingModRowUi) {
        val type = row.type.uppercase()
        val before: String? = try {
            when (type) {
                "CREATE_FILE", "CREATE_ELEMENT" -> ""
                "REPLACE_ELEMENT", "DELETE_ELEMENT", "SAFE_DELETE" -> currentElementText(project, row.path) ?: ""
                "REPLACE_FILE", "DELETE_FILE" -> currentFileText(project, row.path) ?: ""
                else -> null
            }
        } catch (e: Exception) {
            null
        }

        if (before == null) {
            // No meaningful diff for this op type (or path failed to parse) — show raw content.
            Messages.showInfoMessage(project, row.content.take(4000), "Proposed content — ${row.path}")
            return
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
            "MaxVibes: ${row.type} — $fileName",
            factory.create(project, before, fileType),
            factory.create(project, after, fileType),
            "Current",
            "Proposed"
        )
        DiffManager.getInstance().showDiff(project, request)
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
