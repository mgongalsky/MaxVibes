package com.maxvibes.application.testsupport

import com.maxvibes.application.port.output.CodeRepository
import com.maxvibes.application.port.output.CodeRepositoryError
import com.maxvibes.domain.model.code.CodeElement
import com.maxvibes.domain.model.code.CodeView
import com.maxvibes.domain.model.code.CodeViewRequest
import com.maxvibes.domain.model.code.ElementKind
import com.maxvibes.domain.model.code.ElementPath
import com.maxvibes.domain.model.modification.Modification
import com.maxvibes.domain.model.modification.ModificationResult
import com.maxvibes.shared.result.Result

/**
 * Fake [CodeRepository] that records every applied modification batch and
 * succeeds by default. Override [modificationOutcome] to simulate failures.
 *
 * [getCodeView] throws: scenario tests must only use FULL-granularity views,
 * which are served by the context port, never by the code repository.
 */
class FakeCodeRepository : CodeRepository {

    /** Every batch passed to [applyModification]/[applyModifications], in order. */
    val appliedBatches = mutableListOf<List<Modification>>()

    var modificationOutcome: (Modification) -> ModificationResult = { mod ->
        ModificationResult.Success(mod, mod.targetPath, null)
    }

    override suspend fun getFileContent(path: ElementPath): Result<String, CodeRepositoryError> =
        Result.Failure(CodeRepositoryError.NotFound(path.toString()))

    override suspend fun getElement(path: ElementPath): Result<CodeElement, CodeRepositoryError> =
        Result.Failure(CodeRepositoryError.NotFound(path.toString()))

    override suspend fun findElements(
        basePath: ElementPath,
        kinds: Set<ElementKind>?,
        namePattern: Regex?
    ): Result<List<CodeElement>, CodeRepositoryError> = Result.Success(emptyList())

    override suspend fun applyModification(modification: Modification): ModificationResult {
        appliedBatches += listOf(modification)
        return modificationOutcome(modification)
    }

    override suspend fun applyModifications(modifications: List<Modification>): List<ModificationResult> {
        appliedBatches += modifications
        return modifications.map { modificationOutcome(it) }
    }

    override suspend fun exists(path: ElementPath): Boolean = false

    override suspend fun validateSyntax(content: String): Result<Unit, CodeRepositoryError> =
        Result.Success(Unit)

    override suspend fun getCodeView(request: CodeViewRequest): CodeView =
        throw UnsupportedOperationException(
            "FakeCodeRepository.getCodeView must not be called — use FULL granularity in scenarios"
        )
}
