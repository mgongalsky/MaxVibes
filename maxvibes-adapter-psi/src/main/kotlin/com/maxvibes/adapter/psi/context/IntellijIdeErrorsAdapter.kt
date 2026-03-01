package com.maxvibes.adapter.psi.context

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager
import com.maxvibes.application.port.output.IdeErrorsPort
import com.maxvibes.domain.model.code.IdeError
import com.maxvibes.shared.result.Result
import com.intellij.openapi.editor.markup.MarkupModel
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationManager

class IntellijIdeErrorsAdapter(private val project: Project) : IdeErrorsPort {
    override suspend fun getCompilerErrors(): Result<List<IdeError>, Exception> {
        println("[MaxVibes Errors] Starting to scan for IDE errors in open files...")
        return try {
            val errors = mutableListOf<IdeError>()
            val app = ApplicationManager.getApplication()

            app.invokeAndWait {
                app.runReadAction {
                    val openFiles = FileEditorManager.getInstance(project).openFiles
                    println("[MaxVibes Errors] Scanning ${openFiles.size} open files.")

                    val psiManager = PsiManager.getInstance(project)
                    val docManager = FileDocumentManager.getInstance()
                    val basePath = project.basePath ?: ""

                    for (file in openFiles) {
                        psiManager.findFile(file) ?: continue
                        val document = docManager.getDocument(file) ?: continue

                        val markupModel = DocumentMarkupModel.forDocument(document, project, false) ?: continue
                        val highlighters = markupModel.allHighlighters

                        val fileErrors = mutableListOf<IdeError>()
                        for (highlighter in highlighters) {
                            val info = highlighter.errorStripeTooltip as? HighlightInfo ?: continue
                            if (info.severity != HighlightSeverity.ERROR) continue

                            val offset = highlighter.startOffset
                            if (offset < 0 || offset >= document.textLength) continue

                            val line = document.getLineNumber(offset) + 1
                            val column = offset - document.getLineStartOffset(line - 1) + 1
                            val relativePath = file.path.removePrefix(basePath).removePrefix("/")

                            val message = info.description ?: info.toolTip ?: "Unknown error"

                            fileErrors.add(
                                IdeError(
                                    filePath = relativePath,
                                    line = line,
                                    column = column,
                                    message = message
                                )
                            )
                        }

                        if (fileErrors.isNotEmpty()) {
                            println("[MaxVibes Errors] Found ${fileErrors.size} errors in ${file.name}")
                            errors.addAll(fileErrors)
                        }
                    }
                }
            }

            println("[MaxVibes Errors] Total errors found: ${errors.size}")
            Result.Success(errors)
        } catch (e: Exception) {
            println("[MaxVibes Errors] Exception while fetching errors: ${e.message}")
            Result.Failure(e)
        }
    }
}