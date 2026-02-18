package com.maxvibes.plugin.ui

import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBTextArea
import com.maxvibes.adapter.psi.operation.PsiNavigator
import com.maxvibes.domain.model.code.ElementPath
import com.maxvibes.domain.model.modification.ModificationResult
import java.awt.Point
import java.io.File

/**
 * Handles click-to-navigate for file paths and PSI element paths in the chat area.
 *
 * Supports:
 * - Element paths: LinesGame.kt/class[LinesGame]/function[updateAnimations]
 * - File paths: src/main/kotlin/com/example/File.kt
 */
object ChatNavigationHelper {

    private val SKIP_DIRS = setOf("build", ".gradle", ".idea", "node_modules", ".git")

    /**
     * Determines what was clicked: an element path or a file path.
     * Priority: element paths first (they contain file paths), then plain file paths.
     */
    fun getClickTargetAtPosition(
        chatArea: JBTextArea,
        point: Point,
        elementNavRegistry: Map<String, String>
    ): ClickTarget? {
        val offset = chatArea.viewToModel2D(point)
        if (offset < 0 || offset >= chatArea.document.length) return null

        val text = chatArea.text
        val lineStart = text.lastIndexOf('\n', offset - 1).let { if (it < 0) 0 else it + 1 }
        val lineEnd = text.indexOf('\n', offset).let { if (it < 0) text.length else it }
        val line = text.substring(lineStart, lineEnd)
        val posInLine = offset - lineStart

        // Pattern 1: Element paths — File.kt/segment[name] or File.kt/seg[a]/seg[b]
        val elementPathRegex = Regex("""(?:^|[\s\u2022(])([a-zA-Z][\w.-]*\.(?:kt|java)(?:/(?:\w+\[\w+]|\w+))+)""")
        for (match in elementPathRegex.findAll(line)) {
            val group = match.groups[1] ?: continue
            if (posInLine >= group.range.first && posInLine <= group.range.last + 1) {
                val displayText = group.value
                val fullPath = elementNavRegistry[displayText]
                return if (fullPath != null) {
                    ClickTarget.Element(fullPath)
                } else {
                    ClickTarget.File(displayText.substringBefore('/'))
                }
            }
        }

        // Pattern 2: Plain file paths — src/main/kotlin/..., *.kt, *.java, etc.
        val filePathRegex = Regex("""(?:^|[\s\u2022(])([a-zA-Z][\w./\\-]*\.(?:kt|java|xml|json|md|gradle|kts|yaml|yml|properties|txt))""")
        for (match in filePathRegex.findAll(line)) {
            val group = match.groups[1] ?: continue
            if (posInLine >= group.range.first && posInLine <= group.range.last + 1) {
                return ClickTarget.File(group.value)
            }
        }

        return null
    }

    /**
     * Navigate to a specific PSI element in the editor.
     * @return Status text for the status bar.
     */
    fun navigateToElement(project: Project, fullPathValue: String): String {
        val elementPath = ElementPath(fullPathValue)
        val filePath = elementPath.filePath
        val basePath = project.basePath ?: return "No project base path"

        val virtualFile = LocalFileSystem.getInstance().findFileByPath("$basePath/$filePath")
            ?: findFileRecursively(File(basePath), filePath.substringAfterLast('/'))?.let {
                LocalFileSystem.getInstance().findFileByIoFile(it)
            }

        if (virtualFile == null) return "File not found: $filePath"

        if (elementPath.segments.isEmpty()) {
            FileEditorManager.getInstance(project).openFile(virtualFile, true)
            return "Opened: ${filePath.substringAfterLast('/')}"
        }

        return try {
            val navigator = PsiNavigator(project)
            val offset = runReadAction {
                navigator.findElement(elementPath)?.textOffset
            }
            if (offset != null) {
                OpenFileDescriptor(project, virtualFile, offset).navigate(true)
                val lastSegment = elementPath.segments.lastOrNull()
                "Navigated to: ${lastSegment?.toPathString() ?: filePath.substringAfterLast('/')}"
            } else {
                FileEditorManager.getInstance(project).openFile(virtualFile, true)
                "Element not found, opened file"
            }
        } catch (e: Exception) {
            FileEditorManager.getInstance(project).openFile(virtualFile, true)
            "Opened: ${filePath.substringAfterLast('/')}"
        }
    }

    /**
     * Open a file in the editor by relative path.
     * @return Status text for the status bar.
     */
    fun openFileInEditor(project: Project, relativePath: String): String {
        val basePath = project.basePath ?: return "No project base path"
        val file = LocalFileSystem.getInstance().findFileByPath("$basePath/$relativePath")
        if (file != null && !file.isDirectory) {
            FileEditorManager.getInstance(project).openFile(file, true)
            return "Opened: ${relativePath.substringAfterLast('/')}"
        }
        val fileName = relativePath.substringAfterLast('/')
        val found = findFileRecursively(File(basePath), fileName)
        if (found != null) {
            val vf = LocalFileSystem.getInstance().findFileByIoFile(found)
            if (vf != null) {
                FileEditorManager.getInstance(project).openFile(vf, true)
                return "Opened: $fileName"
            }
        }
        return "File not found: $relativePath"
    }

    fun findFileRecursively(dir: File, name: String): File? {
        if (!dir.isDirectory) return null
        for (child in dir.listFiles() ?: return null) {
            if (child.isDirectory) {
                if (child.name in SKIP_DIRS) continue
                val found = findFileRecursively(child, name)
                if (found != null) return found
            } else if (child.name == name) {
                return child
            }
        }
        return null
    }

    /**
     * Compact display format for element paths.
     * "file:src/.../User.kt/class[User]/function[validate]" → "User.kt/class[User]/function[validate]"
     */
    fun formatElementPath(path: ElementPath): String {
        val fileName = path.filePath.substringAfterLast('/')
        val segments = path.segments
        return if (segments.isNotEmpty()) {
            "$fileName/${segments.joinToString("/") { it.toPathString() }}"
        } else {
            fileName
        }
    }

    /**
     * Registers element paths from modification results for click navigation.
     */
    fun registerElementPaths(
        modifications: List<ModificationResult>,
        registry: MutableMap<String, String>
    ) {
        modifications.filterIsInstance<ModificationResult.Success>().forEach { result ->
            val display = formatElementPath(result.affectedPath)
            registry[display] = result.affectedPath.value
        }
    }
}

// ==================== Click Target ====================

sealed class ClickTarget {
    data class Element(val fullPath: String) : ClickTarget()
    data class File(val relativePath: String) : ClickTarget()
}