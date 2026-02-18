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
    /**
     * Determines what was clicked: an element path or a file path.
     * Priority: element paths first (they contain file paths), then plain file paths.
     * @param verbose if true, prints debug logs (use only for mouseClicked, not mouseMoved)
     */
    fun getClickTargetAtPosition(
        chatArea: JBTextArea,
        point: Point,
        elementNavRegistry: Map<String, String>,
        verbose: Boolean = false
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
                // Try registry first
                val fullPath = elementNavRegistry[displayText]
                if (fullPath != null) {
                    if (verbose) println("[NavHelper] Element via registry: $fullPath")
                    return ClickTarget.Element(fullPath)
                }
                // Fallback: resolve from display text directly
                if (verbose) println("[NavHelper] Registry miss for '$displayText', resolving directly")
                return ClickTarget.ElementByDisplayText(displayText)
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
        println("[NavHelper] navigateToElement: $fullPathValue")
        val elementPath = ElementPath(fullPathValue)
        val filePath = elementPath.filePath
        val basePath = project.basePath ?: return "No project base path"
        println("[NavHelper]   filePath='$filePath', segments=${elementPath.segments.map { it.toPathString() }}")

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
                val element = navigator.findElement(elementPath)
                println("[NavHelper]   PSI element found: ${element != null}, type=${element?.javaClass?.simpleName}, offset=${element?.textOffset}")
                element?.textOffset
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

    /**
     * Navigate to element by display text like "LinesGame.kt/class[LinesGame]/function[drawBall]".
     * Finds the file in the project, builds a full ElementPath, and navigates via PSI.
     * @return Status text for the status bar.
     */
    fun navigateByDisplayText(project: Project, displayText: String): String {
        val basePath = project.basePath ?: return "No project base path"
        val fileName = displayText.substringBefore('/')
        val segmentsPart = displayText.substringAfter('/', "")

        println("[NavHelper] navigateByDisplayText: file='$fileName', segments='$segmentsPart'")

        // Find the actual file
        val ioFile = findFileRecursively(File(basePath), fileName)
        if (ioFile == null) return "File not found: $fileName"

        val relativePath = ioFile.toRelativeString(File(basePath)).replace('\\', '/')
        val virtualFile = LocalFileSystem.getInstance().findFileByIoFile(ioFile)
            ?: return "Cannot open: $fileName"

        if (segmentsPart.isBlank()) {
            FileEditorManager.getInstance(project).openFile(virtualFile, true)
            return "Opened: $fileName"
        }

        // Build full element path: file:relative/path/File.kt/segments
        val fullPathValue = "file:$relativePath/$segmentsPart"
        println("[NavHelper]   resolved fullPath: $fullPathValue")

        return try {
            val elementPath = ElementPath(fullPathValue)
            val navigator = PsiNavigator(project)
            val offset = runReadAction {
                val element = navigator.findElement(elementPath)
                println("[NavHelper]   PSI element: ${element?.javaClass?.simpleName}, offset=${element?.textOffset}")
                element?.textOffset
            }
            if (offset != null) {
                OpenFileDescriptor(project, virtualFile, offset).navigate(true)
                "Navigated to: $segmentsPart"
            } else {
                FileEditorManager.getInstance(project).openFile(virtualFile, true)
                "Element not found, opened file"
            }
        } catch (e: Exception) {
            println("[NavHelper]   error: ${e.message}")
            FileEditorManager.getInstance(project).openFile(virtualFile, true)
            "Opened: $fileName"
        }
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
        val successes = modifications.filterIsInstance<ModificationResult.Success>()
        println("[NavHelper] registerElementPaths: ${successes.size} success / ${modifications.size} total")
        successes.forEach { result ->
            val display = formatElementPath(result.affectedPath)
            registry[display] = result.affectedPath.value
            println("[NavHelper]   registered: '$display' → '${result.affectedPath.value}'")
        }
    }
}

// ==================== Click Target ====================

sealed class ClickTarget {
    /** Navigate via full ElementPath from registry */
    data class Element(val fullPath: String) : ClickTarget()
    /** Navigate via display text like "File.kt/class[X]/function[Y]" — resolves file at click time */
    data class ElementByDisplayText(val displayText: String) : ClickTarget()
    /** Open a file by relative path */
    data class File(val relativePath: String) : ClickTarget()
}