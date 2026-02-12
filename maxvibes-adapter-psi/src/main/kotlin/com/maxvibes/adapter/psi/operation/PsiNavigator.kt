package com.maxvibes.adapter.psi.operation

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.maxvibes.domain.model.code.ElementPath
import org.jetbrains.kotlin.psi.*

/**
 * Навигация по PSI дереву.
 *
 * Поддерживаемые сегменты путей:
 *   class[Name], interface[Name], object[Name], function[Name], property[Name],
 *   companion_object, init, enum_entry[Name], constructor
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
        // Special cases that don't require scanning declarations
        when (kind.lowercase()) {
            "companion_object", "companion" -> {
                return findCompanionObject(parent)
            }
            "init" -> {
                return findInitBlock(parent, name)
            }
            "constructor" -> {
                return findConstructor(parent, name)
            }
        }

        val declarations = getDeclarations(parent)

        return declarations.firstOrNull { child ->
            when (kind.lowercase()) {
                "class" -> child is KtClass && !child.isInterface() && !child.isEnum() && child.name == name
                "interface" -> child is KtClass && child.isInterface() && child.name == name
                "enum" -> child is KtClass && child.isEnum() && child.name == name
                "object" -> child is KtObjectDeclaration && !child.isCompanion() && child.name == name
                "function", "fun" -> child is KtNamedFunction && child.name == name
                "property", "val", "var" -> child is KtProperty && child.name == name
                "enum_entry" -> child is KtEnumEntry && child.name == name
                else -> false
            }
        }
    }

    /**
     * Get declarations from a parent element, handling different container types.
     */
    private fun getDeclarations(parent: PsiElement): List<KtDeclaration> {
        return when (parent) {
            is KtFile -> parent.declarations
            is KtClassOrObject -> parent.declarations
            is KtClassBody -> parent.declarations
            else -> emptyList()
        }
    }

    /**
     * Find companion object inside a class.
     */
    private fun findCompanionObject(parent: PsiElement): PsiElement? {
        val classOrObject = when (parent) {
            is KtClassOrObject -> parent
            is KtClassBody -> parent.parent as? KtClassOrObject
            else -> null
        } ?: return null

        return classOrObject.companionObjects.firstOrNull()
    }

    /**
     * Find init block by index (name = "0", "1", etc.) or first if no index.
     */
    private fun findInitBlock(parent: PsiElement, name: String): PsiElement? {
        val classOrObject = when (parent) {
            is KtClassOrObject -> parent
            is KtClassBody -> parent.parent as? KtClassOrObject
            else -> null
        } ?: return null

        val initBlocks = classOrObject.body?.anonymousInitializers ?: emptyList()
        val index = name.toIntOrNull() ?: 0
        return initBlocks.getOrNull(index)
    }

    /**
     * Find constructor. name = "primary" for primary, or "0", "1" for secondary constructors.
     */
    private fun findConstructor(parent: PsiElement, name: String): PsiElement? {
        val classOrObject = when (parent) {
            is KtClass -> parent
            is KtClassBody -> parent.parent as? KtClass
            else -> null
        } ?: return null

        if (name.lowercase() == "primary") {
            return classOrObject.primaryConstructor
        }

        val secondaryConstructors = classOrObject.secondaryConstructors
        val index = name.toIntOrNull() ?: 0
        return secondaryConstructors.getOrNull(index)
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