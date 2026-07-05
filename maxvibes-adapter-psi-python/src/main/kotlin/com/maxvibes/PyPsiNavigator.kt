package com.maxvibes.adapter.psi.python

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.jetbrains.python.psi.*
import com.maxvibes.domain.model.code.ElementPath
import java.io.File

class PyPsiNavigator(private val project: Project) {

    private val psiManager: PsiManager by lazy { PsiManager.getInstance(project) }

    fun findFile(path: ElementPath): PyFile? {
        val filePath = path.filePath
        val absolutePath = if (File(filePath).isAbsolute) filePath
        else "${project.basePath ?: ""}/$filePath"
        val vFile = LocalFileSystem.getInstance().findFileByPath(absolutePath) ?: return null
        return psiManager.findFile(vFile) as? PyFile
    }

    fun findElement(path: ElementPath): PsiElement? {
        val pyFile = findFile(path) ?: return null
        val segments = parseElementSegments(path)
        if (segments.isEmpty()) return pyFile
        return navigateToElement(pyFile, segments)
    }

    fun getChildren(element: PsiElement): List<PsiElement> = when (element) {
        is PyFile -> element.topLevelClasses.toList() +
                element.topLevelFunctions.toList() +
                topLevelAttributes(element)

        is PyClass -> element.methods.toList() +
                element.classAttributes.toList() +
                element.instanceAttributes.toList()

        else -> element.children.toList()
    }

    private fun parseElementSegments(path: ElementPath): List<Pair<String, String>> {
        val value = path.value
        val filePrefix = "file:${path.filePath}"
        val afterFile = value.removePrefix(filePrefix).trimStart('/')
        if (afterFile.isEmpty()) return emptyList()
        val segmentRegex = Regex("(\\w+)\\[([^\\]]+)\\]")
        return afterFile.split("/").mapNotNull { segment ->
            segmentRegex.matchEntire(segment)?.let { it.groupValues[1] to it.groupValues[2] }
        }
    }

    private fun navigateToElement(root: PsiElement, segments: List<Pair<String, String>>): PsiElement? {
        var current: PsiElement = root
        for ((kind, name) in segments) {
            current = findChildByKindAndName(current, kind, name) ?: return null
        }
        return current
    }

    private fun findChildByKindAndName(parent: PsiElement, kind: String, name: String): PsiElement? = when (kind) {
        "class" -> when (parent) {
            is PyFile -> parent.topLevelClasses.firstOrNull { it.name == name }
            else -> null
        }

        "function" -> when (parent) {
            is PyFile -> parent.topLevelFunctions.firstOrNull { it.name == name }
            is PyClass -> parent.methods.firstOrNull { it.name == name }
            else -> null
        }

        "property" -> when (parent) {
            is PyFile -> topLevelAttributes(parent).firstOrNull { it.name == name }
            is PyClass -> (parent.classAttributes.toList() + parent.instanceAttributes.toList())
                .firstOrNull { it.name == name }

            else -> null
        }

        else -> null
    }

    private fun topLevelAttributes(file: PyFile): List<PyTargetExpression> =
        file.statements
            .filterIsInstance<PyAssignmentStatement>()
            .flatMap { it.targets.toList() }
            .filterIsInstance<PyTargetExpression>()
}
