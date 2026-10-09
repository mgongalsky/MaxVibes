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
import com.maxvibes.domain.model.code.ElementKind

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

    fun createElement(
        parentPath: ElementPath,
        content: String,
        position: InsertPosition,
        elementKind: ElementKind
    ): Result<Unit, String> = runWrite {
        val target = navigator.findElement(parentPath)
            ?: return@runWrite Result.Failure("Target not found: ${parentPath.value}")
        // Creation is dispatched by the requested declaration, never by its parent.
        val newElement = factory.createDeclaration(content, elementKind)
        val sibling = position == InsertPosition.BEFORE || position == InsertPosition.AFTER
        val anchor = if (sibling) {
            (target as? PyStatement)
                ?: com.intellij.psi.util.PsiTreeUtil.getParentOfType(target, PyStatement::class.java)
                ?: return@runWrite Result.Failure("Insertion target is not a Python statement: ${parentPath.value}")
        } else null
        val container = if (sibling) {
            anchor!!.parent
        } else when (target) {
            is PyClass -> target.statementList
            is PyFunction -> target.statementList
            is PyStatementList -> target
            is PyFile -> target
            else -> return@runWrite Result.Failure("Target cannot contain Python declarations: ${parentPath.value}")
        }
        if (container !is PyStatementList && container !is PyFile) {
            return@runWrite Result.Failure("Insertion requires a Python file or statement list")
        }
        val documentManager = PsiDocumentManager.getInstance(project)
        val document = documentManager.getDocument(target.containingFile)
            ?: return@runWrite Result.Failure("No document for file: ${parentPath.filePath}")
        val added = when (position) {
            InsertPosition.FIRST_CHILD -> {
                val first = when (container) {
                    is PyStatementList -> container.statements.firstOrNull()
                    is PyFile -> container.statements.firstOrNull()
                    else -> null
                }
                container.addBefore(newElement, first)
            }

            InsertPosition.LAST_CHILD -> container.add(newElement)
            InsertPosition.BEFORE -> container.addBefore(newElement, anchor)
            InsertPosition.AFTER -> container.addAfter(newElement, anchor)
        }
        // Format the suite owner too: insertion can expand an inline suite.
        val formatTarget = if (container is PyStatementList) container.parent else added
        val formatPointer = com.intellij.psi.SmartPointerManager.getInstance(project)
            .createSmartPsiElementPointer(formatTarget)
        // Flush formatting scheduled by PSI insertion before explicitly formatting
        // the element, otherwise its document offsets can still be stale.
        documentManager.doPostponedOperationsAndUnblockDocument(document)
        documentManager.commitDocument(document)
        val currentTarget = formatPointer.element
            ?: return@runWrite Result.Failure("Created Python declaration was lost during PSI synchronization")
        com.intellij.psi.codeStyle.CodeStyleManager.getInstance(project).reformat(currentTarget)
        documentManager.doPostponedOperationsAndUnblockDocument(document)
        documentManager.commitDocument(document)
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

    /** Adds a legacy FQN or an explicit Python import, preserving aliases and relative levels. */
    fun addImport(filePath: ElementPath, importPath: String): Result<Unit, String> = runWrite {
        val file = navigator.findFile(filePath)
            ?: return@runWrite Result.Failure("File not found: ${filePath.filePath}")
        val request = PythonImportLookup.parse(file, importPath)
        for (expected in request.entries) {
            val exists = file.statements.filterIsInstance<PyImportStatementBase>().any { statement ->
                statement.importElements.any { request.matches(expected, PythonImportLookup.entry(statement, it)) }
            }
            if (exists) continue
            if (expected.source != null) {
                AddImportHelper.addOrUpdateFromImportStatement(
                    file, expected.source, expected.name, expected.alias,
                    AddImportHelper.ImportPriority.THIRD_PARTY, null
                )
            } else {
                AddImportHelper.addImportStatement(
                    file, expected.name, expected.alias,
                    AddImportHelper.ImportPriority.THIRD_PARTY, null
                )
            }
        }
        Result.Success(Unit)
    }

    /** Removes every matching file-level import; explicit requests also match form and alias. */
    fun removeImport(filePath: ElementPath, importPath: String): Result<Unit, String> = runWrite {
        val file = navigator.findFile(filePath)
            ?: return@runWrite Result.Failure("File not found: ${filePath.filePath}")
        val request = PythonImportLookup.parse(file, importPath)
        val statements = file.statements.filterIsInstance<PyImportStatementBase>()
        val actual = statements.flatMap { statement ->
            statement.importElements.map { PythonImportLookup.entry(statement, it) }
        }
        if (request.entries.any { expected -> actual.none { request.matches(expected, it) } }) {
            return@runWrite Result.Failure("Import not found: $importPath")
        }
        for (statement in statements) {
            val elements = statement.importElements.toList()
            val matching = elements.filter { element ->
                val entry = PythonImportLookup.entry(statement, element)
                request.entries.any { request.matches(it, entry) }
            }
            if (matching.isEmpty()) continue
            if (matching.size == elements.size) {
                statement.delete()
            } else {
                matching.asReversed().forEach { it.delete() }
            }
        }
        Result.Success(Unit)
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
