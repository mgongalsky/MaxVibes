package com.maxvibes.adapter.psi.context

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager
import com.maxvibes.application.port.output.IdeErrorsPort
import com.maxvibes.domain.model.code.IdeError
import com.maxvibes.shared.result.Result

/**
 * Collects ERROR-severity highlights from all currently open documents.
 *
 * IntelliJ's [DocumentMarkupModel] stores both compiler errors (reported after
 * build) and daemon-analysis errors as highlight ranges on open documents.
 * This covers the primary use case: user runs a build, sees errors in the
 * editor gutter, and wants to attach them to the LLM request.
 *
 * Files that are not open in the editor are not scanned — their documents
 * are not loaded into memory and cannot be read without triggering I/O.
 */
class IntellijIdeErrorsAdapter(private val project: Project) : IdeErrorsPort {

    private val log = Logger.getInstance(IntellijIdeErrorsAdapter::class.java)

    override suspend fun getCompilerErrors(): Result<List<IdeError>, Exception> {
        return try {
            val errors = mutableListOf<IdeError>()

            // Must run on EDT inside a read action to access markup models safely.
            ApplicationManager.getApplication().invokeAndWait {
                ApplicationManager.getApplication().runReadAction {
                    val openFiles = FileEditorManager.getInstance(project).openFiles
                    log.info("[MaxVibes] Scanning ${openFiles.size} open file(s) for errors")

                    for (file in openFiles) {
                        errors += extractErrors(file)
                    }
                }
            }

            log.info("[MaxVibes] Total errors collected: ${errors.size}")
            Result.Success(errors)
        } catch (e: Exception) {
            log.warn("[MaxVibes] Failed to collect IDE errors", e)
            Result.Failure(e)
        }
    }

    // ── Error extraction ──────────────────────────────────────────────

    /**
     * Extracts [IdeError] entries from a single open file via [DocumentMarkupModel].
     *
     * Only highlighters with [HighlightSeverity.ERROR] are included.
     * Highlighters at invalid offsets are silently skipped.
     */
    private fun extractErrors(file: com.intellij.openapi.vfs.VirtualFile): List<IdeError> {
        // PSI must exist for the file to belong to this project.
        PsiManager.getInstance(project).findFile(file) ?: return emptyList()

        val document = FileDocumentManager.getInstance().getDocument(file)
            ?: return emptyList()

        val markupModel = DocumentMarkupModel.forDocument(document, project, false)
            ?: return emptyList()

        val basePath = project.basePath ?: ""
        val relativePath = file.path.removePrefix(basePath).removePrefix("/")

        return markupModel.allHighlighters.mapNotNull { highlighter ->
            val info = highlighter.errorStripeTooltip as? HighlightInfo ?: return@mapNotNull null
            if (info.severity != HighlightSeverity.ERROR) return@mapNotNull null

            val offset = highlighter.startOffset
            if (offset < 0 || offset >= document.textLength) return@mapNotNull null

            val line = document.getLineNumber(offset) + 1
            val column = offset - document.getLineStartOffset(line - 1) + 1
            val message = info.description ?: info.toolTip ?: "Unknown error"

            IdeError(filePath = relativePath, line = line, column = column, message = message)
        }
    }
}
