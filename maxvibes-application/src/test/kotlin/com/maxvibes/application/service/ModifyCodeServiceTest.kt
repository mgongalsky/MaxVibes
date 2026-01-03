package com.maxvibes.application.service

import com.maxvibes.application.port.input.ModifyCodeRequest
import com.maxvibes.application.port.output.*
import com.maxvibes.domain.model.code.*
import com.maxvibes.domain.model.modification.Modification
import com.maxvibes.domain.model.modification.ModificationResult
import com.maxvibes.shared.result.Result
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ModifyCodeServiceTest {

    private lateinit var codeRepository: CodeRepository
    private lateinit var llmService: LLMService
    private lateinit var notificationPort: NotificationPort
    private lateinit var service: ModifyCodeService

    @BeforeEach
    fun setup() {
        codeRepository = mockk(relaxed = true)
        llmService = mockk(relaxed = true)
        notificationPort = mockk(relaxed = true)
        service = ModifyCodeService(codeRepository, llmService, notificationPort)
    }

    @Test
    fun `execute should return failure when code not found`() = runBlocking {
        // Given
        val request = ModifyCodeRequest(
            instruction = "add function",
            targetPath = ElementPath.file("NonExistent.kt")
        )
        coEvery { codeRepository.getElement(any()) } returns
                Result.Failure(CodeRepositoryError.NotFound("NonExistent.kt"))

        // When
        val response = service.execute(request)

        // Then
        assertFalse(response.success)
        assertTrue(response.summary.contains("Failed to read code"))
        verify { notificationPort.showError(any()) }
    }

    @Test
    fun `execute should return failure when LLM fails`() = runBlocking {
        // Given
        val request = ModifyCodeRequest(
            instruction = "add function",
            targetPath = ElementPath.file("Test.kt")
        )
        val codeFile = createMockCodeFile()

        coEvery { codeRepository.getElement(any()) } returns Result.Success(codeFile)
        coEvery { llmService.generateModifications(any(), any()) } returns
                Result.Failure(LLMError.NetworkError("Connection refused"))

        // When
        val response = service.execute(request)

        // Then
        assertFalse(response.success)
        assertTrue(response.summary.contains("LLM error"))
    }

    @Test
    fun `execute should return success when no modifications needed`() = runBlocking {
        // Given
        val request = ModifyCodeRequest(
            instruction = "do nothing",
            targetPath = ElementPath.file("Test.kt")
        )
        val codeFile = createMockCodeFile()

        coEvery { codeRepository.getElement(any()) } returns Result.Success(codeFile)
        coEvery { llmService.generateModifications(any(), any()) } returns Result.Success(emptyList())

        // When
        val response = service.execute(request)

        // Then
        assertTrue(response.success)
        assertTrue(response.results.isEmpty())
        verify { notificationPort.showWarning(any()) }
    }

    @Test
    fun `execute should apply modifications and return success`() = runBlocking {
        // Given
        val request = ModifyCodeRequest(
            instruction = "add toString",
            targetPath = ElementPath.file("Test.kt")
        )
        val codeFile = createMockCodeFile()
        val modification = Modification.CreateElement(
            targetPath = ElementPath.file("Test.kt"),
            elementKind = ElementKind.FUNCTION,
            content = "fun toString() = \"Test\""
        )
        val modResult = ModificationResult.Success(
            modification = modification,
            affectedPath = ElementPath.file("Test.kt"),
            resultContent = "fun toString() = \"Test\""
        )

        coEvery { codeRepository.getElement(any()) } returns Result.Success(codeFile)
        coEvery { llmService.generateModifications(any(), any()) } returns Result.Success(listOf(modification))
        coEvery { codeRepository.applyModifications(any()) } returns listOf(modResult)

        // When
        val response = service.execute(request)

        // Then
        assertTrue(response.success)
        assertEquals(1, response.results.size)
        verify { notificationPort.showSuccess(any()) }
    }

    @Test
    fun `execute should report partial failure`() = runBlocking {
        // Given
        val request = ModifyCodeRequest(
            instruction = "add functions",
            targetPath = ElementPath.file("Test.kt")
        )
        val codeFile = createMockCodeFile()
        val mod1 = Modification.CreateElement(
            targetPath = ElementPath.file("Test.kt"),
            elementKind = ElementKind.FUNCTION,
            content = "fun func1() {}"
        )
        val mod2 = Modification.CreateElement(
            targetPath = ElementPath.file("Test.kt"),
            elementKind = ElementKind.FUNCTION,
            content = "invalid syntax {{{"
        )

        coEvery { codeRepository.getElement(any()) } returns Result.Success(codeFile)
        coEvery { llmService.generateModifications(any(), any()) } returns Result.Success(listOf(mod1, mod2))
        coEvery { codeRepository.applyModifications(any()) } returns listOf(
            ModificationResult.Success(mod1, ElementPath.file("Test.kt"), "fun func1() {}"),
            ModificationResult.Failure(mod2, com.maxvibes.domain.model.modification.ModificationError.ParseError("Syntax error"))
        )

        // When
        val response = service.execute(request)

        // Then
        assertFalse(response.success)
        assertEquals(2, response.results.size)
        verify { notificationPort.showWarning(any()) }
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