package com.maxvibes.plugin.ui

import com.maxvibes.application.port.output.IdeErrorsPort
import com.maxvibes.domain.model.code.IdeError
import com.maxvibes.plugin.testsupport.FakeChatPanelCallbacks
import com.maxvibes.shared.result.Result
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class IdeErrorsAttachmentLoaderTest {
    private val ideErrorsPort = mockk<IdeErrorsPort>()
    private lateinit var callbacks: FakeChatPanelCallbacks
    private lateinit var attachments: AttachmentCoordinator
    private lateinit var loader: IdeErrorsAttachmentLoader

    @BeforeEach
    fun setUp() {
        callbacks = FakeChatPanelCallbacks()
        attachments = AttachmentCoordinator(
            context = PendingTurnContext(maxImages = 3),
            attachmentView = callbacks,
            inputStatusView = callbacks,
            maxImages = 3
        )
        loader = IdeErrorsAttachmentLoader(
            ideErrorsPort = ideErrorsPort,
            backgroundTaskRunner = ImmediateBackgroundTaskRunner(),
            attachments = attachments,
            inputStatusView = callbacks
        )
    }

    @Test
    fun `fetch attaches formatted IDE errors`() {
        val errors = listOf(
            IdeError("src/A.kt", 10, 4, "First error"),
            IdeError("src/B.kt", 20, 8, "Second error")
        )
        coEvery { ideErrorsPort.getCompilerErrors() } returns Result.Success(errors)

        loader.fetch()

        val expected = errors.joinToString(separator = System.lineSeparator()) {
            it.formatForLlm()
        }
        assertEquals(expected, attachments.errors)
        assertEquals(null to expected, callbacks.attachmentsChanges.last())
        assertEquals("Attached 2 IDE errors", callbacks.statusUpdates.last())
    }

    @Test
    fun `fetch reports empty result without changing attachments`() {
        coEvery { ideErrorsPort.getCompilerErrors() } returns Result.Success(emptyList())

        loader.fetch()

        assertEquals(null, attachments.errors)
        assertEquals("No IDE errors found in open files", callbacks.statusUpdates.last())
        assertTrue(callbacks.attachmentsChanges.isEmpty())
    }

    @Test
    fun `fetch reports port failure`() {
        coEvery { ideErrorsPort.getCompilerErrors() } returns Result.Failure(Exception("boom"))

        loader.fetch()

        assertTrue(callbacks.reportedErrors.single().contains("boom"))
    }

    private class ImmediateBackgroundTaskRunner : BackgroundTaskRunner {
        override fun <T> run(
            title: String,
            cancellable: Boolean,
            publishIndicator: Boolean,
            action: suspend () -> T,
            onSuccess: (T) -> Unit,
            onCancel: () -> Unit
        ) {
            onSuccess(runBlocking { action() })
        }
    }
}
