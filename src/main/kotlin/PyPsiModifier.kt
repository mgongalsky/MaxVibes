package com.maxvibes.adapter.psi.python

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.jetbrains.python.psi.*
import com.maxvibes.domain.model.code.ElementPath
import com.maxvibes.domain.model.modification.*
import com.maxvibes.shared.result.Result

class PyPsiModifier(
    private val project: Project,
    private val navigator: PyPsiNavigator,
    private val factory: PythonElementFactory
) {

    fun replaceFile(path: ElementPath, newContent: String): Result<Unit, String> = runWrite {
        val pyFile = navigator.findFile(path) ?: return@runWrite Result.Failure("File not found: ${path.filePath}")
        val newFile = factory.createFile(newContent)
        pyFile.children.forEach { it.delete() }
        newFile.children.forEach { child -> pyFile.add(child.copy()) }
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
        val run = { WriteCommandAction.runWriteCommandAction(project) { result = action() } }
        if (app.isDispatchThread) run() else app.invokeAndWait(run)
        return result
    }
}
