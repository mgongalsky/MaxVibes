package com.maxvibes.plugin.files

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.maxvibes.application.port.output.CodeRepository
import com.maxvibes.application.port.output.CodeRepositoryError
import com.maxvibes.domain.model.code.*
import com.maxvibes.domain.model.modification.*
import com.maxvibes.shared.result.Result
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Path

/** Handles whole text files independently of the optional language plugin. */
class FileAwareCodeRepository(project: Project, private val language: CodeRepository) : CodeRepository by language {
    private val files = ProjectTextFiles(project)
    private val mutationLock = Mutex()

    override suspend fun getFileContent(path: ElementPath): Result<String, CodeRepositoryError> = try {
        Result.Success(files.read { files.text(path.filePath) })
    } catch (e: Exception) {
        Result.Failure(CodeRepositoryError.ReadError(e.message ?: "Cannot read ${path.filePath}"))
    }

    override suspend fun getCodeView(request: CodeViewRequest): CodeView {
        if (request.granularity != CodeGranularity.FULL) return language.getCodeView(request)
        return CodeView(request.filePath, request.granularity, files.read { files.text(request.filePath) })
    }

    override suspend fun exists(path: ElementPath): Boolean =
        if (path.isElement) language.exists(path) else files.read { files.find(path.filePath) != null }

    override suspend fun getElement(path: ElementPath): Result<CodeElement, CodeRepositoryError> {
        if (path.isElement) return language.getElement(path)
        val mapped = language.getElement(path)
        if (mapped is Result.Success) return mapped
        return when (val content = getFileContent(path)) {
            is Result.Success -> Result.Success(
                CodeFile(
                    ElementPath.file(path.filePath), path.filePath.replace('\\', '/').substringAfterLast('/'),
                    content.value, null, emptyList(), emptyList()
                )
            )

            is Result.Failure -> content
        }
    }

    override suspend fun applyModification(modification: Modification): ModificationResult =
        applyModifications(listOf(modification)).single()

    override suspend fun applyModifications(modifications: List<Modification>): List<ModificationResult> =
        mutationLock.withLock { applyBatch(modifications) }

    private suspend fun applyBatch(modifications: List<Modification>): List<ModificationResult> {
        if (modifications.isEmpty()) return emptyList()
        // Preserve protocol diagnostics without silently discarding valid neighbouring entries.
        if (modifications.any { it is Modification.Unsupported }) {
            val results = applyBatch(modifications.filterNot { it is Modification.Unsupported }).iterator()
            return modifications.map {
                if (it is Modification.Unsupported) failure(it, it.reason) else results.next()
            }
        }
        val semanticRefactoring = modifications.any {
            it is Modification.SafeDelete ||
                    (it is Modification.RenameElement && it.targetPath.isElement) ||
                    (it is Modification.MoveElement && it.targetPath.isElement)
        }
        if (semanticRefactoring) {
            if (modifications.size != 1) return modifications.map {
                failure(it, "IDE refactorings cannot be mixed with other modifications in one atomic batch")
            }
            // Semantic refactorings can affect references outside the target file.
            return language.applyModifications(modifications)
        }

        val snapshots = try {
            files.read {
                modifications.flatMap { modification ->
                    val source = modification.targetPath.filePath
                    require(!isWholeFileOperation(modification) || !modification.targetPath.isElement) {
                        "Whole-file operation requires a file path: ${modification.targetPath}"
                    }
                    listOfNotNull(source, destination(modification))
                }.map { files.resolve(it).toString() }.distinct().map(files::capture)
            }
        } catch (e: Exception) {
            return modifications.map { failure(it, "No modifications applied: ${e.message}") }
        }

        val results = mutableListOf<ModificationResult>()
        var failedIndex = 0
        try {
            for ((index, modification) in modifications.withIndex()) {
                failedIndex = index
                val result = if (isWholeFileOperation(modification)) {
                    files.write { applyFile(modification) }
                } else {
                    // Retain the language adapter's structural postcondition checks.
                    language.applyModifications(listOf(modification)).single()
                }
                if (result is ModificationResult.Failure) error(result.error.message)
                results += result
            }
            files.write {
                snapshots.forEach { files.save(it.path) }
            }
            return results
        } catch (e: Exception) {
            val rollbackErrors = try {
                files.write { files.restore(snapshots) }
            } catch (restoreError: Exception) {
                listOf(restoreError.message ?: "Restoration did not execute")
            }
            val reason = e.message ?: e.javaClass.simpleName
            return modifications.map {
                val error = if (rollbackErrors.isEmpty()) {
                    ModificationError.BatchRolledBack(failedIndex, reason)
                } else {
                    ModificationError.IOError(
                        "Operation ${failedIndex + 1} failed: $reason. Rollback is incomplete; " +
                                "original state was NOT restored: ${rollbackErrors.joinToString("; ")}"
                    )
                }
                ModificationResult.Failure(it, error)
            }
        }
    }

    private fun isWholeFileOperation(mod: Modification): Boolean = when (mod) {
        is Modification.CreateFile, is Modification.ReplaceFile, is Modification.DeleteFile -> true
        is Modification.RenameElement, is Modification.MoveElement -> !mod.targetPath.isElement
        else -> false
    }

    private fun destination(mod: Modification): String? {
        val source = files.resolve(mod.targetPath.filePath)
        return when (mod) {
            is Modification.RenameElement -> {
                require(
                    mod.newName.isNotBlank() && mod.newName != "." && mod.newName != ".." &&
                            mod.newName.none { it == '/' || it == '\\' || it == ':' }) { "Expected a file name" }
                source.resolveSibling(mod.newName).toString()
            }

            is Modification.MoveElement -> Path.of(mod.destination.replace('\\', '/'))
                .resolve(source.fileName).toString()

            else -> null
        }
    }

    private fun applyFile(mod: Modification): ModificationResult {
        val source = mod.targetPath.filePath
        var affected = source
        var expected: String? = null
        when (mod) {
            is Modification.CreateFile -> {
                files.create(source, mod.content)
                expected = mod.content
            }

            is Modification.ReplaceFile -> {
                files.replace(source, mod.newContent)
                expected = mod.newContent
            }

            is Modification.DeleteFile -> files.delete(source)
            is Modification.RenameElement -> {
                expected = files.text(source)
                affected = files.rename(source, mod.newName)
            }

            is Modification.MoveElement -> {
                expected = files.text(source)
                affected = files.move(source, mod.destination)
            }

            else -> error("Not a whole-file operation")
        }
        if (mod is Modification.DeleteFile || affected != source) {
            check(files.find(source) == null) { "Source file still exists: $source" }
        }
        if (expected != null) {
            check(files.text(affected) == StringUtil.convertLineSeparators(expected)) {
                "File content differs from the request: $affected"
            }
        }
        return ModificationResult.Success(mod, ElementPath.file(affected), expected)
    }

    private fun failure(mod: Modification, message: String): ModificationResult =
        ModificationResult.Failure(mod, ModificationError.InvalidOperation(message))
}
