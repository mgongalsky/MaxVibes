package com.maxvibes.adapter.psi.python

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.jetbrains.python.psi.*
import com.maxvibes.domain.model.code.ElementPath
import com.maxvibes.domain.model.modification.*
import com.maxvibes.shared.result.Result
import com.jetbrains.python.codeInsight.imports.AddImportHelper
import com.intellij.psi.PsiDocumentManager
import com.intellij.openapi.util.text.StringUtil

class PyPsiModifier(
    private val project: Project,
    private val navigator: PyPsiNavigator,
    private val factory: PythonElementFactory
) {

    fun replaceFile(path: ElementPath, newContent: String): Result<Unit, String> = runWrite {
        val pyFile = navigator.findFile(path) ?: return@runWrite Result.Failure("File not found: ${path.filePath}")
        val documentManager = PsiDocumentManager.getInstance(project)
        val document = documentManager.getDocument(pyFile)
            ?: return@runWrite Result.Failure("No document for file: ${path.filePath}")
        document.setText(StringUtil.convertLineSeparators(newContent))
        documentManager.commitDocument(document)
        Result.Success(Unit)
    }

    fun createElement(parentPath: ElementPath, content: String, position: InsertPosition): Result<Unit, String> =
        runWrite {
            val parent = navigator.findElement(parentPath)
                ?: return@runWrite Result.Failure("Parent not found: ${parentPath.value}")
            val newElement = createMatchingElement(parent, content)
            when (position) {
                InsertPosition.LAST_CHILD -> parent.add(newElement)
                InsertPosition.FIRST_CHILD -> parent.addBefore(newElement, parent.firstChild)
                InsertPosition.AFTER -> parent.parent?.addAfter(newElement, parent)
                InsertPosition.BEFORE -> parent.parent?.addBefore(newElement, parent)
            }
            Result.Success(Unit)
        }

    fun replaceElement(targetPath: ElementPath, newContent: String): Result<Unit, String> = runWrite {
        val target = navigator.findElement(targetPath)
            ?: return@runWrite Result.Failure("Element not found: ${targetPath.value}")
        val newElement = createMatchingElement(target, newContent)
        if (target is PyFunction && newElement is PyFunction) copyDecorators(target, newElement)
        target.replace(newElement)
        Result.Success(Unit)
    }

    fun deleteElement(targetPath: ElementPath): Result<Unit, String> = runWrite {
        val target = navigator.findElement(targetPath)
            ?: return@runWrite Result.Failure("Element not found: ${targetPath.value}")
        target.delete()
        Result.Success(Unit)
    }

    /**
     * Adds an import to a Python file via [AddImportHelper].
     *
     * Convention for [importPath]:
     * - contains a dot ("typing.List") -> `from typing import List`
     * - single name ("os") -> `import os`
     */
    fun addImport(filePath: ElementPath, importPath: String): Result<Unit, String> = runWrite {
        val file = navigator.findFile(filePath)
            ?: return@runWrite Result.Failure("File not found: ${filePath.filePath}")
        val dotIndex = importPath.lastIndexOf('.')
        if (dotIndex > 0) {
            AddImportHelper.addOrUpdateFromImportStatement(
                file,
                importPath.substring(0, dotIndex),
                importPath.substring(dotIndex + 1),
                null,
                AddImportHelper.ImportPriority.THIRD_PARTY,
                null
            )
        } else {
            AddImportHelper.addImportStatement(
                file,
                importPath,
                null,
                AddImportHelper.ImportPriority.THIRD_PARTY,
                null
            )
        }
        Result.Success(Unit)
    }

    /**
     * Removes an import matching [importPath] (FQN) from a Python file.
     *
     * Matches both plain imports (`import os.path`) and from-imports
     * (`from typing import List` matches "typing.List"). If the statement
     * has a single import element, the whole statement is removed.
     */
    fun removeImport(filePath: ElementPath, importPath: String): Result<Unit, String> = runWrite {
        val file = navigator.findFile(filePath)
            ?: return@runWrite Result.Failure("File not found: ${filePath.filePath}")
        var removed = false
        outer@ for (statement in file.statements.filterIsInstance<PyImportStatementBase>()) {
            val elements = statement.importElements
            for (importElement in elements) {
                val fqn = when (statement) {
                    is PyFromImportStatement -> {
                        val source = statement.importSourceQName?.toString() ?: continue
                        val name = importElement.importedQName?.toString() ?: continue
                        "$source.$name"
                    }

                    else -> importElement.importedQName?.toString() ?: continue
                }
                if (fqn == importPath) {
                    if (elements.size == 1) statement.delete() else importElement.delete()
                    removed = true
                    break@outer
                }
            }
        }
        if (removed) Result.Success(Unit) else Result.Failure("Import not found: $importPath")
    }

    private fun createMatchingElement(target: PsiElement, source: String): PsiElement = when (target) {
        is PyFunction -> factory.createFunction(source)
        is PyClass -> factory.createClass(source)
        is PyAssignmentStatement -> factory.createAssignment(source)
        else -> factory.createStatement(source)
    }

    private fun copyDecorators(source: PyFunction, dest: PyFunction) {
        val srcDecorators = source.decoratorList ?: return
        if (dest.decoratorList != null) return
        dest.addBefore(srcDecorators.copy(), dest.firstChild)
    }

    private fun <T> runWrite(action: () -> Result<T, String>): Result<T, String> {
        var result: Result<T, String> = Result.Failure("Not executed")
        val app = ApplicationManager.getApplication()
        val run = {
            WriteCommandAction.runWriteCommandAction(project) {
                result = try {
                    action()
                } catch (e: Exception) {
                    Result.Failure("${e.javaClass.simpleName}: ${e.message ?: "no message"}")
                }
            }
        }
        if (app.isDispatchThread) run() else app.invokeAndWait(run)
        return result
    }
}
