package com.maxvibes.adapter.psi.operation

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.maxvibes.domain.model.code.ElementPath
import org.jetbrains.kotlin.psi.*

/**
 * Навигация по PSI дереву
 */
class PsiNavigator(private val project: Project) {

    private val psiManager: PsiManager by lazy { PsiManager.getInstance(project) }

    fun findFile(path: ElementPath): PsiFile? {
        val filePath = path.filePath
        val basePath = project.basePath ?: return null
        val fullPath = if (filePath.startsWith(basePath)) filePath else "$basePath/$filePath"

        val virtualFile = LocalFileSystem.getInstance().findFileByPath(fullPath)
        return virtualFile?.let { psiManager.findFile(it) }
    }

    fun findElement(path: ElementPath): PsiElement? {
        val file = findFile(path) ?: return null

        if (path.isFile || path.segments.isEmpty()) {
            return file
        }

        return navigateToElement(file, path.segments.map { it.kind to it.name })
    }

    private fun navigateToElement(root: PsiElement, segments: List<Pair<String, String>>): PsiElement? {
        var current: PsiElement = root

        for ((kind, name) in segments) {
            current = findChildByKindAndName(current, kind, name) ?: return null
        }

        return current
    }

    private fun findChildByKindAndName(parent: PsiElement, kind: String, name: String): PsiElement? {
        val declarations = when (parent) {
            is KtFile -> parent.declarations
            is KtClassOrObject -> parent.declarations
            else -> return null
        }

        return declarations.firstOrNull { child ->
            when (kind.lowercase()) {
                "class" -> child is KtClass && !child.isInterface() && child.name == name
                "interface" -> child is KtClass && child.isInterface() && child.name == name
                "object" -> child is KtObjectDeclaration && child.name == name
                "function", "fun" -> child is KtNamedFunction && child.name == name
                "property", "val", "var" -> child is KtProperty && child.name == name
                else -> false
            }
        }
    }

    fun getParent(element: PsiElement): PsiElement? {
        return when (val parent = element.parent) {
            is KtClassBody -> parent.parent // Вернуть класс, а не тело класса
            is KtFile -> parent
            is KtClassOrObject -> parent
            else -> parent
        }
    }

    fun getChildren(element: PsiElement): List<PsiElement> {
        return when (element) {
            is KtFile -> element.declarations
            is KtClassOrObject -> element.declarations
            else -> emptyList()
        }
    }
}