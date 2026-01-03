package com.maxvibes.adapter.psi.operation

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.codeStyle.CodeStyleManager
import com.maxvibes.adapter.psi.kotlin.KotlinElementFactory
import com.maxvibes.domain.model.code.ElementKind
import com.maxvibes.domain.model.modification.InsertPosition
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile

/**
 * Модификация PSI элементов
 */
class PsiModifier(
    private val project: Project,
    private val elementFactory: KotlinElementFactory
) {

    fun createFile(directory: PsiElement, fileName: String, content: String): PsiFile? {
        return executeWriteCommand("Create file $fileName") {
            val psiFile = PsiFileFactory.getInstance(project)
                .createFileFromText(fileName, KotlinFileType.INSTANCE, content)

            // Форматируем код
            CodeStyleManager.getInstance(project).reformat(psiFile)

            psiFile
        }
    }

    fun replaceFileContent(file: PsiFile, newContent: String): PsiFile? {
        return executeWriteCommand("Replace file content") {
            val newFile = PsiFileFactory.getInstance(project)
                .createFileFromText(file.name, KotlinFileType.INSTANCE, newContent)

            file.deleteChildRange(file.firstChild, file.lastChild)

            newFile.children.forEach { child ->
                file.add(child.copy())
            }

            CodeStyleManager.getInstance(project).reformat(file)
            file
        }
    }

    fun addElement(
        parent: PsiElement,
        content: String,
        kind: ElementKind,
        position: InsertPosition
    ): PsiElement? {
        val newElement = elementFactory.createElementFromText(content, kind) ?: return null

        return executeWriteCommand("Add element") {
            val added = when (position) {
                InsertPosition.FIRST_CHILD -> addAsFirstChild(parent, newElement)
                InsertPosition.LAST_CHILD -> addAsLastChild(parent, newElement)
                InsertPosition.BEFORE -> parent.parent.addBefore(newElement, parent)
                InsertPosition.AFTER -> parent.parent.addAfter(newElement, parent)
            }

            // Добавляем перенос строки если нужно
            ensureNewLineBefore(added)

            CodeStyleManager.getInstance(project).reformat(added)
            added
        }
    }

    private fun addAsFirstChild(parent: PsiElement, element: PsiElement): PsiElement {
        return when (parent) {
            is KtFile -> {
                val firstDecl = parent.declarations.firstOrNull()
                if (firstDecl != null) {
                    parent.addBefore(element, firstDecl)
                } else {
                    parent.add(element)
                }
            }
            is KtClassOrObject -> {
                val body = parent.body
                if (body != null) {
                    val lBrace = body.lBrace
                    if (lBrace != null) {
                        body.addAfter(element, lBrace)
                    } else {
                        body.add(element)
                    }
                } else {
                    parent.add(element)
                }
            }
            else -> parent.addAfter(element, parent.firstChild)
        }
    }

    private fun addAsLastChild(parent: PsiElement, element: PsiElement): PsiElement {
        return when (parent) {
            is KtFile -> parent.add(element)
            is KtClassOrObject -> {
                val body = parent.body
                if (body != null) {
                    val rBrace = body.rBrace
                    if (rBrace != null) {
                        body.addBefore(element, rBrace)
                    } else {
                        body.add(element)
                    }
                } else {
                    parent.add(element)
                }
            }
            else -> parent.addBefore(element, parent.lastChild)
        }
    }

    fun replaceElement(target: PsiElement, content: String, kind: ElementKind): PsiElement? {
        val newElement = elementFactory.createElementFromText(content, kind) ?: return null

        return executeWriteCommand("Replace element") {
            val replaced = target.replace(newElement)
            CodeStyleManager.getInstance(project).reformat(replaced)
            replaced
        }
    }

    fun deleteElement(element: PsiElement) {
        executeWriteCommand("Delete element") {
            element.delete()
        }
    }

    private fun ensureNewLineBefore(element: PsiElement) {
        val prev = element.prevSibling
        if (prev != null && !prev.text.contains("\n")) {
            element.parent.addBefore(elementFactory.createNewLine(), element)
        }
    }

    private fun <T> executeWriteCommand(name: String, action: () -> T): T? {
        var result: T? = null
        ApplicationManager.getApplication().invokeAndWait {
            WriteCommandAction.runWriteCommandAction(project) {
                result = action()
            }
        }
        return result
    }
}