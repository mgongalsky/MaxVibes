package com.maxvibes.adapter.psi

import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.command.WriteCommandAction
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
            val projectBasePath = project.basePath ?: ""

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
        }
    }

    override suspend fun applyModifications(modifications: List<Modification>): List<ModificationResult> {
        return modifications.map { applyModification(it) }
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
    }

    // ═══════════════════════════════════════════════════════════════
    // Private implementation - Write operations
    // ═══════════════════════════════════════════════════════════════

    private fun createFile(mod: Modification.CreateFile): ModificationResult {
        return runReadAction {
            try {
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
    }

    private fun replaceFile(mod: Modification.ReplaceFile): ModificationResult {
        val psiFile = runReadAction { navigator.findFile(mod.targetPath) }
            ?: return ModificationResult.Failure(
                modification = mod,
                error = ModificationError.FileNotFound(mod.targetPath.filePath)
            )

        return try {
            WriteCommandAction.runWriteCommandAction(project) {
                modifier.replaceFileContent(psiFile, mod.newContent)
            }
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
            WriteCommandAction.runWriteCommandAction(project) {
                modifier.deleteElement(psiFile)
            }
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
        val parent = runReadAction { navigator.findElement(mod.targetPath) }
            ?: return ModificationResult.Failure(
                modification = mod,
                error = ModificationError.ElementNotFound(mod.targetPath)
            )

        return try {
            var resultText: String? = null
            WriteCommandAction.runWriteCommandAction(project) {
                val added = modifier.addElement(parent, mod.content, mod.elementKind, mod.position)
                resultText = added?.text
            }

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
        val elementAndKind = runReadAction {
            val element = navigator.findElement(mod.targetPath) ?: return@runReadAction null
            val kind = mapper.inferKind(element)
            element to kind
        } ?: return ModificationResult.Failure(
            modification = mod,
            error = ModificationError.ElementNotFound(mod.targetPath)
        )

        val (element, kind) = elementAndKind

        return try {
            var resultText: String? = null
            WriteCommandAction.runWriteCommandAction(project) {
                val replaced = modifier.replaceElement(element, mod.newContent, kind)
                resultText = replaced?.text
            }

            if (resultText != null) {
                ModificationResult.Success(
                    modification = mod,
                    affectedPath = mod.targetPath,
                    resultContent = resultText
                )
            } else {
                ModificationResult.Failure(
                    modification = mod,
                    error = ModificationError.ParseError("Failed to parse replacement")
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
            WriteCommandAction.runWriteCommandAction(project) {
                modifier.deleteElement(element)
            }
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