package com.maxvibes.adapter.psi.context

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.maxvibes.application.port.output.IdeErrorsPort
import com.maxvibes.domain.model.code.IdeError
import com.maxvibes.shared.result.Result
import kotlinx.coroutines.delay

/**
 * Collects ERROR-severity highlights from IDE documents.
 *
 * IntelliJ's [DocumentMarkupModel] stores both compiler errors (reported after
 * build) and daemon-analysis errors as highlight ranges on documents. This covers
 * the main MaxVibes use cases: attaching visible IDE errors and checking files
 * immediately after Claude Code applies modifications.
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
                        errors += collectProblemsForFile(file, errorsOnly = true)
                    }
                }
            }

            log.info("[MaxVibes] Total errors collected: ${errors.size}")
            Result.Success(errors.sortedForStableSnapshot())
        } catch (e: Exception) {
            log.warn("[MaxVibes] Failed to collect IDE errors", e)
            Result.Failure(e)
        }
    }

    /**
     * Collects ERROR-severity highlights for selected project files after giving
     * IntelliJ's code-analysis daemon time to re-run on the just-touched files.
     *
     * Flow: resolve VirtualFiles -> restart the daemon for each file (read action on
     * EDT — PsiManager.findFile and DaemonCodeAnalyzer.restart both require it) ->
     * poll until two consecutive snapshots are identical or [timeoutMs] elapses.
     */
    override suspend fun getErrorsForFiles(
        relativePaths: List<String>,
        timeoutMs: Long
    ): Result<List<IdeError>, Exception> {
        try {
            val basePath = project.basePath ?: return Result.Success(emptyList())

            val normalizedPaths = relativePaths
                .map { it.removePrefix("file:").trimStart('/', '\\') }
                .filter { it.isNotBlank() }
                .distinct()
            if (normalizedPaths.isEmpty()) return Result.Success(emptyList())

            val files: List<VirtualFile> = ApplicationManager.getApplication()
                .runReadAction(Computable {
                    normalizedPaths.mapNotNull { relativePath ->
                        LocalFileSystem.getInstance().findFileByPath("$basePath/$relativePath")
                    }
                })
            if (files.isEmpty()) return Result.Success(emptyList())

            // Nudge the daemon so the snapshot reflects the just-applied modifications.
            // A bare EDT block is NOT a read action (hard assert on recent platforms),
            // hence runReadAction INSIDE invokeAndWait.
            ApplicationManager.getApplication().invokeAndWait {
                ApplicationManager.getApplication().runReadAction {
                    val psiManager = PsiManager.getInstance(project)
                    val daemon = DaemonCodeAnalyzer.getInstance(project)
                    files.forEach { vf ->
                        psiManager.findFile(vf)?.let { daemon.restart(it) }
                    }
                }
            }

            // Poll until the snapshot is stable: two identical consecutive reads.
            // Sorting makes the comparison independent of highlight iteration order.
            val startedAt = System.currentTimeMillis()
            delay(INITIAL_DELAY_MS)
            var previous: List<IdeError>? = null
            while (true) {
                val current: List<IdeError> = ApplicationManager.getApplication()
                    .runReadAction(Computable {
                        files.flatMap { vf -> collectProblemsForFile(vf, errorsOnly = true) }
                            .sortedWith(compareBy({ it.filePath }, { it.line }, { it.message }))
                    })

                if (previous != null && current == previous) {
                    return Result.Success(current)
                }
                if (System.currentTimeMillis() - startedAt > timeoutMs) {
                    return Result.Success(current)
                }
                previous = current
                delay(POLL_INTERVAL_MS)
            }
        } catch (e: Exception) {
            log.warn("[MaxVibes] Failed to collect post-apply IDE errors", e)
            return Result.Failure(e)
        }
    }

    // ── Error extraction ──────────────────────────────────────────────

    /**
     * Extracts [IdeError] entries from a file via [DocumentMarkupModel].
     *
     * Only highlighters with [HighlightSeverity.ERROR] are included when
     * [errorsOnly] is true. The default preserves the adapter's historical behavior:
     * this class reports errors, not warnings.
     *
     * Highlighters at invalid offsets are silently skipped.
     */
    private fun collectProblemsForFile(
        file: VirtualFile,
        errorsOnly: Boolean = false
    ): List<IdeError> {
        // PSI must exist for the file to belong to this project.
        PsiManager.getInstance(project).findFile(file) ?: return emptyList()

        val document = FileDocumentManager.getInstance().getDocument(file)
            ?: return emptyList()

        val markupModel = DocumentMarkupModel.forDocument(document, project, true)
            ?: return emptyList()

        val basePath = project.basePath ?: ""
        val relativePath = file.path.removePrefix(basePath).removePrefix("/")

        return markupModel.allHighlighters.mapNotNull { highlighter ->
            val info = highlighter.errorStripeTooltip as? HighlightInfo ?: return@mapNotNull null
            if (errorsOnly && info.severity != HighlightSeverity.ERROR) return@mapNotNull null
            if (!errorsOnly && info.severity != HighlightSeverity.ERROR) return@mapNotNull null

            val offset = highlighter.startOffset
            if (offset < 0 || offset >= document.textLength) return@mapNotNull null

            val line = document.getLineNumber(offset) + 1
            val column = offset - document.getLineStartOffset(line - 1) + 1
            val message = info.description ?: info.toolTip ?: "Unknown error"

            IdeError(filePath = relativePath, line = line, column = column, message = message)
        }
    }

    private fun List<IdeError>.sortedForStableSnapshot(): List<IdeError> =
        sortedWith(
            compareBy<IdeError> { it.filePath }
                .thenBy { it.line }
                .thenBy { it.column }
                .thenBy { it.message }
        )

    companion object {
        private const val INITIAL_DELAY_MS = 1500L
        private const val POLL_INTERVAL_MS = 700L
    }
}
