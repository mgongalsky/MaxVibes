package com.maxvibes.plugin.files

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path

/** Platform-only text file access. Call methods inside [read] or [write]. */
internal class ProjectTextFiles(private val project: Project) {
    data class Snapshot(
        val path: String,
        val bytes: ByteArray?,
        val charset: Charset?,
        val documentText: String?,
        val unsaved: Boolean
    )

    private val documents get() = FileDocumentManager.getInstance()
    private val psiDocuments get() = PsiDocumentManager.getInstance(project)

    fun resolve(path: String): Path {
        val root = Path.of(requireNotNull(project.basePath) { "Project has no base directory" })
            .toAbsolutePath().normalize()
        val supplied = Path.of(path.replace('\\', '/'))
        val resolved = (if (supplied.isAbsolute) supplied else root.resolve(supplied)).normalize()
        require(resolved != root && resolved.startsWith(root)) { "File must be inside the project: $path" }
        val realRoot = root.toRealPath()
        var ancestor: Path? = resolved
        while (ancestor != null && !Files.exists(ancestor, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            ancestor = ancestor.parent
        }
        require(ancestor != null && ancestor.toRealPath().startsWith(realRoot)) {
            "File resolves outside the project: $path"
        }
        return resolved
    }

    fun find(path: String): VirtualFile? {
        val resolved = resolve(path)
        val file = LocalFileSystem.getInstance().findFileByPath(resolved.toString().replace('\\', '/'))
        check(file != null || !Files.exists(resolved)) {
            "File exists on disk but is unavailable in VFS; refresh the project: $path"
        }
        if (file != null) require(!file.isDirectory) { "Expected a file: $path" }
        return file
    }

    fun text(path: String): String {
        val file = requireNotNull(find(path)) { "File not found: $path" }
        require(!file.fileType.isBinary) { "Binary file cannot be read as text: $path" }
        return requireNotNull(documents.getDocument(file)) { "No text document: $path" }.text
    }

    fun capture(path: String): Snapshot {
        val file = find(path) ?: return Snapshot(path, null, null, null, false)
        require(!file.fileType.isBinary) { "Binary file cannot be modified as text: $path" }
        val document = documents.getDocument(file)
            ?: error("No text document: $path")
        return Snapshot(
            path, Files.readAllBytes(resolve(path)), file.charset, document.text,
            documents.isDocumentUnsaved(document)
        )
    }

    fun create(path: String, content: String) {
        require(find(path) == null) { "File already exists: $path" }
        val target = resolve(path)
        val parent = directory(target.parent)
        val file = parent.createChildData(this, target.fileName.toString())
        replace(file, content)
    }

    fun replace(path: String, content: String) {
        replace(requireNotNull(find(path)) { "File not found: $path" }, content)
    }

    private fun replace(file: VirtualFile, content: String) {
        require(!file.fileType.isBinary) { "Binary file cannot be modified as text: ${file.path}" }
        val document = requireNotNull(documents.getDocument(file)) { "No text document: ${file.path}" }
        psiDocuments.doPostponedOperationsAndUnblockDocument(document)
        document.setText(StringUtil.convertLineSeparators(content))
        psiDocuments.commitDocument(document)
    }

    fun delete(path: String) {
        requireNotNull(find(path)) { "File not found: $path" }.delete(this)
    }

    fun rename(path: String, name: String): String {
        require(
            name.isNotBlank() && name != "." && name != ".." &&
                    name.none { it == '/' || it == '\\' || it == ':' }) { "Expected a file name: $name" }
        val target = resolve(path).resolveSibling(name).toString()
        require(find(target) == null) { "Destination already exists: $target" }
        requireNotNull(find(path)) { "File not found: $path" }.rename(this, name)
        return target
    }

    fun move(path: String, destination: String): String {
        val root = Path.of(requireNotNull(project.basePath)).toAbsolutePath().normalize()
        val supplied = Path.of(destination.replace('\\', '/'))
        val parent = (if (supplied.isAbsolute) supplied else root.resolve(supplied)).normalize()
        val target = parent.resolve(resolve(path).fileName).toString()
        resolve(target)
        require(find(target) == null) { "Destination already exists: $target" }
        requireNotNull(find(path)) { "File not found: $path" }.move(this, directory(parent))
        return target
    }

    fun save(path: String) {
        val file = find(path) ?: return
        val document = documents.getCachedDocument(file) ?: return
        psiDocuments.commitDocument(document)
        documents.saveDocument(document)
        check(!documents.isDocumentUnsaved(document)) { "File could not be saved: $path" }
        check(StringUtil.convertLineSeparators(com.intellij.openapi.vfs.VfsUtil.loadText(file)) == document.text) {
            "Saved content differs from the editor: $path"
        }
    }

    /** Restores every snapshot even if another restoration fails; verifies disk and editor. */
    fun restore(snapshots: List<Snapshot>): List<String> {
        val errors = mutableListOf<String>()
        for (snapshot in snapshots) {
            try {
                var file = find(snapshot.path)
                val bytes = snapshot.bytes
                if (bytes == null) {
                    file?.delete(this)
                    check(!Files.exists(resolve(snapshot.path))) { "Created file still exists" }
                } else {
                    if (file == null) {
                        val target = resolve(snapshot.path)
                        file = directory(target.parent).createChildData(this, target.fileName.toString())
                    }
                    file.charset = requireNotNull(snapshot.charset)
                    // Reload the cached document from the exact original bytes before restoring unsaved edits.
                    file.setBinaryContent(bytes)
                    val document = documents.getDocument(file) ?: error("No restored document")
                    documents.reloadFromDisk(document)
                    if (snapshot.unsaved) document.setText(requireNotNull(snapshot.documentText))
                    psiDocuments.commitDocument(document)
                    check(Files.readAllBytes(resolve(snapshot.path)).contentEquals(bytes)) {
                        "Restored disk bytes differ from snapshot"
                    }
                    check(document.text == snapshot.documentText) { "Restored editor text differs from snapshot" }
                    check(documents.isDocumentUnsaved(document) == snapshot.unsaved) {
                        "Restored editor save state differs from snapshot"
                    }
                }
            } catch (e: Exception) {
                errors += "${snapshot.path}: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        return errors
    }

    private fun directory(path: Path): VirtualFile {
        val vfs = LocalFileSystem.getInstance()
        vfs.findFileByPath(path.toString().replace('\\', '/'))?.let {
            require(it.isDirectory) { "Expected directory: $path" }
            return it
        }
        check(!Files.exists(path)) { "Directory exists outside the VFS; refresh the project: $path" }
        return directory(requireNotNull(path.parent)).createChildDirectory(this, path.fileName.toString())
    }

    fun <T> read(action: () -> T): T = com.intellij.openapi.application.runReadAction(action)

    fun <T> write(action: () -> T): T {
        var result: kotlin.Result<T>? = null
        val app = ApplicationManager.getApplication()
        val command = {
            WriteCommandAction.runWriteCommandAction(project) {
                result = runCatching(action)
            }
        }
        if (app.isDispatchThread) command() else app.invokeAndWait(command)
        return requireNotNull(result) { "Write command did not execute" }.getOrThrow()
    }
}
