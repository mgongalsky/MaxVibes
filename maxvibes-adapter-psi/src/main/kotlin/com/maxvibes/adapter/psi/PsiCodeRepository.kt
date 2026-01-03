package com.maxvibes.adapter.psi

import com.intellij.openapi.project.Project
import com.maxvibes.adapter.psi.kotlin.KotlinElementFactory
import com.maxvibes.adapter.psi.mapper.PsiToDomainMapper
import com.maxvibes.adapter.psi.operation.PsiModifier
import com.maxvibes.adapter.psi.operation.PsiNavigator
import com.maxvibes.application.port.output.CodeRepository
import com.maxvibes.application.port.output.CodeRepositoryError
import com.maxvibes.domain.model.code.CodeElement
import com.maxvibes.domain.model.code.ElementKind
import com.maxvibes.domain.model.code.ElementPath
import com.maxvibes.domain.model.modification.*
import com.maxvibes.shared.result.Result
import org.jetbrains.kotlin.psi.KtFile

/**
 * Реализация CodeRepository через IntelliJ PSI
 */
class PsiCodeRepository(private val project: Project) : CodeRepository {

    private val mapper = PsiToDomainMapper()
    private val navigator = PsiNavigator(project)
    private val elementFactory = KotlinElementFactory(project)
    private val modifier = PsiModifier(project, elementFactory)

    override suspend fun getFileContent(path: ElementPath): Result<String, CodeRepositoryError> {
        val psiFile = navigator.findFile(path)
            ?: return Result.Failure(CodeRepositoryError.NotFound(path.filePath))

        return Result.Success(psiFile.text)
    }

    override suspend fun getElement(path: ElementPath): Result<CodeElement, CodeRepositoryError> {
        val psiElement = navigator.findElement(path)
            ?: return Result.Failure(CodeRepositoryError.NotFound(path.value))

        return try {
            val basePath = project.basePath ?: ""
            val codeElement = when (psiElement) {
                is KtFile -> mapper.mapFile(psiElement, basePath)
                else -> mapper.mapDeclaration(psiElement, path.parentPath ?: path)
                    ?: return Result.Failure(CodeRepositoryError.ReadError("Cannot map element"))
            }
            Result.Success(codeElement)
        } catch (e: Exception) {
            Result.Failure(CodeRepositoryError.ReadError(e.message ?: "Unknown error"))
        }
    }

    override suspend fun findElements(
        basePath: ElementPath,
        kinds: Set<ElementKind>?,
        namePattern: Regex?
    ): Result<List<CodeElement>, CodeRepositoryError> {
        val rootElement = navigator.findElement(basePath)
            ?: return Result.Failure(CodeRepositoryError.NotFound(basePath.value))

        val children = navigator.getChildren(rootElement)
        val projectBasePath = project.basePath ?: ""

        val mapped = children.mapNotNull { child ->
            mapper.mapDeclaration(child, basePath)
        }.filter { element ->
            (kinds == null || element.kind in kinds) &&
                    (namePattern == null || namePattern.matches(element.name))
        }

        return Result.Success(mapped)
    }

    override suspend fun applyModification(modification: Modification): ModificationResult {
        return when (modification) {
            is Modification.CreateFile -> createFile(modification)
            is Modification.ReplaceFile -> replaceFile(modification)
            is Modification.DeleteFile -> deleteFile(modification)
            is Modification.CreateElement -> createElement(modification)
            is Modification.ReplaceElement -> replaceElement(modification)
            is Modification.DeleteElement -> deleteElement(modification)
        }
    }

    override suspend fun applyModifications(modifications: List<Modification>): List<ModificationResult> {
        return modifications.map { applyModification(it) }
    }

    override suspend fun exists(path: ElementPath): Boolean {
        return navigator.findElement(path) != null
    }

    override suspend fun validateSyntax(content: String): Result<Unit, CodeRepositoryError> {
        return try {
            val file = elementFactory.createFile(content)
            // Проверяем что нет синтаксических ошибок
            if (file.children.isEmpty() && content.isNotBlank()) {
                Result.Failure(CodeRepositoryError.ValidationError("Failed to parse content"))
            } else {
                Result.Success(Unit)
            }
        } catch (e: Exception) {
            Result.Failure(CodeRepositoryError.ValidationError(e.message ?: "Parse error"))
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Private implementation
    // ═══════════════════════════════════════════════════════════════

    private fun createFile(mod: Modification.CreateFile): ModificationResult {
        // Для MVP просто создаём файл в памяти
        // В реальности нужно создать через VirtualFile API
        return try {
            val psiFile = elementFactory.createFile(mod.targetPath.name, mod.content)
            ModificationResult.Success(
                modification = mod,
                affectedPath = mod.targetPath,
                resultContent = psiFile.text
            )
        } catch (e: Exception) {
            ModificationResult.Failure(
                modification = mod,
                error = ModificationError.IOError(e.message ?: "Failed to create file")
            )
        }
    }

    private fun replaceFile(mod: Modification.ReplaceFile): ModificationResult {
        val psiFile = navigator.findFile(mod.targetPath)
            ?: return ModificationResult.Failure(
                modification = mod,
                error = ModificationError.FileNotFound(mod.targetPath.filePath)
            )

        return try {
            modifier.replaceFileContent(psiFile, mod.newContent)
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
        val psiFile = navigator.findFile(mod.targetPath)
            ?: return ModificationResult.Failure(
                modification = mod,
                error = ModificationError.FileNotFound(mod.targetPath.filePath)
            )

        return try {
            modifier.deleteElement(psiFile)
            ModificationResult.Success(
                modification = mod,
                affectedPath = mod.targetPath,
                resultContent = null
            )
        } catch (e: Exception) {
            ModificationResult.Failure(
                modification = mod,
                error = ModificationError.IOError(e.message ?: "Failed to delete file")
            )
        }
    }

    private fun createElement(mod: Modification.CreateElement): ModificationResult {
        val parent = navigator.findElement(mod.targetPath)
            ?: return ModificationResult.Failure(
                modification = mod,
                error = ModificationError.ElementNotFound(mod.targetPath)
            )

        return try {
            val added = modifier.addElement(parent, mod.content, mod.elementKind, mod.position)
                ?: return ModificationResult.Failure(
                    modification = mod,
                    error = ModificationError.ParseError("Failed to parse: ${mod.content.take(50)}")
                )

            ModificationResult.Success(
                modification = mod,
                affectedPath = mod.targetPath,
                resultContent = added.text
            )
        } catch (e: Exception) {
            ModificationResult.Failure(
                modification = mod,
                error = ModificationError.IOError(e.message ?: "Failed to create element")
            )
        }
    }

    private fun replaceElement(mod: Modification.ReplaceElement): ModificationResult {
        val element = navigator.findElement(mod.targetPath)
            ?: return ModificationResult.Failure(
                modification = mod,
                error = ModificationError.ElementNotFound(mod.targetPath)
            )

        return try {
            val kind = mapper.inferKind(element)
            val replaced = modifier.replaceElement(element, mod.newContent, kind)
                ?: return ModificationResult.Failure(
                    modification = mod,
                    error = ModificationError.ParseError("Failed to parse replacement")
                )

            ModificationResult.Success(
                modification = mod,
                affectedPath = mod.targetPath,
                resultContent = replaced.text
            )
        } catch (e: Exception) {
            ModificationResult.Failure(
                modification = mod,
                error = ModificationError.IOError(e.message ?: "Failed to replace element")
            )
        }
    }

    private fun deleteElement(mod: Modification.DeleteElement): ModificationResult {
        val element = navigator.findElement(mod.targetPath)
            ?: return ModificationResult.Failure(
                modification = mod,
                error = ModificationError.ElementNotFound(mod.targetPath)
            )

        return try {
            modifier.deleteElement(element)
            ModificationResult.Success(
                modification = mod,
                affectedPath = mod.targetPath,
                resultContent = null
            )
        } catch (e: Exception) {
            ModificationResult.Failure(
                modification = mod,
                error = ModificationError.IOError(e.message ?: "Failed to delete element")
            )
        }
    }
}