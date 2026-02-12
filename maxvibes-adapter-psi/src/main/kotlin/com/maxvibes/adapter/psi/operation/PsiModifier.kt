package com.maxvibes.adapter.psi.operation

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.codeStyle.CodeStyleManager
import com.maxvibes.adapter.psi.kotlin.KotlinElementFactory
import com.maxvibes.domain.model.code.ElementKind
import com.maxvibes.domain.model.modification.InsertPosition
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtImportList

/**
 * Модификация PSI элементов.
 *
 * Поддерживает:
 * - File-level: createFile, replaceFileContent
 * - Element-level: addElement, replaceElement, deleteElement
 * - Import-level: addImport, removeImport
 */
class PsiModifier(
    private val project: Project,
    private val elementFactory: KotlinElementFactory
) {

    // ═══════════════════════════════════════════════════
    // File operations
    // ═══════════════════════════════════════════════════

    /**
     * Создаёт файл и добавляет его в указанную директорию
     */
    fun createFile(directory: PsiDirectory, fileName: String, content: String): PsiFile? {
        println("[PsiModifier] Creating file '$fileName' in ${directory.virtualFile.path}")

        val existingFile = directory.findFile(fileName)
        if (existingFile != null) {
            println("[PsiModifier] File already exists, replacing content")
            replaceFileContent(existingFile, content)
            return existingFile
        }

        val psiFile = PsiFileFactory.getInstance(project)
            .createFileFromText(fileName, KotlinFileType.INSTANCE, content)

        CodeStyleManager.getInstance(project).reformat(psiFile)

        val addedFile = directory.add(psiFile) as? PsiFile
        println("[PsiModifier] File added: ${addedFile?.virtualFile?.path}")
        return addedFile
    }

    /**
     * Заменяет содержимое файла через PSI
     */
    fun replaceFileContent(file: PsiFile, newContent: String): PsiFile? {
        println("[PsiModifier] Replacing content of ${file.name}")

        val newFile = PsiFileFactory.getInstance(project)
            .createFileFromText(file.name, KotlinFileType.INSTANCE, newContent)

        val firstChild = file.firstChild
        val lastChild = file.lastChild

        if (firstChild != null && lastChild != null) {
            file.deleteChildRange(firstChild, lastChild)
        }

        newFile.children.forEach { child ->
            file.add(child.copy())
        }

        CodeStyleManager.getInstance(project).reformat(file)
        return file
    }

    // ═══════════════════════════════════════════════════
    // Element operations
    // ═══════════════════════════════════════════════════

    fun addElement(
        parent: PsiElement,
        content: String,
        kind: ElementKind,
        position: InsertPosition
    ): PsiElement? {
        println("[PsiModifier] Adding element of kind $kind to ${parent.javaClass.simpleName}")

        val newElement = elementFactory.createElementFromText(content, kind)
        if (newElement == null) {
            println("[PsiModifier] ERROR: Failed to create element from content")
            return null
        }

        val added = when (position) {
            InsertPosition.FIRST_CHILD -> addAsFirstChild(parent, newElement)
            InsertPosition.LAST_CHILD -> addAsLastChild(parent, newElement)
            InsertPosition.BEFORE -> parent.parent.addBefore(newElement, parent)
            InsertPosition.AFTER -> parent.parent.addAfter(newElement, parent)
        }

        ensureNewLineBefore(added)
        CodeStyleManager.getInstance(project).reformat(added)

        println("[PsiModifier] Element added successfully")
        return added
    }

    /**
     * Заменяет элемент. Для FILE использует replaceFileContent.
     * Для элементов — PSI replace с сохранением окружающих whitespace.
     */
    fun replaceElement(target: PsiElement, content: String, kind: ElementKind): PsiElement? {
        println("[PsiModifier] Replacing element of kind $kind")

        // Для файлов — специальная логика
        if (target is PsiFile || kind == ElementKind.FILE) {
            val file = when (target) {
                is PsiFile -> target
                else -> target.containingFile
            }
            if (file != null) {
                println("[PsiModifier] Target is FILE, using replaceFileContent")
                return replaceFileContent(file, content)
            }
        }

        val newElement = elementFactory.createElementFromText(content, kind)
        if (newElement == null) {
            println("[PsiModifier] ERROR: Failed to create element from content")
            return null
        }

        // Сохраняем whitespace до и после элемента
        val prevWhitespace = target.prevSibling?.takeIf { it is PsiWhiteSpace }?.text
        val nextWhitespace = target.nextSibling?.takeIf { it is PsiWhiteSpace }?.text

        val replaced = target.replace(newElement)

        // Восстанавливаем whitespace если он был потерян
        if (prevWhitespace != null && replaced.prevSibling?.text != prevWhitespace) {
            val ws = elementFactory.createWhiteSpace(prevWhitespace)
            replaced.parent.addBefore(ws, replaced)
        }

        CodeStyleManager.getInstance(project).reformat(replaced)

        println("[PsiModifier] Element replaced successfully")
        return replaced
    }

    fun deleteElement(element: PsiElement) {
        println("[PsiModifier] Deleting element: ${element.javaClass.simpleName}")

        // Удаляем trailing whitespace/newline вместе с элементом, чтобы не оставлять пустых строк
        val nextSibling = element.nextSibling
        if (nextSibling is PsiWhiteSpace && nextSibling.text.count { it == '\n' } <= 2) {
            nextSibling.delete()
        }

        element.delete()
    }

    // ═══════════════════════════════════════════════════
    // Import operations
    // ═══════════════════════════════════════════════════

    /**
     * Add an import to a KtFile via PSI.
     * Skips if the import already exists.
     * @return the added import directive, or null if already present
     */
    fun addImport(file: KtFile, importFqName: String): KtImportDirective? {
        println("[PsiModifier] Adding import: $importFqName to ${file.name}")

        // Проверяем дубликат
        val existing = file.importDirectives.any { directive ->
            directive.importPath?.pathStr == importFqName
        }
        if (existing) {
            println("[PsiModifier] Import already exists, skipping")
            return null
        }

        val newImport = elementFactory.createImportDirective(importFqName)

        val importList = file.importList
        return if (importList != null) {
            // Есть список импортов — добавляем в конец
            val added = importList.add(newImport) as? KtImportDirective
            println("[PsiModifier] Import added to existing import list")
            added
        } else {
            // Нет импортов — создаём после package declaration
            val packageDirective = file.packageDirective
            val anchor = packageDirective ?: file.firstChild

            val added = if (anchor != null) {
                // Добавляем newline + import после package
                val newLine = elementFactory.createNewLine(2)
                file.addAfter(newLine, anchor)
                file.addAfter(newImport, file.findElementAt(anchor.textRange.endOffset + 1) ?: anchor) as? KtImportDirective
            } else {
                file.add(newImport) as? KtImportDirective
            }

            println("[PsiModifier] Import added (created new import section)")
            added
        }
    }

    /**
     * Remove a specific import from a KtFile.
     * @return true if the import was found and removed
     */
    fun removeImport(file: KtFile, importFqName: String): Boolean {
        println("[PsiModifier] Removing import: $importFqName from ${file.name}")

        val importToRemove = file.importDirectives.firstOrNull { directive ->
            directive.importPath?.pathStr == importFqName
        }

        if (importToRemove == null) {
            println("[PsiModifier] Import not found, nothing to remove")
            return false
        }

        // Удаляем trailing whitespace
        val nextSibling = importToRemove.nextSibling
        if (nextSibling is PsiWhiteSpace && nextSibling.text == "\n") {
            nextSibling.delete()
        }

        importToRemove.delete()

        // Если import list стал пустой — удаляем его
        val importList = file.importList
        if (importList != null && importList.imports.isEmpty()) {
            // Удаляем пустой import list и лишние newlines
            val prevWs = importList.prevSibling
            if (prevWs is PsiWhiteSpace) {
                prevWs.delete()
            }
            importList.delete()
        }

        println("[PsiModifier] Import removed successfully")
        return true
    }

    // ═══════════════════════════════════════════════════
    // Private helpers
    // ═══════════════════════════════════════════════════

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

    private fun ensureNewLineBefore(element: PsiElement) {
        val prev = element.prevSibling
        if (prev != null && prev !is PsiWhiteSpace) {
            element.parent.addBefore(elementFactory.createNewLine(), element)
        } else if (prev is PsiWhiteSpace && !prev.text.contains("\n")) {
            element.parent.addBefore(elementFactory.createNewLine(), element)
        }
    }
}