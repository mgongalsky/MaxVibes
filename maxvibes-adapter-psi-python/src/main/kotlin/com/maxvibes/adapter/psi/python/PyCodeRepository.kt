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
 */
class PyCodeRepository(private val project: Project) : CodeRepository {

    private val mapper = PyPsiToDomainMapper()
    private val navigator = PyPsiNavigator(project)
    private val elementFactory = PythonElementFactory(project)
    private val modifier = PyPsiModifier(project, navigator, elementFactory)

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
        }
    }

    override suspend fun applyModifications(modifications: List<Modification>): List<ModificationResult> {
        return modifications.map { applyModification(it) }
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
}
