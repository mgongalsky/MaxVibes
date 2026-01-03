package com.maxvibes.application.port.input

import com.maxvibes.domain.model.code.ElementPath
import com.maxvibes.domain.model.modification.ModificationResult

/**
 * Use Case: модификация кода через LLM
 */
interface ModifyCodeUseCase {

    suspend fun execute(request: ModifyCodeRequest): ModifyCodeResponse
}

data class ModifyCodeRequest(
    val instruction: String,
    val targetPath: ElementPath,
    val includeRelatedFiles: Boolean = false
)

data class ModifyCodeResponse(
    val success: Boolean,
    val results: List<ModificationResult>,
    val summary: String
)