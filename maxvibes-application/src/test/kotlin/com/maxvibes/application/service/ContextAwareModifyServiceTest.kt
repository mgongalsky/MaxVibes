package com.maxvibes.application.service

import com.maxvibes.application.port.input.ContextAwareRequest
import com.maxvibes.application.port.output.*
import com.maxvibes.domain.model.code.ElementPath
import com.maxvibes.domain.model.context.*
import com.maxvibes.domain.model.modification.Modification
import com.maxvibes.domain.model.modification.ModificationResult
import com.maxvibes.shared.result.Result
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ContextAwareModifyServiceTest {

    private lateinit var contextProvider: ProjectContextPort
    private lateinit var llmService: LLMService
    private lateinit var codeRepository: CodeRepository
    private lateinit var notificationPort: NotificationPort
    private lateinit var service: ContextAwareModifyService

    private val testFileTree = FileTree(
        root = FileNode("project", "/project", true, listOf(
            FileNode("src", "/project/src", true, listOf(
                FileNode("Main.kt", "/project/src/Main.kt", false)
            ))
        )),
        totalFiles = 1,
        totalDirectories = 2
    )

    private val testProjectContext = ProjectContext(
        name = "TestProject",
        rootPath = "/project",
        description = "Test",
        architecture = null,
        fileTree = testFileTree,
        techStack = TechStack()
    )

    @BeforeEach
    fun setup() {
        contextProvider = mockk()
        llmService = mockk()
        codeRepository = mockk()
        notificationPort = mockk(relaxed = true)

        service = ContextAwareModifyService(
            contextProvider = contextProvider,
            llmService = llmService,
            codeRepository = codeRepository,
            notificationPort = notificationPort
        )
    }

    @Test
    fun `execute should complete successfully with all phases`() = runBlocking {
        // Given
        val request = ContextAwareRequest(task = "Add logging")

        coEvery { contextProvider.getProjectContext() } returns Result.Success(testProjectContext)

        coEvery { llmService.planContext(any(), any()) } returns Result.Success(
            ContextRequest(
                requestedFiles = listOf("src/Main.kt"),
                reasoning = "Need main file"
            )
        )

        coEvery { contextProvider.gatherFiles(any(), any()) } returns Result.Success(
            GatheredContext(
                files = mapOf("src/Main.kt" to "fun main() {}"),
                totalTokensEstimate = 10
            )
        )

        val modification = Modification.CreateFile(
            targetPath = ElementPath("src/Logger.kt"),
            content = "class Logger {}"
        )

        coEvery { llmService.generateModifications(any<String>(), any<GatheredContext>(), any()) } returns
                Result.Success(listOf(modification))

        coEvery { codeRepository.applyModifications(any()) } returns listOf(
            ModificationResult.Success(modification, ElementPath("src/Logger.kt"), "class Logger {}")
        )

        // When
        val result = service.execute(request)

        // Then
        assertTrue(result.success)
        assertEquals(1, result.requestedFiles.size)
        assertEquals(1, result.gatheredFiles.size)
        assertEquals(1, result.modifications.size)
        assertNull(result.error)

        coVerify { contextProvider.getProjectContext() }
        coVerify { llmService.planContext("Add logging", testProjectContext) }
        coVerify { contextProvider.gatherFiles(listOf("src/Main.kt"), any()) }
    }

    @Test
    fun `execute should fail when project context fails`() = runBlocking {
        // Given
        val request = ContextAwareRequest(task = "Add logging")

        coEvery { contextProvider.getProjectContext() } returns
                Result.Failure(ContextError.ProjectNotFound())

        // When
        val result = service.execute(request)

        // Then
        assertFalse(result.success)
        assertTrue(result.error?.contains("project context") == true)
        assertTrue(result.modifications.isEmpty())
    }

    @Test
    fun `execute should fail when planning fails`() = runBlocking {
        // Given
        val request = ContextAwareRequest(task = "Add logging")

        coEvery { contextProvider.getProjectContext() } returns Result.Success(testProjectContext)
        coEvery { llmService.planContext(any(), any()) } returns
                Result.Failure(LLMError.NetworkError("Connection timeout"))

        // When
        val result = service.execute(request)

        // Then
        assertFalse(result.success)
        assertTrue(result.error?.contains("Planning failed") == true)
    }

    @Test
    fun `execute should merge additional files with requested files`() = runBlocking {
        // Given
        val request = ContextAwareRequest(
            task = "Add logging",
            additionalFiles = listOf("config/settings.kt")
        )

        coEvery { contextProvider.getProjectContext() } returns Result.Success(testProjectContext)

        coEvery { llmService.planContext(any(), any()) } returns Result.Success(
            ContextRequest(requestedFiles = listOf("src/Main.kt"), reasoning = null)
        )

        coEvery { contextProvider.gatherFiles(any(), any()) } returns Result.Success(
            GatheredContext(files = mapOf("src/Main.kt" to "code"), totalTokensEstimate = 10)
        )

        coEvery { llmService.generateModifications(any<String>(), any<GatheredContext>(), any()) } returns
                Result.Success(emptyList())

        coEvery { codeRepository.applyModifications(any()) } returns emptyList()

        // When
        val result = service.execute(request)

        // Then
        assertTrue(result.requestedFiles.contains("src/Main.kt"))
        assertTrue(result.requestedFiles.contains("config/settings.kt"))

        coVerify {
            contextProvider.gatherFiles(
                match { it.contains("src/Main.kt") && it.contains("config/settings.kt") },
                any()
            )
        }
    }

    @Test
    fun `execute should exclude files from requested list`() = runBlocking {
        // Given
        val request = ContextAwareRequest(
            task = "Add logging",
            excludeFiles = listOf("src/Main.kt")
        )

        coEvery { contextProvider.getProjectContext() } returns Result.Success(testProjectContext)

        coEvery { llmService.planContext(any(), any()) } returns Result.Success(
            ContextRequest(requestedFiles = listOf("src/Main.kt", "src/Utils.kt"), reasoning = null)
        )

        coEvery { contextProvider.gatherFiles(any(), any()) } returns Result.Success(
            GatheredContext(files = mapOf("src/Utils.kt" to "code"), totalTokensEstimate = 10)
        )

        coEvery { llmService.generateModifications(any<String>(), any<GatheredContext>(), any()) } returns
                Result.Success(emptyList())

        coEvery { codeRepository.applyModifications(any()) } returns emptyList()

        // When
        val result = service.execute(request)

        // Then
        assertFalse(result.requestedFiles.contains("src/Main.kt"))
        assertTrue(result.requestedFiles.contains("src/Utils.kt"))
    }

    @Test
    fun `execute with dryRun should not generate or apply modifications`() = runBlocking {
        // Given
        val request = ContextAwareRequest(task = "Add logging", dryRun = true)

        coEvery { contextProvider.getProjectContext() } returns Result.Success(testProjectContext)

        coEvery { llmService.planContext(any(), any()) } returns Result.Success(
            ContextRequest(requestedFiles = listOf("src/Main.kt"), reasoning = "Test reasoning")
        )

        coEvery { contextProvider.gatherFiles(any(), any()) } returns Result.Success(
            GatheredContext(files = mapOf("src/Main.kt" to "code"), totalTokensEstimate = 10)
        )

        // When
        val result = service.execute(request)

        // Then
        assertTrue(result.success)
        assertEquals("Test reasoning", result.planningReasoning)
        assertTrue(result.modifications.isEmpty())

        coVerify(exactly = 0) { llmService.generateModifications(any<String>(), any<GatheredContext>(), any()) }
        coVerify(exactly = 0) { codeRepository.applyModifications(any()) }
    }

    @Test
    fun `execute should report partial failure when some modifications fail`() = runBlocking {
        // Given
        val request = ContextAwareRequest(task = "Add files")

        coEvery { contextProvider.getProjectContext() } returns Result.Success(testProjectContext)
        coEvery { llmService.planContext(any(), any()) } returns Result.Success(
            ContextRequest(requestedFiles = emptyList(), reasoning = null)
        )
        coEvery { contextProvider.gatherFiles(any(), any()) } returns Result.Success(
            GatheredContext(files = emptyMap(), totalTokensEstimate = 0)
        )

        val mod1 = Modification.CreateFile(ElementPath("src/A.kt"), "class A")
        val mod2 = Modification.CreateFile(ElementPath("src/B.kt"), "class B")

        coEvery { llmService.generateModifications(any<String>(), any<GatheredContext>(), any()) } returns
                Result.Success(listOf(mod1, mod2))

        coEvery { codeRepository.applyModifications(any()) } returns listOf(
            ModificationResult.Success(mod1, ElementPath("src/A.kt"), "class A"),
            ModificationResult.Failure(mod2, com.maxvibes.domain.model.modification.ModificationError.IOError("Write failed"))
        )

        // When
        val result = service.execute(request)

        // Then
        assertFalse(result.success)
        assertEquals(2, result.modifications.size)
        assertTrue(result.error?.contains("1 modifications failed") == true)
    }
}