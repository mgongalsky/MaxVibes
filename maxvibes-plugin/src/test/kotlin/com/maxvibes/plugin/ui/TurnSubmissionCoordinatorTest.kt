package com.maxvibes.plugin.ui

import com.maxvibes.domain.model.interaction.AttachedImage
import com.maxvibes.domain.model.interaction.InteractionMode
import com.maxvibes.plugin.testsupport.FakeChatPanelCallbacks
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class TurnSubmissionCoordinatorTest {
    private data class ApiCall(
        val message: String,
        val trace: String?,
        val errors: String?,
        val isPlanOnly: Boolean,
        val isDryRun: Boolean
    )

    private data class ClipboardCall(
        val message: String,
        val trace: String?,
        val errors: String?,
        val isPlanOnly: Boolean,
        val addHistory: Boolean,
        val promptName: String?
    )

    private data class ClaudeCall(
        val message: String,
        val trace: String?,
        val errors: String?,
        val isPlanOnly: Boolean,
        val promptName: String?,
        val images: List<AttachedImage>
    )

    private lateinit var callbacks: FakeChatPanelCallbacks
    private lateinit var attachments: AttachmentCoordinator
    private lateinit var coordinator: TurnSubmissionCoordinator
    private val warnings = mutableListOf<String>()
    private val apiCalls = mutableListOf<ApiCall>()
    private val cheapApiCalls = mutableListOf<ApiCall>()
    private val clipboardCalls = mutableListOf<ClipboardCall>()
    private val claudeCalls = mutableListOf<ClaudeCall>()
    private val approveCalls = mutableListOf<Pair<String?, String?>>()
    private var savedDocuments = 0
    private var dismissedQuestions = 0
    private var redoneClipboardRequests = 0

    @BeforeEach
    fun setUp() {
        callbacks = FakeChatPanelCallbacks()
        attachments = AttachmentCoordinator(
            context = PendingTurnContext(maxImages = 3),
            attachmentView = callbacks,
            inputStatusView = callbacks,
            maxImages = 3
        )
        warnings.clear()
        apiCalls.clear()
        cheapApiCalls.clear()
        clipboardCalls.clear()
        claudeCalls.clear()
        approveCalls.clear()
        savedDocuments = 0
        dismissedQuestions = 0
        redoneClipboardRequests = 0

        coordinator = TurnSubmissionCoordinator(
            documentSaver = DocumentSaver { savedDocuments++ },
            dismissQuestionTurn = { dismissedQuestions++ },
            attachments = attachments,
            appendToChat = warnings::add,
            dispatchApi = { message, trace, errors, planOnly, dryRun ->
                apiCalls.add(ApiCall(message, trace, errors, planOnly, dryRun))
            },
            dispatchClipboard = { message, trace, errors, planOnly, addHistory, promptName ->
                clipboardCalls.add(
                    ClipboardCall(message, trace, errors, planOnly, addHistory, promptName)
                )
            },
            dispatchCheapApi = { message, trace, errors, planOnly, dryRun ->
                cheapApiCalls.add(ApiCall(message, trace, errors, planOnly, dryRun))
            },
            dispatchClaudeCode = { message, trace, errors, planOnly, promptName, images ->
                claudeCalls.add(
                    ClaudeCall(message, trace, errors, planOnly, promptName, images)
                )
            },
            approveClaudeCode = { trace, errors -> approveCalls.add(trace to errors) },
            redoClipboardJson = { redoneClipboardRequests++ }
        )
    }

    private fun image(id: String) = AttachedImage(
        mediaType = "image/png",
        base64Data = id
    )

    @Test
    fun `Claude Code send consumes attachments and preserves images`() {
        val attachedImage = image("first")
        attachments.attachTrace("trace")
        attachments.attachErrors("errors")
        attachments.attachImage(attachedImage)
        attachments.armOneShot("one-shot", "class Example", "Write test")

        coordinator.sendMessage(
            userInput = "hello",
            isPlanOnly = true,
            isDryRun = false,
            mode = InteractionMode.CLAUDE_CODE,
            selectedSpecificPromptName = "session-prompt"
        )

        val call = claudeCalls.single()
        assertEquals("hello", call.message)
        assertEquals(
            "class Example" + System.lineSeparator() + System.lineSeparator() + "trace",
            call.trace
        )
        assertEquals("errors", call.errors)
        assertEquals("one-shot", call.promptName)
        assertEquals(listOf(attachedImage), call.images)
        assertTrue(call.isPlanOnly)
        assertEquals(1, savedDocuments)
        assertEquals(1, dismissedQuestions)
        assertNull(attachments.trace)
        assertNull(attachments.errors)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `API send publishes image and one-shot warnings`() {
        attachments.attachImage(image("first"))
        attachments.armOneShot("skill", "context", "label")

        coordinator.sendMessage(
            userInput = "hello",
            isPlanOnly = false,
            isDryRun = true,
            mode = InteractionMode.API
        )

        assertEquals(1, apiCalls.size)
        assertTrue(apiCalls.single().isDryRun)
        assertEquals(2, warnings.size)
        assertTrue(warnings.any { it.contains("image(s) dropped") })
        assertTrue(warnings.any { it.contains("prefill text only") })
    }

    @Test
    fun `Clipboard send forwards history flag and selected prompt`() {
        coordinator.sendMessage(
            userInput = "hello",
            isPlanOnly = false,
            isDryRun = false,
            mode = InteractionMode.CLIPBOARD,
            addHistory = true,
            selectedSpecificPromptName = "session-prompt"
        )

        val call = clipboardCalls.single()
        assertTrue(call.addHistory)
        assertEquals("session-prompt", call.promptName)
    }

    @Test
    fun `Cheap API send uses cheap dispatcher`() {
        coordinator.sendMessage(
            userInput = "hello",
            isPlanOnly = true,
            isDryRun = true,
            mode = InteractionMode.CHEAP_API
        )

        assertEquals(1, cheapApiCalls.size)
        assertTrue(apiCalls.isEmpty())
    }

    @Test
    fun `approve warns about unsupported attachments and forwards trace and errors`() {
        attachments.attachTrace("trace")
        attachments.attachErrors("errors")
        attachments.attachImage(image("first"))
        attachments.armOneShot("skill", "context", "label")

        coordinator.approve()

        assertEquals(listOf("trace" to "errors"), approveCalls)
        assertEquals(2, warnings.size)
        assertNull(attachments.trace)
        assertNull(attachments.errors)
        assertEquals(1, savedDocuments)
    }

    @Test
    fun `redo saves documents before delegating`() {
        coordinator.redoClipboardJson()

        assertEquals(1, savedDocuments)
        assertEquals(1, redoneClipboardRequests)
    }
}
