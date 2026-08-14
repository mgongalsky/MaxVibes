package com.maxvibes.adapter.psi

import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiManager
import com.maxvibes.adapter.psi.kotlin.KotlinElementFactory
import com.maxvibes.adapter.psi.mapper.PsiToDomainMapper
import com.maxvibes.adapter.psi.operation.PsiCallHierarchyFinder
import com.maxvibes.adapter.psi.operation.PsiModifier
import com.maxvibes.adapter.psi.operation.PsiNavigator
import com.maxvibes.adapter.psi.operation.PsiRefactoringExecutor
import com.maxvibes.adapter.psi.renderer.PsiCodeViewRenderer
import com.maxvibes.application.port.output.CodeRepository
import com.maxvibes.application.port.output.CodeRepositoryError
import com.maxvibes.domain.model.code.CodeElement
import com.maxvibes.domain.model.code.CodeGranularity
import com.maxvibes.domain.model.code.CodeView
import com.maxvibes.domain.model.code.CodeViewRequest
import com.maxvibes.domain.model.code.ElementKind
import com.maxvibes.domain.model.code.ElementPath
import com.maxvibes.domain.model.modification.*
import com.maxvibes.shared.result.Result
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import java.io.File
import com.maxvibes.adapter.psi.operation.PsiUsagesFinder
import org.jetbrains.kotlin.psi.KtNamedFunction
import com.intellij.openapi.fileEditor.FileDocumentManager

/**
 * Implementation of [CodeRepository] backed by the IntelliJ PSI API.
 *
 * Handles file/element reads, structural modifications (create / replace / delete),
 * IDE refactorings (rename / safe delete / move), and granularity-aware code view
 * rendering via [PsiCodeViewRenderer].
 */
class PsiCodeRepository(private val project: Project) : CodeRepository {

    private val mapper = PsiToDomainMapper()
    private val navigator = PsiNavigator(project)
    private val elementFactory = KotlinElementFactory(project)
    private val modifier = PsiModifier(project, elementFactory)
    private val usagesFinder = PsiUsagesFinder(project)
    private val callHierarchyFinder = PsiCallHierarchyFinder(project)
    private val refactoringExecutor = PsiRefactoringExecutor(project)

    /** Renders PSI elements into prompt-ready text at the requested granularity level. */
    private val renderer = PsiCodeViewRenderer()

    override suspend fun getFileContent(path: ElementPath): Result<String, CodeRepositoryError> {
        return runReadAction {
            val psiFile = navigator.findFile(path)
                ?: return@runReadAction Result.Failure(CodeRepositoryError.NotFound(path.filePath))

            Result.Success(psiFile.text)
        }
    }

    override suspend fun getElement(path: ElementPath): Result<CodeElement, CodeRepositoryError> {
        return runReadAction {
            val psiElement = navigator.findElement(path)
                ?: return@runReadAction Result.Failure(CodeRepositoryError.NotFound(path.value))

            try {
                val basePath = project.basePath ?: ""
                val codeElement = when (psiElement) {
                    is KtFile -> mapper.mapFile(psiElement, basePath)
                    else -> mapper.mapDeclaration(psiElement, path.parentPath ?: path)
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
            val rootElement = navigator.findElement(basePath)
                ?: return@runReadAction Result.Failure(CodeRepositoryError.NotFound(basePath.value))

            val children = navigator.getChildren(rootElement)

            val mapped = children.mapNotNull { child ->
                mapper.mapDeclaration(child, basePath)
            }.filter { element ->
                (kinds == null || element.kind in kinds) &&
                        (namePattern == null || namePattern.matches(element.name))
            }

            Result.Success(mapped)
        }
    }

    override suspend fun applyModification(modification: Modification): ModificationResult {
        return when (modification) {
            is Modification.CreateFile -> createFile(modification)
            is Modification.ReplaceFile -> replaceFile(modification)
            is Modification.DeleteFile -> deleteFile(modification)
            is Modification.CreateElement -> createElement(modification)
            is Modification.ReplaceElement -> replaceElement(modification)
            is Modification.DeleteElement -> deleteElement(modification)
            is Modification.AddImport -> addImport(modification)
            is Modification.RemoveImport -> removeImport(modification)
            is Modification.RenameElement -> renameElement(modification)
            is Modification.SafeDelete -> safeDeleteElement(modification)
            is Modification.MoveElement -> moveElement(modification)
        }
    }

    override suspend fun applyModifications(modifications: List<Modification>): List<ModificationResult> {
        val results = modifications.map { applyModification(it) }
        // Flush PSI/Document changes to disk right away so that shell commands,
        // greps and external tools attached to this turn see the applied state
        // instead of the stale pre-edit files.
        if (results.any { it is ModificationResult.Success }) {
            flushDocumentsToDisk()
        }
        return results
    }

    override suspend fun exists(path: ElementPath): Boolean {
        return runReadAction {
            navigator.findElement(path) != null
        }
    }

    override suspend fun validateSyntax(content: String): Result<Unit, CodeRepositoryError> {
        return runReadAction {
            try {
                val file = elementFactory.createFile(content)
                if (file.children.isEmpty() && content.isNotBlank()) {
                    Result.Failure(CodeRepositoryError.ValidationError("Failed to parse content"))
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

                // All declarations with stub bodies — no implementation noise
                CodeGranularity.SIGNATURES -> {
                    val ktFile = navigator.findFile(ElementPath.file(request.filePath)) as? KtFile
                        ?: error("File not found: ${request.filePath}")
                    renderer.renderSignatures(ktFile)
                }

                // Compact class outline: header, properties, method signatures
                CodeGranularity.OUTLINE -> {
                    val ktFile = navigator.findFile(ElementPath.file(request.filePath)) as? KtFile
                        ?: error("File not found: ${request.filePath}")
                    val allClasses = ktFile.declarations.filterIsInstance<KtClass>()
                    val ktClass = if (request.elementPath != null) {
                        // elementPath for OUTLINE: just the class name, e.g. "SnakeGame"
                        allClasses.firstOrNull { it.name == request.elementPath }
                            ?: error("Class '${request.elementPath}' not found in: ${request.filePath}")
                    } else {
                        // No elementPath — pick the largest class by member count
                        allClasses.maxByOrNull { it.declarations.size }
                            ?: error("No class found in: ${request.filePath}")
                    }
                    renderer.renderOutline(ktClass)
                }

                // Single element resolved by its PSI path segments
                CodeGranularity.ELEMENT -> {
                    val elemPathStr = request.elementPath
                        ?: error("elementPath is required for ELEMENT granularity")
                    val fullPath = ElementPath("file:${request.filePath}/$elemPathStr")
                    val element = navigator.findElement(fullPath)
                        ?: error("Element not found: $elemPathStr in ${request.filePath}")
                    renderer.renderElement(element as KtNamedDeclaration)
                }

                // Flat semantic Find Usages of one element across the whole project
                CodeGranularity.USAGES -> {
                    val elemPathStr = request.elementPath
                        ?: error("elementPath is required for USAGES granularity")
                    val fullPath = ElementPath("file:${request.filePath}/$elemPathStr")
                    val element = navigator.findElement(fullPath)
                        ?: error("Element not found: $elemPathStr in ${request.filePath}")
                    usagesFinder.renderUsages(element, fallbackName = elemPathStr)
                }

                // Multi-level tree of calling functions (upward call hierarchy).
                // Functions only; calls through interfaces/base classes handled by the finder.
                CodeGranularity.CALLERS -> {
                    val elemPathStr = request.elementPath
                        ?: error("elementPath is required for CALLERS granularity")
                    val fullPath = ElementPath("file:${request.filePath}/$elemPathStr")
                    val element = navigator.findElement(fullPath)
                        ?: error("Element not found: $elemPathStr in ${request.filePath}")
                    val function = element as? KtNamedFunction
                        ?: error("CALLERS is only supported for functions: $elemPathStr")
                    callHierarchyFinder.renderCallers(function, fallbackName = elemPathStr)
                }

                // Downward half of the call hierarchy — lands with the next change set.
                CodeGranularity.CALLEES ->
                    error("CALLEES granularity is not implemented yet (${request.filePath})")

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
    // Private implementation - File operations
    // ═══════════════════════════════════════════════════════════════

    private fun createFile(mod: Modification.CreateFile): ModificationResult {
        println("[PsiCodeRepository] Creating file: ${mod.targetPath.value}")
        return try {
            var resultContent: String? = null
            var errorMessage: String? = null
            val app = ApplicationManager.getApplication()
            val action = {
                WriteCommandAction.runWriteCommandAction(project) {
                    // Exceptions must be caught here: invokeAndWait does not
                    // propagate them to the calling thread, so the outer catch
                    // never sees failures from inside the write command.
                    try {
                        val filePath = mod.targetPath.filePath
                        val directory = findOrCreateDirectory(filePath)
                        if (directory == null) {
                            errorMessage = "Could not find or create directory for $filePath"
                            println("[PsiCodeRepository] ERROR: $errorMessage")
                            return@runWriteCommandAction
                        }
                        val fileName = File(filePath).name
                        val psiFile = modifier.createFile(directory, fileName, mod.content)
                        if (psiFile != null) {
                            resultContent = psiFile.text
                            println("[PsiCodeRepository] File created: ${psiFile.virtualFile?.path}")
                        } else {
                            errorMessage = "PSI file creation returned null for $filePath"
                            println("[PsiCodeRepository] ERROR: $errorMessage")
                        }
                    } catch (e: Exception) {
                        errorMessage = "${e.javaClass.simpleName}: ${e.message}"
                        println("[PsiCodeRepository] ERROR creating file: $errorMessage")
                    }
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
                    error = ModificationError.IOError(errorMessage ?: "Failed to create file")
                )
            }
        } catch (e: Exception) {
            println("[PsiCodeRepository] ERROR creating file: ${e.message}")
            ModificationResult.Failure(
                modification = mod,
                error = ModificationError.IOError(e.message ?: "Failed to create file")
            )
        }
    }

    private fun replaceFile(mod: Modification.ReplaceFile): ModificationResult {
        val psiFile = runReadAction { navigator.findFile(mod.targetPath) }
            ?: return ModificationResult.Failure(
                modification = mod,
                error = ModificationError.FileNotFound(mod.targetPath.filePath)
            )
        return try {
            val app = ApplicationManager.getApplication()
            val action = {
                WriteCommandAction.runWriteCommandAction(project) {
                    modifier.replaceFileContent(psiFile, mod.newContent)
                }
            }
            if (app.isDispatchThread) action() else app.invokeAndWait(action)
            ModificationResult.Success(
                modification = mod,
                affectedPath = mod.targetPath,
                resultContent = mod.newContent
            )
        } catch (e: Exception) {
            ModificationResult.Failure(
                modification = mod,
                error = ModificationError.IOError(e.message ?: "Failed to replace file")
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
            var deleted = false
            val app = ApplicationManager.getApplication()
            val action = {
                WriteCommandAction.runWriteCommandAction(project) {
                    deleted = modifier.deleteElement(psiFile)
                }
            }
            if (app.isDispatchThread) action() else app.invokeAndWait(action)
            if (deleted) {
                ModificationResult.Success(modification = mod, affectedPath = mod.targetPath, resultContent = null)
            } else {
                ModificationResult.Failure(
                    modification = mod,
                    error = ModificationError.IOError("PSI file deletion did not complete: ${mod.targetPath.filePath}")
                )
            }
        } catch (e: Exception) {
            ModificationResult.Failure(
                modification = mod,
                error = ModificationError.IOError(e.message ?: "Failed to delete file")
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Private implementation - Element operations
    // ═══════════════════════════════════════════════════════════════

    private fun createElement(mod: Modification.CreateElement): ModificationResult {
        val parent = runReadAction { navigator.findElement(mod.targetPath) }
            ?: return ModificationResult.Failure(
                modification = mod,
                error = ModificationError.ElementNotFound(mod.targetPath)
            )
        return try {
            var resultText: String? = null
            val app = ApplicationManager.getApplication()
            val action = {
                WriteCommandAction.runWriteCommandAction(project) {
                    val added = modifier.addElement(parent, mod.content, mod.elementKind, mod.position)
                    resultText = added?.text
                }
            }
            if (app.isDispatchThread) action() else app.invokeAndWait(action)
            if (resultText != null) {
                ModificationResult.Success(
                    modification = mod,
                    affectedPath = mod.targetPath,
                    resultContent = resultText
                )
            } else {
                ModificationResult.Failure(
                    modification = mod,
                    error = ModificationError.ParseError("Failed to parse: ${mod.content.take(50)}")
                )
            }
        } catch (e: Exception) {
            ModificationResult.Failure(
                modification = mod,
                error = ModificationError.IOError(e.message ?: "Failed to create element")
            )
        }
    }

    private fun replaceElement(mod: Modification.ReplaceElement): ModificationResult {
        val targetSegment = mod.targetPath.segments.lastOrNull()
        if (targetSegment?.kind.equals("constructor", ignoreCase = true) &&
            targetSegment?.name.equals("primary", ignoreCase = true)
        ) {
            return ModificationResult.Failure(
                modification = mod,
                error = ModificationError.InvalidOperation(
                    "REPLACE_ELEMENT does not support primary constructors; use REPLACE_FILE for class-header changes"
                )
            )
        }

        val elementAndKind = runReadAction {
            val element = navigator.findElement(mod.targetPath) ?: return@runReadAction null
            val kind = mapper.inferKind(element) ?: return@runReadAction null
            element to kind
        } ?: return ModificationResult.Failure(
            modification = mod,
            error = ModificationError.ElementNotFound(mod.targetPath)
        )
        val (element, kind) = elementAndKind
        return try {
            var resultText: String? = null
            val app = ApplicationManager.getApplication()
            val action = {
                WriteCommandAction.runWriteCommandAction(project) {
                    val replaced = modifier.replaceElement(element, mod.newContent, kind)
                    resultText = replaced?.text
                }
            }
            if (app.isDispatchThread) action() else app.invokeAndWait(action)
            if (resultText != null) {
                ModificationResult.Success(
                    modification = mod,
                    affectedPath = mod.targetPath,
                    resultContent = resultText
                )
            } else {
                ModificationResult.Failure(
                    modification = mod,
                    error = ModificationError.ParseError("REPLACE_ELEMENT requires exactly one valid declaration")
                )
            }
        } catch (e: Exception) {
            ModificationResult.Failure(
                modification = mod,
                error = ModificationError.IOError(e.message ?: "Failed to replace element")
            )
        }
    }

    private fun deleteElement(mod: Modification.DeleteElement): ModificationResult {
        val element = runReadAction { navigator.findElement(mod.targetPath) }
            ?: return ModificationResult.Failure(
                modification = mod,
                error = ModificationError.ElementNotFound(mod.targetPath)
            )
        return try {
            var deleted = false
            val app = ApplicationManager.getApplication()
            val action = {
                WriteCommandAction.runWriteCommandAction(project) {
                    deleted = modifier.deleteElement(element)
                }
            }
            if (app.isDispatchThread) action() else app.invokeAndWait(action)
            if (deleted) {
                ModificationResult.Success(modification = mod, affectedPath = mod.targetPath, resultContent = null)
            } else {
                ModificationResult.Failure(
                    modification = mod,
                    error = ModificationError.InvalidOperation(
                        "PSI element deletion did not complete: ${mod.targetPath.value}"
                    )
                )
            }
        } catch (e: Exception) {
            ModificationResult.Failure(
                modification = mod,
                error = ModificationError.IOError(e.message ?: "Failed to delete element")
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Private implementation - Import operations
    // ═══════════════════════════════════════════════════════════════

    private fun addImport(mod: Modification.AddImport): ModificationResult {
        val ktFile = runReadAction { navigator.findFile(mod.targetPath) as? KtFile }
            ?: return ModificationResult.Failure(
                modification = mod,
                error = ModificationError.FileNotFound(mod.targetPath.filePath)
            )
        return try {
            val app = ApplicationManager.getApplication()
            val action =
                { WriteCommandAction.runWriteCommandAction(project) { modifier.addImport(ktFile, mod.importPath) } }
            if (app.isDispatchThread) action() else app.invokeAndWait(action)
            ModificationResult.Success(
                modification = mod,
                affectedPath = mod.targetPath,
                resultContent = "import ${mod.importPath}"
            )
        } catch (e: Exception) {
            ModificationResult.Failure(
                modification = mod,
                error = ModificationError.IOError(e.message ?: "Failed to add import")
            )
        }
    }

    private fun removeImport(mod: Modification.RemoveImport): ModificationResult {
        val ktFile = runReadAction { navigator.findFile(mod.targetPath) as? KtFile }
            ?: return ModificationResult.Failure(
                modification = mod,
                error = ModificationError.FileNotFound(mod.targetPath.filePath)
            )
        return try {
            var removed = false
            val app = ApplicationManager.getApplication()
            val action = {
                WriteCommandAction.runWriteCommandAction(project) {
                    removed = modifier.removeImport(ktFile, mod.importPath)
                }
            }
            if (app.isDispatchThread) action() else app.invokeAndWait(action)
            if (removed) {
                ModificationResult.Success(modification = mod, affectedPath = mod.targetPath, resultContent = null)
            } else {
                ModificationResult.Failure(
                    modification = mod,
                    error = ModificationError.InvalidOperation("Import not found: ${mod.importPath}")
                )
            }
        } catch (e: Exception) {
            ModificationResult.Failure(
                modification = mod,
                error = ModificationError.IOError(e.message ?: "Failed to remove import")
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Private implementation - IDE refactorings (Rename / Safe Delete / Move)
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

    // ═══════════════════════════════════════════════════════════════
    // Directory utilities
    // ═══════════════════════════════════════════════════════════════

    private fun findOrCreateDirectory(filePath: String): PsiDirectory? {
        val projectBasePath = project.basePath ?: return null
        val psiManager = PsiManager.getInstance(project)

        val dirPath = File(filePath).parent?.replace("\\", "/") ?: ""

        val existing = VfsUtil.findFileByIoFile(File("$projectBasePath/$dirPath"), true)
        if (existing != null && existing.isDirectory) {
            return psiManager.findDirectory(existing)
        }

        val baseDir = VfsUtil.findFileByIoFile(File(projectBasePath), true) ?: return null
        val basePsiDir = psiManager.findDirectory(baseDir) ?: return null
        return createDirectoryPath(basePsiDir, dirPath)
    }

    private fun createDirectoryPath(baseDir: PsiDirectory, relativePath: String): PsiDirectory {
        if (relativePath.isBlank()) return baseDir

        var currentDir = baseDir
        val parts = relativePath.split("/", "\\").filter { it.isNotBlank() }

        for (part in parts) {
            val existing = currentDir.findSubdirectory(part)
            currentDir = existing ?: currentDir.createSubdirectory(part)
        }

        return currentDir
    }
    private fun flushDocumentsToDisk() {
        ApplicationManager.getApplication().invokeAndWait {
            FileDocumentManager.getInstance().saveAllDocuments()
        }
    }
}
