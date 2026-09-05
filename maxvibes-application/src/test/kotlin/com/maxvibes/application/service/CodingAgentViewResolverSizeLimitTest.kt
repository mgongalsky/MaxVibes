package com.maxvibes.application.service

import com.maxvibes.application.port.output.ContextError
import com.maxvibes.application.port.output.ProjectContextPort
import com.maxvibes.domain.model.code.CodeGranularity
import com.maxvibes.domain.model.code.CodeViewRequest
import com.maxvibes.shared.result.Result
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodingAgentViewResolverSizeLimitTest {
    @Test
    fun `oversized batch returns feedback for every full view without caching it`() = runBlocking {
        val paths = listOf(".maxvibes/logs/maxvibes.log", "src/Main.kt")
        val context = mockk<ProjectContextPort>()
        coEvery { context.gatherFiles(paths, any()) } returns
                Result.Failure(ContextError.SizeLimitExceeded(1_658_924, 500_000))
        val state = mockk<ClipboardSessionState>()
        val resolver = CodingAgentViewResolver(context, mockk(), null, mockk(relaxed = true))

        val files = assertNotNull(
            resolver.resolve(
                paths.map { CodeViewRequest(it, CodeGranularity.FULL) }, state
            )
        )

        assertEquals(paths.toSet(), files.keys)
        paths.forEach { path ->
            val feedback = files.getValue(path)
            assertTrue(feedback.contains(path))
            assertTrue(feedback.contains("Size limit exceeded: 1658924 > 500000"))
            assertTrue(feedback.contains("Request fewer files"))
        }
        verify(exactly = 0) { state.allGatheredFiles }
    }

    @Test
    fun `missing project remains a blocking failure`() = runBlocking {
        val context = mockk<ProjectContextPort>()
        coEvery { context.gatherFiles(any(), any()) } returns
                Result.Failure(ContextError.ProjectNotFound())
        val resolver = CodingAgentViewResolver(context, mockk(), null, mockk(relaxed = true))

        assertNull(
            resolver.resolve(
                listOf(CodeViewRequest("src/Main.kt", CodeGranularity.FULL)), mockk()
            )
        )
    }
}
