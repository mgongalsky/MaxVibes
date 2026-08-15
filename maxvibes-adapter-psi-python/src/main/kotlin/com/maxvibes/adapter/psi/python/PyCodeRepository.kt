package com.maxvibes.adapter.psi.python

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.python.PythonLanguage
import com.jetbrains.python.psi.PyFile
import com.maxvibes.adapter.psi.python.mapper.PyPsiToDomainMapper
import com.maxvibes.application.port.output.CodeRepository
import com.maxvibes.application.port.output.CodeRepositoryError
import com.maxvibes.domain.model.code.CodeElement
import com.maxvibes.domain.model.code.CodeGranularity
import com.maxvibes.domain.model.code.CodeView
import com.maxvibes.domain.model.code.CodeViewRequest
import com.maxvibes.domain.model.code.ElementKind
import com.maxvibes.domain.model.code.ElementPath
import com.maxvibes.domain.model.modification.Modification
import com.maxvibes.domain.model.modification.ModificationError
import com.maxvibes.domain.model.modification.ModificationResult
import com.maxvibes.shared.result.Result
import java.io.File

/**
 * Implementation of [CodeRepository] backed by the PyCharm Python PSI API.
 *
 * Python counterpart of `PsiCodeRepository` from maxvibes-adapter-psi. Read paths
 * mirror the Kotlin adapter; structural writes are delegated to [PyPsiModifier];
 * file-level create/delete are handled here (the modifier has no file-level API).
 * IDE refactorings (rename / safe delete / move) go through [PyRefactoringExecutor],
 * which uses platform-only processors — they work in PyCharm out of the box.
 */
class PyCodeRepository(private val project: Project) : CodeRepository {

    private val mapper = PyPsiToDomainMapper()
    private val navigator = PyPsiNavigator(project)
    private val elementFactory = PythonElementFactory(project)
    private val modifier = PyPsiModifier(project, navigator, elementFactory)
    private val refactoringExecutor = PyRefactoringExecutor(project)

    /** Renders PSI elements into prompt-ready text at the requested granularity level. */
    private val renderer = PyPsiCodeViewRenderer()

    override suspend fun getFileContent(path: ElementPath): Result<String, CodeRepositoryError> {
        return runReadAction {
            val psiFile = navigator.findFile(path)
                ?: return@runReadAction Result.Failure(CodeRepositoryError.NotFound(path.filePath))
            Result.Success(psiFile.text)
        }
    }

    override suspend fun getElement(path: ElementPath): Result<CodeElement, CodeRepositoryError> {
        return runReadAction {
            val psiElement = (if (path.isFile) navigator.findFile(path) else navigator.findElement(path))
                ?: return@runReadAction Result.Failure(CodeRepositoryError.NotFound(path.value))
            try {
                val basePath = project.basePath ?: ""
                val codeElement = when (psiElement) {
                    is PyFile -> mapper.mapFile(psiElement, basePath)
                    else -> mapper.mapElement(psiElement, path.parentPath ?: path)
                        ?: return@runReadAction Result.Failure(CodeRepositoryError.ReadError("Cannot map element"))
                }
                Result.Success(codeElement)
            } catch (e: Exception) {
                Result.Failure(CodeRepositoryError.ReadError(e.message ?: "Unknown error"))
            }
        }
    }

    override suspend fun findElements(
        basePath: ElementPath,
        kinds: Set<ElementKind>?,
        namePattern: Regex?
    ): Result<List<CodeElement>, CodeRepositoryError> {
        return runReadAction {
            val rootElement = (if (basePath.isFile) navigator.findFile(basePath) else navigator.findElement(basePath))
                ?: return@runReadAction Result.Failure(CodeRepositoryError.NotFound(basePath.value))
            val mapped = navigator.getChildren(rootElement)
                .mapNotNull { mapper.mapElement(it, basePath) }
                .filter { element ->
                    (kinds == null || element.kind in kinds) &&
                            (namePattern == null || namePattern.matches(element.name))
                }
            Result.Success(mapped)
        }
    }

    override suspend fun applyModification(modification: Modification): ModificationResult {
        return when (modification) {
            is Modification.CreateFile -> createFile(modification)
            is Modification.ReplaceFile ->
                modifier.replaceFile(modification.targetPath, modification.newContent)
                    .toModificationResult(modification)

            is Modification.DeleteFile -> deleteFile(modification)
            is Modification.CreateElement ->
                modifier.createElement(modification.targetPath, modification.content, modification.position)
                    .toModificationResult(modification)

            is Modification.ReplaceElement ->
                modifier.replaceElement(modification.targetPath, modification.newContent)
                    .toModificationResult(modification)

            is Modification.DeleteElement ->
                modifier.deleteElement(modification.targetPath)
                    .toModificationResult(modification)

            is Modification.AddImport ->
                modifier.addImport(modification.targetPath, modification.importPath)
                    .toModificationResult(modification)

            is Modification.RemoveImport ->
                modifier.removeImport(modification.targetPath, modification.importPath)
                    .toModificationResult(modification)

            is Modification.RenameElement -> renameElement(modification)
            is Modification.SafeDelete -> safeDeleteElement(modification)
            is Modification.MoveElement -> moveElement(modification)
        }
    }

    override suspend fun applyModifications(modifications: List<Modification>): List<ModificationResult> {
        if (modifications.isEmpty()) return emptyList()

        val refactorings = modifications.filter {
            it is Modification.RenameElement ||
                    it is Modification.SafeDelete ||
                    it is Modification.MoveElement
        }
        if (refactorings.isNotEmpty()) {
            if (modifications.size != 1) {
                val reason = "IDE refactorings cannot be mixed with other modifications in one atomic batch"
                return modifications.map { modification ->
                    ModificationResult.Failure(
                        modification = modification,
                        error = ModificationError.InvalidOperation(reason)
                    )
                }
            }
            val result = applyModification(modifications.single())
            if (result is ModificationResult.Success) {
                synchronizeDocumentsAndPsi()
                flushDocumentsToDisk()
            }
            return listOf(result)
        }

        val snapshots = captureSnapshots(modifications)
        var failedIndex = -1
        var failureReason = "Unknown batch failure"

        try {
            for (index in modifications.indices) {
                val modification = modifications[index]
                val result = applyModification(modification)
                synchronizeDocumentsAndPsi()

                if (result is ModificationResult.Failure) {
                    failedIndex = index
                    failureReason = result.error.message
                    break
                }

                val postconditionError = verifyPostcondition(modification)
                if (postconditionError != null) {
                    failedIndex = index
                    failureReason = postconditionError
                    break
                }
            }
        } catch (e: Exception) {
            failedIndex = failedIndex.takeIf { it >= 0 } ?: 0
            failureReason = "${e.javaClass.simpleName}: ${e.message ?: "batch execution failed"}"
        }

        if (failedIndex >= 0) {
            val rollbackError = restoreSnapshots(snapshots)
            flushDocumentsToDisk()
            val reason = if (rollbackError == null) {
                failureReason
            } else {
                "$failureReason; rollback error: $rollbackError"
            }
            return modifications.map { modification ->
                ModificationResult.Failure(
                    modification = modification,
                    error = ModificationError.BatchRolledBack(failedIndex, reason)
                )
            }
        }

        flushDocumentsToDisk()
        return modifications.map { modification ->
            ModificationResult.Success(
                modification = modification,
                affectedPath = modification.targetPath,
                resultContent = when (modification) {
                    is Modification.CreateFile -> modification.content
                    is Modification.ReplaceFile -> modification.newContent
                    is Modification.CreateElement -> modification.content
                    is Modification.ReplaceElement -> modification.newContent
                    is Modification.AddImport -> modification.importPath
                    else -> null
                }
            )
        }
    }

    override suspend fun exists(path: ElementPath): Boolean {
        return runReadAction {
            if (path.isFile) navigator.findFile(path) != null else navigator.findElement(path) != null
        }
    }

    override suspend fun validateSyntax(content: String): Result<Unit, CodeRepositoryError> {
        return runReadAction {
            try {
                val file = elementFactory.createFile(content)
                val error = PsiTreeUtil.findChildOfType(file, PsiErrorElement::class.java)
                if (error != null) {
                    Result.Failure(CodeRepositoryError.ValidationError(error.errorDescription))
                } else {
                    Result.Success(Unit)
                }
            } catch (e: Exception) {
                Result.Failure(CodeRepositoryError.ValidationError(e.message ?: "Parse error"))
            }
        }
    }

    override suspend fun getCodeView(request: CodeViewRequest): CodeView {
        return runReadAction {
            val content = when (request.granularity) {

                // Full file — return raw PSI text
                CodeGranularity.FULL -> {
                    val psiFile = navigator.findFile(ElementPath.file(request.filePath))
                        ?: error("File not found: ${request.filePath}")
                    psiFile.text
                }

                // All declarations without bodies, imports preserved
                CodeGranularity.SIGNATURES -> {
                    val pyFile = navigator.findFile(ElementPath.file(request.filePath))
                        ?: error("File not found: ${request.filePath}")
                    renderer.renderSignatures(pyFile)
                }

                // Compact outline; elementPath (class name) narrows to a single class
                CodeGranularity.OUTLINE -> {
                    val pyFile = navigator.findFile(ElementPath.file(request.filePath))
                        ?: error("File not found: ${request.filePath}")
                    // Local copy on purpose: request.elementPath is a public property from
                    // another module (maxvibes-domain), so Kotlin cannot smart-cast it to
                    // a non-null String at the findTopLevelClass call site.
                    val className = request.elementPath
                    if (className != null) {
                        val pyClass = pyFile.findTopLevelClass(className)
                            ?: error("Class '$className' not found in: ${request.filePath}")
                        renderer.renderOutline(pyClass)
                    } else {
                        // Python files commonly host several top-level declarations —
                        // render the whole-file outline instead of picking one class
                        renderer.renderOutline(pyFile)
                    }
                }

                // Single element resolved by its PSI path segments
                CodeGranularity.ELEMENT -> {
                    val elemPathStr = request.elementPath
                        ?: error("elementPath is required for ELEMENT granularity")
                    val fullPath = ElementPath("file:${request.filePath}/$elemPathStr")
                    val element = navigator.findElement(fullPath)
                        ?: error("Element not found: $elemPathStr in ${request.filePath}")
                    renderer.renderElement(element)
                }

                // Usage search is Kotlin-only for now: no ReferencesSearch wiring
                // in the Python adapter yet.
                CodeGranularity.USAGES ->
                    error("USAGES granularity is not supported for Python yet (${request.filePath})")

                // Call hierarchies are Kotlin-only for now, same as USAGES.
                CodeGranularity.CALLERS ->
                    error("CALLERS granularity is not supported for Python yet (${request.filePath})")

                CodeGranularity.CALLEES ->
                    error("CALLEES granularity is not supported for Python yet (${request.filePath})")

                // Not a code view: SKILL requests carry a skill name, not a file path,
                // and are resolved by the interaction services from the skill repository
                // BEFORE any CodeRepository call. Reaching this branch is a routing bug.
                CodeGranularity.SKILL ->
                    error(
                        "SKILL granularity must be resolved by the interaction layer, " +
                                "not the PSI adapter (skill: ${request.filePath})"
                    )
            }
            CodeView(request.filePath, request.granularity, content)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Private implementation — file create/delete (not covered by PyPsiModifier)
    // ═══════════════════════════════════════════════════════════════

    private fun createFile(mod: Modification.CreateFile): ModificationResult {
        return try {
            var resultContent: String? = null
            val app = ApplicationManager.getApplication()
            val action = {
                WriteCommandAction.runWriteCommandAction(project) {
                    val filePath = mod.targetPath.filePath
                    val directory = findOrCreateDirectory(filePath) ?: return@runWriteCommandAction
                    val fileName = File(filePath).name
                    val psiFile = PsiFileFactory.getInstance(project)
                        .createFileFromText(fileName, PythonLanguage.getInstance(), mod.content)
                    val added = directory.add(psiFile) as? PsiFile
                    resultContent = added?.text
                }
            }
            if (app.isDispatchThread) action() else app.invokeAndWait(action)
            if (resultContent != null) {
                ModificationResult.Success(
                    modification = mod,
                    affectedPath = mod.targetPath,
                    resultContent = resultContent
                )
            } else {
                ModificationResult.Failure(
                    modification = mod,
                    error = ModificationError.IOError("Failed to create file: ${mod.targetPath.filePath}")
                )
            }
        } catch (e: Exception) {
            ModificationResult.Failure(
                modification = mod,
                error = ModificationError.IOError(e.message ?: "Failed to create file")
            )
        }
    }

    private fun deleteFile(mod: Modification.DeleteFile): ModificationResult {
        val psiFile = runReadAction { navigator.findFile(mod.targetPath) }
            ?: return ModificationResult.Failure(
                modification = mod,
                error = ModificationError.FileNotFound(mod.targetPath.filePath)
            )
        return try {
            val app = ApplicationManager.getApplication()
            val action = { WriteCommandAction.runWriteCommandAction(project) { psiFile.delete() } }
            if (app.isDispatchThread) action() else app.invokeAndWait(action)
            ModificationResult.Success(modification = mod, affectedPath = mod.targetPath, resultContent = null)
        } catch (e: Exception) {
            ModificationResult.Failure(
                modification = mod,
                error = ModificationError.IOError(e.message ?: "Failed to delete file")
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Private implementation — IDE refactorings (Rename / Safe Delete / Move)
    // ═══════════════════════════════════════════════════════════════

    private fun renameElement(mod: Modification.RenameElement): ModificationResult {
        val target = runReadAction {
            if (mod.targetPath.isFile) navigator.findFile(mod.targetPath) else navigator.findElement(mod.targetPath)
        } ?: return ModificationResult.Failure(
            modification = mod,
            error = ModificationError.ElementNotFound(mod.targetPath)
        )
        return when (val result = refactoringExecutor.rename(target, mod.newName)) {
            is Result.Success -> ModificationResult.Success(
                modification = mod,
                affectedPath = mod.targetPath,
                resultContent = mod.newName
            )

            is Result.Failure -> ModificationResult.Failure(
                modification = mod,
                error = ModificationError.InvalidOperation(result.error)
            )
        }
    }

    private fun safeDeleteElement(mod: Modification.SafeDelete): ModificationResult {
        val target = runReadAction {
            if (mod.targetPath.isFile) navigator.findFile(mod.targetPath) else navigator.findElement(mod.targetPath)
        } ?: return ModificationResult.Failure(
            modification = mod,
            error = ModificationError.ElementNotFound(mod.targetPath)
        )
        return when (val result = refactoringExecutor.safeDelete(target)) {
            is Result.Success -> ModificationResult.Success(
                modification = mod,
                affectedPath = mod.targetPath,
                resultContent = null
            )

            is Result.Failure -> ModificationResult.Failure(
                modification = mod,
                error = ModificationError.InvalidOperation(result.error)
            )
        }
    }

    private fun moveElement(mod: Modification.MoveElement): ModificationResult {
        val psiFile = runReadAction { navigator.findFile(mod.targetPath) }
            ?: return ModificationResult.Failure(
                modification = mod,
                error = ModificationError.InvalidOperation(
                    "MOVE_ELEMENT v1 moves whole files: '${mod.targetPath.value}' does not resolve to a file"
                )
            )
        val targetDir = resolveOrCreateDirectory(mod.destination)
            ?: return ModificationResult.Failure(
                modification = mod,
                error = ModificationError.IOError("Cannot resolve or create destination directory: ${mod.destination}")
            )
        return when (val result = refactoringExecutor.moveFile(psiFile, targetDir)) {
            is Result.Success -> ModificationResult.Success(
                modification = mod,
                affectedPath = mod.targetPath,
                resultContent = mod.destination
            )

            is Result.Failure -> ModificationResult.Failure(
                modification = mod,
                error = ModificationError.InvalidOperation(result.error)
            )
        }
    }

    /** Resolves a project-relative directory, creating missing segments in a write command. */
    private fun resolveOrCreateDirectory(relativeDirPath: String): PsiDirectory? {
        val projectBasePath = project.basePath ?: return null
        val normalized = relativeDirPath.replace('\\', '/').trim('/')
        if (normalized.isBlank()) return null
        val psiManager = PsiManager.getInstance(project)

        val existing = VfsUtil.findFileByIoFile(File("$projectBasePath/$normalized"), true)
        if (existing != null && existing.isDirectory) {
            return runReadAction { psiManager.findDirectory(existing) }
        }

        var created: PsiDirectory? = null
        val app = ApplicationManager.getApplication()
        val action = {
            WriteCommandAction.runWriteCommandAction(project) {
                val baseVf = VfsUtil.findFileByIoFile(File(projectBasePath), true) ?: return@runWriteCommandAction
                val baseDir = psiManager.findDirectory(baseVf) ?: return@runWriteCommandAction
                created = createDirectoryPath(baseDir, normalized)
            }
        }
        if (app.isDispatchThread) action() else app.invokeAndWait(action)
        return created
    }

    /**
     * Resolves the target directory strictly relative to the project base path,
     * creating missing subdirectories. Intentionally has NO fallback heuristics
     * (unlike the Kotlin adapter) — a wrong path must fail, not land elsewhere.
     */
    private fun findOrCreateDirectory(filePath: String): PsiDirectory? {
        val projectBasePath = project.basePath ?: return null
        val baseVirtualFile = VfsUtil.findFileByIoFile(File(projectBasePath), true) ?: return null
        val baseDir = PsiManager.getInstance(project).findDirectory(baseVirtualFile) ?: return null
        val dirPath = File(filePath).parent?.replace('\\', '/') ?: ""
        return createDirectoryPath(baseDir, dirPath)
    }

    private fun createDirectoryPath(baseDir: PsiDirectory, relativePath: String): PsiDirectory {
        if (relativePath.isBlank()) return baseDir
        var currentDir = baseDir
        for (part in relativePath.split('/', '\\').filter { it.isNotBlank() }) {
            currentDir = currentDir.findSubdirectory(part) ?: currentDir.createSubdirectory(part)
        }
        return currentDir
    }

    // ═══════════════════════════════════════════════════════════════
    // Result mapping
    // ═══════════════════════════════════════════════════════════════

    private fun Result<Unit, String>.toModificationResult(mod: Modification): ModificationResult =
        when (this) {
            is Result.Success -> ModificationResult.Success(
                modification = mod,
                affectedPath = mod.targetPath,
                resultContent = null
            )

            is Result.Failure -> ModificationResult.Failure(
                modification = mod,
                error = ModificationError.IOError(error)
            )
        }
    private data class FileSnapshot(
        val path: ElementPath,
        val existed: Boolean,
        val content: String?
    )

    private fun captureSnapshots(modifications: List<Modification>): List<FileSnapshot> = runReadAction {
        modifications
            .map { it.targetPath.filePath }
            .distinct()
            .map { filePath ->
                val path = ElementPath.file(filePath)
                val file = navigator.findFile(path)
                FileSnapshot(
                    path = path,
                    existed = file != null,
                    content = file?.text
                )
            }
    }
    private fun synchronizeDocumentsAndPsi() {
        val app = ApplicationManager.getApplication()
        val action = {
            WriteCommandAction.runWriteCommandAction(project) {
                com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments()
            }
        }
        if (app.isDispatchThread) action() else app.invokeAndWait(action)
    }
    private fun restoreSnapshots(snapshots: List<FileSnapshot>): String? {
        var rollbackError: String? = null
        val app = ApplicationManager.getApplication()
        val action = {
            WriteCommandAction.runWriteCommandAction(project, "Rollback MaxVibes Python modifications", null, Runnable {
                try {
                    val documentManager = com.intellij.psi.PsiDocumentManager.getInstance(project)
                    snapshots.forEach { snapshot ->
                        val current = navigator.findFile(snapshot.path)
                        if (!snapshot.existed) {
                            current?.delete()
                        } else {
                            val originalContent = requireNotNull(snapshot.content)
                            if (current != null) {
                                val document = documentManager.getDocument(current)
                                    ?: error("No document for ${snapshot.path.filePath}")
                                document.setText(originalContent)
                                documentManager.commitDocument(document)
                            } else {
                                val directory = findOrCreateDirectory(snapshot.path.filePath)
                                    ?: error("Cannot recreate directory for ${snapshot.path.filePath}")
                                val fileName = File(snapshot.path.filePath).name
                                val restored = PsiFileFactory.getInstance(project)
                                    .createFileFromText(fileName, PythonLanguage.getInstance(), originalContent)
                                directory.add(restored)
                            }
                        }
                    }
                    documentManager.commitAllDocuments()
                } catch (e: Exception) {
                    rollbackError = "${e.javaClass.simpleName}: ${e.message ?: "rollback failed"}"
                }
            })
        }
        if (app.isDispatchThread) action() else app.invokeAndWait(action)
        return rollbackError
    }
    private fun verifyPostcondition(modification: Modification): String? = runReadAction {
        fun normalized(text: String): String = text.filterNot { it.isWhitespace() }
        fun hasImport(fileText: String, importPath: String): Boolean {
            val dot = importPath.lastIndexOf('.')
            val plain = normalized("import $importPath")
            val fromImport = if (dot > 0) {
                normalized("from ${importPath.substring(0, dot)} import ${importPath.substring(dot + 1)}")
            } else null
            val actual = normalized(fileText)
            return actual.contains(plain) || (fromImport != null && actual.contains(fromImport))
        }

        when (modification) {
            is Modification.CreateFile -> {
                val file = navigator.findFile(modification.targetPath)
                    ?: return@runReadAction "Created Python file does not exist: ${modification.targetPath.filePath}"
                if (normalized(file.text) != normalized(modification.content)) {
                    "Created Python file content does not match the request"
                } else null
            }

            is Modification.ReplaceFile -> {
                val file = navigator.findFile(modification.targetPath)
                    ?: return@runReadAction "Replaced Python file does not exist: ${modification.targetPath.filePath}"
                if (normalized(file.text) != normalized(modification.newContent)) {
                    "Python file content does not match REPLACE_FILE postcondition"
                } else null
            }

            is Modification.DeleteFile -> {
                if (navigator.findFile(modification.targetPath) != null) "Deleted Python file still exists" else null
            }

            is Modification.CreateElement -> {
                val file = navigator.findFile(ElementPath.file(modification.targetPath.filePath))
                    ?: return@runReadAction "Python target file disappeared after CREATE_ELEMENT"
                if (!normalized(file.text).contains(normalized(modification.content))) {
                    "Created Python element cannot be found with the requested content"
                } else null
            }

            is Modification.ReplaceElement -> {
                val element = navigator.findElement(modification.targetPath)
                    ?: return@runReadAction "Replaced Python element cannot be resolved"
                if (normalized(element.text) != normalized(modification.newContent)) {
                    "Python element does not match REPLACE_ELEMENT postcondition"
                } else null
            }

            is Modification.DeleteElement -> {
                if (navigator.findElement(modification.targetPath) != null) "Deleted Python element still resolves" else null
            }

            is Modification.AddImport -> {
                val file = navigator.findFile(modification.targetPath)
                    ?: return@runReadAction "Python import target disappeared"
                if (!hasImport(file.text, modification.importPath)) "Python import was not added" else null
            }

            is Modification.RemoveImport -> {
                val file = navigator.findFile(modification.targetPath)
                    ?: return@runReadAction "Python import target disappeared"
                if (hasImport(file.text, modification.importPath)) "Python import was not removed" else null
            }

            is Modification.RenameElement,
            is Modification.SafeDelete,
            is Modification.MoveElement -> null
        }
    }
    private fun flushDocumentsToDisk() {
        ApplicationManager.getApplication().invokeAndWait {
            com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().saveAllDocuments()
        }
    }
}
