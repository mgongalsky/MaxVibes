package com.maxvibes.application.service

import com.maxvibes.application.port.input.AnalyzeCodeRequest
import com.maxvibes.application.port.output.*
import com.maxvibes.domain.model.code.*
import com.maxvibes.shared.result.Result
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class AnalyzeCodeServiceTest {

    private lateinit var codeRepository: CodeRepository
    private lateinit var llmService: LLMService
    private lateinit var notificationPort: NotificationPort
    private lateinit var service: AnalyzeCodeService

    @BeforeEach
    fun setup() {
        codeRepository = mockk(relaxed = true)
        llmService = mockk(relaxed = true)
        notificationPort = mockk(relaxed = true)
        service = AnalyzeCodeService(codeRepository, llmService, notificationPort)
    }

    @Test
    fun `execute should return failure when code not found`() = runBlocking {
        // Given
        val request = AnalyzeCodeRequest(
            question = "What does this do?",
            targetPath = ElementPath.file("Missing.kt")
        )
        coEvery { codeRepository.getElement(any()) } returns
                Result.Failure(CodeRepositoryError.NotFound("Missing.kt"))

        // When
        val response = service.execute(request)

        // Then
        assertFalse(response.success)
        assertTrue(response.answer.contains("Failed to read code"))
    }

    @Test
    fun `execute should return failure when LLM fails`() = runBlocking {
        // Given
        val request = AnalyzeCodeRequest(
            question = "Explain this code",
            targetPath = ElementPath.file("Test.kt")
        )
        val codeFile = createMockCodeFile()

        coEvery { codeRepository.getElement(any()) } returns Result.Success(codeFile)
        coEvery { llmService.analyzeCode(any(), any()) } returns
                Result.Failure(LLMError.RateLimitError())

        // When
        val response = service.execute(request)

        // Then
        assertFalse(response.success)
        assertTrue(response.answer.contains("LLM error"))
    }

    @Test
    fun `execute should return analysis result on success`() = runBlocking {
        // Given
        val request = AnalyzeCodeRequest(
            question = "What patterns are used?",
            targetPath = ElementPath.file("Test.kt")
        )
        val codeFile = createMockCodeFile()
        val analysisResponse = AnalysisResponse(
            answer = "This code uses Factory pattern",
            suggestions = listOf("Consider using Builder pattern too")
        )

        coEvery { codeRepository.getElement(any()) } returns Result.Success(codeFile)
        coEvery { llmService.analyzeCode(any(), any()) } returns Result.Success(analysisResponse)

        // When
        val response = service.execute(request)

        // Then
        assertTrue(response.success)
        assertEquals("This code uses Factory pattern", response.answer)
        assertEquals(1, response.suggestions.size)
        verify { notificationPort.showSuccess("Analysis complete") }
    }

    private fun createMockCodeFile(): CodeFile {
        return CodeFile(
            path = ElementPath.file("Test.kt"),
            name = "Test.kt",
            content = "class Test {}",
            packageName = "com.test",
            imports = emptyList(),
            children = emptyList()
        )
    }
}