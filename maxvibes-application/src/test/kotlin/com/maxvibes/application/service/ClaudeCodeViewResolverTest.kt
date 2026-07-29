package com.maxvibes.application.service

import com.maxvibes.application.port.output.ChatMessageDTO
import com.maxvibes.application.port.output.CodeRepository
import com.maxvibes.application.port.output.PromptTemplates
import com.maxvibes.application.testsupport.FakeProjectContextPort
import com.maxvibes.application.testsupport.RecordingNotificationPort
import com.maxvibes.domain.model.code.CodeGranularity
import com.maxvibes.domain.model.code.CodeView
import com.maxvibes.domain.model.code.CodeViewRequest
import com.maxvibes.application.port.output.ContextError
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClaudeCodeViewResolverTest {
    private lateinit var contextProvider: FakeProjectContextPort
    private lateinit var codeRepository: CodeRepository
    private lateinit var notificationPort: RecordingNotificationPort

    @BeforeEach
    fun setUp() {
        contextProvider = FakeProjectContextPort()
        codeRepository = mockk(relaxed = true)
        notificationPort = RecordingNotificationPort()
    }

    @Test
    fun `empty full file list returns empty map without side effects`() = runBlocking {
        val resolver = resolver()
        val state = state()

        val result = resolver.gatherFullFiles(emptyList(), state)

        assertEquals(emptyMap(), result)
        assertTrue(contextProvider.gatheredPathLists.isEmpty())
        assertTrue(notificationPort.progress.isEmpty())
        assertTrue(state.allGatheredFiles.isEmpty())
    }

    @Test
    fun `successful full gather returns files and tracks them in workspace`() = runBlocking {
        contextProvider.fileContents["src/A.kt"] = "class A"
        contextProvider.fileContents["src/B.kt"] = "class B"
        val resolver = resolver()
        val state = state()

        val result = resolver.gatherFullFiles(
            listOf("src/A.kt", "src/B.kt"),
            state
        )

        val expected = mapOf(
            "src/A.kt" to "class A",
            "src/B.kt" to "class B"
        )
        assertEquals(expected, result)
        assertEquals(expected, state.allGatheredFiles)
        assertEquals(
            listOf(listOf("src/A.kt", "src/B.kt")),
            contextProvider.gatheredPathLists
        )
        assertEquals(1, notificationPort.progress.size)
        assertEquals(0.4, notificationPort.progress.single().fraction)
    }

    @Test
    fun `later full gather replaces tracked content for the same path`() = runBlocking {
        contextProvider.fileContents["src/A.kt"] = "old"
        val resolver = resolver()
        val state = state()

        resolver.gatherFullFiles(listOf("src/A.kt"), state)
        contextProvider.fileContents["src/A.kt"] = "new"
        resolver.gatherFullFiles(listOf("src/A.kt"), state)

        assertEquals("new", state.allGatheredFiles["src/A.kt"])
        assertEquals(2, contextProvider.gatheredPathLists.size)
    }

    @Test
    fun `full gather failure returns null and preserves tracked files`() = runBlocking {
        contextProvider.gatherFilesError = ContextError.FileReadError(
            path = "src/Broken.kt",
            details = "boom"
        )
        val resolver = resolver()
        val state = state(
            gatheredFiles = mutableMapOf("src/Existing.kt" to "existing")
        )

        val result = resolver.gatherFullFiles(
            listOf("src/Broken.kt"),
            state
        )

        assertNull(result)
        assertEquals(
            mapOf("src/Existing.kt" to "existing"),
            state.allGatheredFiles
        )
        assertEquals(1, notificationPort.progress.size)
    }

    @Test
    fun `partial request is delegated unchanged and returned under original path`() = runBlocking {
        val request = CodeViewRequest(
            filePath = "src/Foo.kt",
            granularity = CodeGranularity.ELEMENT,
            elementPath = "class[Foo]/function[bar]"
        )
        val view = mockk<CodeView>()
        every { view.content } returns "fun bar() = 1"
        coEvery { codeRepository.getCodeView(request) } returns view
        val resolver = resolver()

        val result = resolver.resolve(listOf(request), state())

        assertEquals(
            mapOf("src/Foo.kt" to "fun bar() = 1"),
            result
        )
        coVerify(exactly = 1) { codeRepository.getCodeView(request) }
        assertTrue(contextProvider.gatheredPathLists.isEmpty())
    }

    @Test
    fun `partial view exception is isolated and other views still resolve`() = runBlocking {
        val broken = CodeViewRequest(
            filePath = "src/Broken.kt",
            granularity = CodeGranularity.SIGNATURES
        )
        val healthy = CodeViewRequest(
            filePath = "src/Healthy.kt",
            granularity = CodeGranularity.OUTLINE
        )
        val healthyView = mockk<CodeView>()
        every { healthyView.content } returns "class Healthy"
        coEvery { codeRepository.getCodeView(broken) } throws IllegalStateException("psi failed")
        coEvery { codeRepository.getCodeView(healthy) } returns healthyView
        val resolver = resolver()

        val result = resolver.resolve(listOf(broken, healthy), state())!!

        assertTrue(result.getValue("src/Broken.kt").contains("psi failed"))
        assertEquals("class Healthy", result["src/Healthy.kt"])
        coVerify(exactly = 1) { codeRepository.getCodeView(broken) }
        coVerify(exactly = 1) { codeRepository.getCodeView(healthy) }
    }

    @Test
    fun `known skill resolves under prefixed key without reading code`() = runBlocking {
        val specificPromptService = mockk<SpecificPromptService>()
        every { specificPromptService.resolveSkillBody("testing") } returns "skill body"
        val resolver = resolver(specificPromptService)
        val request = CodeViewRequest(
            filePath = "testing",
            granularity = CodeGranularity.SKILL
        )

        val result = resolver.resolve(listOf(request), state())

        assertEquals(mapOf("skill:testing" to "skill body"), result)
        verify(exactly = 1) { specificPromptService.resolveSkillBody("testing") }
        coVerify(exactly = 0) { codeRepository.getCodeView(any()) }
        assertTrue(contextProvider.gatheredPathLists.isEmpty())
    }

    @Test
    fun `unknown skill produces explicit error content`() = runBlocking {
        val specificPromptService = mockk<SpecificPromptService>()
        every { specificPromptService.resolveSkillBody("missing") } returns null
        val resolver = resolver(specificPromptService)
        val request = CodeViewRequest(
            filePath = "missing",
            granularity = CodeGranularity.SKILL
        )

        val result = resolver.resolve(listOf(request), state())!!

        assertTrue(result.getValue("skill:missing").contains("Unknown skill 'missing'"))
    }

    @Test
    fun `mixed full partial and skill requests combine all sources`() = runBlocking {
        contextProvider.fileContents["src/Full.kt"] = "full body"
        val partialRequest = CodeViewRequest(
            filePath = "src/Partial.kt",
            granularity = CodeGranularity.SIGNATURES
        )
        val partialView = mockk<CodeView>()
        every { partialView.content } returns "partial body"
        coEvery { codeRepository.getCodeView(partialRequest) } returns partialView
        val specificPromptService = mockk<SpecificPromptService>()
        every { specificPromptService.resolveSkillBody("review") } returns "skill body"
        val resolver = resolver(specificPromptService)
        val requests = listOf(
            CodeViewRequest("src/Full.kt", CodeGranularity.FULL),
            partialRequest,
            CodeViewRequest("review", CodeGranularity.SKILL)
        )
        val state = state()

        val result = resolver.resolve(requests, state)

        assertEquals(
            mapOf(
                "src/Full.kt" to "full body",
                "src/Partial.kt" to "partial body",
                "skill:review" to "skill body"
            ),
            result
        )
        assertEquals(listOf(listOf("src/Full.kt")), contextProvider.gatheredPathLists)
        assertEquals("full body", state.allGatheredFiles["src/Full.kt"])
        coVerify(exactly = 1) { codeRepository.getCodeView(partialRequest) }
        verify(exactly = 1) { specificPromptService.resolveSkillBody("review") }
    }

    private fun resolver(
        specificPromptService: SpecificPromptService? = null
    ) = ClaudeCodeViewResolver(
        contextProvider = contextProvider,
        codeRepository = codeRepository,
        specificPromptService = specificPromptService,
        notificationPort = notificationPort
    )

    private fun state(
        gatheredFiles: MutableMap<String, String> = mutableMapOf()
    ) = ClipboardSessionState(
        currentMessage = "task",
        projectContext = FakeProjectContextPort.defaultContext(),
        dialogHistory = mutableListOf<ChatMessageDTO>(),
        prompts = PromptTemplates(
            chatSystem = "system",
            planningSystem = "system"
        ),
        allGatheredFiles = gatheredFiles,
        planOnly = false
    )
}
