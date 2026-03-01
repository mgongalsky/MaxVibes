package com.maxvibes.adapter.psi.compiler

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.problems.WolfTheProblemSolver
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiManager
import com.intellij.psi.SyntaxTraverser
import com.maxvibes.application.port.output.IdeErrorsPort
import com.maxvibes.domain.model.code.IdeError
import com.maxvibes.shared.result.Result

class IntellijIdeErrorsAdapter(private val project: Project) : IdeErrorsPort {
    override suspend fun getCompilerErrors(): Result<List<IdeError>, Exception> {
        println("[MaxVibes Errors] Starting to scan for IDE errors...")
        return try {
            val errors = mutableListOf<IdeError>()

            ReadAction.run<Exception> {
                val wolf = WolfTheProblemSolver.getInstance(project)
                val problemFiles = wolf.problemFiles
                println("[MaxVibes Errors] WolfTheProblemSolver found ${problemFiles.size} problem files.")

                val psiManager = PsiManager.getInstance(project)
                val docManager = FileDocumentManager.getInstance()
                val basePath = project.basePath ?: ""

                for (file in problemFiles) {
                    val psiFile = psiManager.findFile(file) ?: continue
                    val document = docManager.getDocument(file) ?: continue
                    println("[MaxVibes Errors] Scanning file for PsiErrorElements: ${file.name}")

                    val errorElements = SyntaxTraverser.psiTraverser(psiFile)
                        .filter(PsiErrorElement::class.java)
                        .toList()

                    for (error in errorElements) {
                        val offset = error.textOffset
                        val line = document.getLineNumber(offset) + 1
                        val column = offset - document.getLineStartOffset(line - 1) + 1
                        val relativePath = file.path.removePrefix(basePath).removePrefix("/")

                        errors.add(
                            IdeError(
                                filePath = relativePath,
                                line = line,
                                column = column,
                                message = error.errorDescription
                            )
                        )
                    }
                    println("[MaxVibes Errors] Found ${errorElements.size} syntax errors in ${file.name}")
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