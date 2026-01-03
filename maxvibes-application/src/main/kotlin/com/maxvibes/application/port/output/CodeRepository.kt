package com.maxvibes.application.port.output

import com.maxvibes.domain.model.code.CodeElement
import com.maxvibes.domain.model.code.ElementKind
import com.maxvibes.domain.model.code.ElementPath
import com.maxvibes.domain.model.modification.Modification
import com.maxvibes.domain.model.modification.ModificationResult
import com.maxvibes.shared.result.Result

/**
 * Порт для работы с кодом (реализуется PSI адаптером)
 */
interface CodeRepository {

    // Чтение
    suspend fun getFileContent(path: ElementPath): Result<String, CodeRepositoryError>

    suspend fun getElement(path: ElementPath): Result<CodeElement, CodeRepositoryError>

    suspend fun findElements(
        basePath: ElementPath,
        kinds: Set<ElementKind>? = null,
        namePattern: Regex? = null
    ): Result<List<CodeElement>, CodeRepositoryError>

    // Запись
    suspend fun applyModification(modification: Modification): ModificationResult

    suspend fun applyModifications(modifications: List<Modification>): List<ModificationResult>

    // Проверки
    suspend fun exists(path: ElementPath): Boolean

    suspend fun validateSyntax(content: String): Result<Unit, CodeRepositoryError>
}

sealed class CodeRepositoryError(val message: String) {
    class NotFound(path: String) : CodeRepositoryError("Not found: $path")
    class ReadError(details: String) : CodeRepositoryError("Read error: $details")
    class WriteError(details: String) : CodeRepositoryError("Write error: $details")
    class ValidationError(details: String) : CodeRepositoryError("Validation error: $details")
}