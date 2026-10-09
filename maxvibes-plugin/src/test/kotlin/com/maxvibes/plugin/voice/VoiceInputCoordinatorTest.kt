package com.maxvibes.plugin.voice

import com.maxvibes.application.port.output.VoiceTranscript
import com.maxvibes.application.port.output.VoiceTranscriptionError
import com.maxvibes.plugin.settings.VoiceTranscriptionConfiguration
import com.maxvibes.shared.result.Result
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.ArrayDeque
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VoiceInputCoordinatorTest {
    private val executor = QueuedExecutor()
    private val recorder = mockk<VoiceRecorder>(relaxed = true)
    private val events = mutableListOf<String>()
    private var input = "Existing text"
    private var sends = 0
    private lateinit var coordinator: VoiceInputCoordinator

    @BeforeEach
    fun setUp() {
        mockkConstructor(OpenAiCompatibleTranscriptionAdapter::class)
        every { recorder.start() } returns Result.Success(Unit)
        every { recorder.stop() } returns Result.Success(byteArrayOf(1, 2))
        transcript("spoken words")
        coordinator = VoiceInputCoordinator(
            projectName = "MaxVibes",
            configuration = {
                VoiceTranscriptionConfiguration(
                    endpoint = "https://example.invalid/transcriptions",
                    model = "test-model",
                    apiKey = "test-key"
                )
            },
            openSettings = { error("Settings should not open") },
            recorder = recorder,
            onState = {
                assertTrue(SwingUtilities.isEventDispatchThread())
                events += it.name
            },
            onTranscript = {
                assertTrue(SwingUtilities.isEventDispatchThread())
                input += " $it"
                events += "transcript"
            },
            onStatus = { assertTrue(SwingUtilities.isEventDispatchThread()) },
            executor = executor
        )
    }

    @AfterEach
    fun tearDown() {
        try {
            edt { coordinator.close() }
            edt { }
        } finally {
            unmockkConstructor(OpenAiCompatibleTranscriptionAdapter::class)
        }
    }

    @Test
    fun idleSendDoesNotWaitForVoice() {
        edt { assertFalse(coordinator.deferSendUntilTranscript(::send)) }
        assertEquals(0, sends)
        verify(exactly = 0) { recorder.stop() }
    }

    @Test
    fun stopOnlyInsertsTranscriptWithoutSending() {
        startRecording()
        edt { coordinator.toggle() }
        completeBackgroundTask()
        assertEquals("Existing text spoken words", input)
        assertEquals(0, sends)
        assertEquals("IDLE", events.last())
    }

    @Test
    fun sendDuringRecordingWaitsAndSendsCombinedInputExactlyOnce() {
        startRecording()
        edt {
            assertTrue(coordinator.deferSendUntilTranscript(::send))
            assertTrue(coordinator.deferSendUntilTranscript { error("Duplicate send") })
        }
        assertEquals("Existing text", input)
        assertEquals(0, sends)
        completeBackgroundTask()
        assertEquals(listOf("transcript", "IDLE", "send:Existing text spoken words"), events.takeLast(3))
        assertEquals(1, sends)
        verify(exactly = 1) { recorder.stop() }
    }

    @Test
    fun sendWhileTranscribingWaitsForTheExistingRequest() {
        startRecording()
        edt {
            coordinator.toggle()
            assertTrue(coordinator.deferSendUntilTranscript(::send))
        }
        assertEquals(0, sends)
        completeBackgroundTask()
        assertEquals(1, sends)
        verify(exactly = 1) { recorder.stop() }
    }

    @Test
    fun sendWhileOpeningMicrophoneStopsOnceRecordingStarts() {
        edt {
            coordinator.toggle()
            assertTrue(coordinator.deferSendUntilTranscript(::send))
        }
        assertEquals(listOf("STARTING"), events)
        completeBackgroundTask()
        assertEquals("TRANSCRIBING", events.last())
        assertEquals(0, sends)
        completeBackgroundTask()
        assertEquals(1, sends)
        verify(exactly = 1) { recorder.start() }
        verify(exactly = 1) { recorder.stop() }
    }

    @Test
    fun failedTranscriptionPreservesInputAndDoesNotSendOnTheNextRecording() {
        coEvery { anyConstructed<OpenAiCompatibleTranscriptionAdapter>().transcribe(any()) } returns
            Result.Failure(VoiceTranscriptionError.Network("offline"))
        startRecording()
        edt { assertTrue(coordinator.deferSendUntilTranscript(::send)) }
        completeBackgroundTask()
        assertEquals("Existing text", input)
        assertEquals(0, sends)
        assertEquals("IDLE", events.last())

        transcript("retry")
        startRecording()
        edt { coordinator.toggle() }
        completeBackgroundTask()
        assertEquals("Existing text retry", input)
        assertEquals(0, sends)
    }

    @Test
    fun blankTranscriptDoesNotSendExistingText() {
        transcript("  \n ")
        startRecording()
        edt { assertTrue(coordinator.deferSendUntilTranscript(::send)) }
        completeBackgroundTask()
        assertEquals("Existing text", input)
        assertEquals(0, sends)
        assertEquals("IDLE", events.last())
    }

    @Test
    fun microphoneStartFailureCancelsPendingSend() {
        every { recorder.start() } returns Result.Failure(VoiceRecordingError.Unavailable("no device"))
        edt {
            coordinator.toggle()
            assertTrue(coordinator.deferSendUntilTranscript(::send))
        }
        completeBackgroundTask()
        assertEquals(listOf("STARTING", "IDLE"), events)
        assertEquals("Existing text", input)
        assertEquals(0, sends)
        verify(exactly = 0) { recorder.stop() }

        every { recorder.start() } returns Result.Success(Unit)
        startRecording()
        assertEquals("RECORDING", events.last())
    }

    @Test
    fun recorderStopFailureCancelsPendingSend() {
        every { recorder.stop() } returns Result.Failure(VoiceRecordingError.Capture("no samples"))
        startRecording()
        edt { assertTrue(coordinator.deferSendUntilTranscript(::send)) }
        completeBackgroundTask()
        assertEquals("Existing text", input)
        assertEquals(0, sends)
        assertEquals("IDLE", events.last())
    }

    @Test
    fun unexpectedTranscriptionExceptionRestoresIdleWithoutSending() {
        coEvery { anyConstructed<OpenAiCompatibleTranscriptionAdapter>().transcribe(any()) } throws
            IllegalStateException("test failure")
        startRecording()
        edt { assertTrue(coordinator.deferSendUntilTranscript(::send)) }
        completeBackgroundTask()
        assertEquals("Existing text", input)
        assertEquals(0, sends)
        assertEquals("IDLE", events.last())
        edt { assertFalse(coordinator.deferSendUntilTranscript(::send)) }
    }

    @Test
    fun closingPanelDiscardsTranscriptAlreadyQueuedForTheEdt() {
        startRecording()
        edt { assertTrue(coordinator.deferSendUntilTranscript(::send)) }
        val beforeClose = events.toList()
        edt {
            // Keep the EDT occupied until the worker has queued its success callback.
            val worker = Thread { executor.runNext() }
            worker.start()
            worker.join(5_000)
            assertFalse(worker.isAlive, "Voice worker did not finish")
            coordinator.close()
        }
        edt { }
        assertEquals(beforeClose, events)
        assertEquals("Existing text", input)
        assertEquals(0, sends)
        edt {
            coordinator.toggle()
            assertTrue(coordinator.deferSendUntilTranscript(::send))
        }
        verify(exactly = 1) { recorder.start() }
    }

    private fun transcript(text: String) {
        coEvery { anyConstructed<OpenAiCompatibleTranscriptionAdapter>().transcribe(any()) } returns
            Result.Success(VoiceTranscript(text))
    }

    private fun startRecording() {
        edt { coordinator.toggle() }
        completeBackgroundTask()
    }

    private fun completeBackgroundTask() {
        executor.runNext()
        edt { }
    }

    private fun send() {
        assertTrue(SwingUtilities.isEventDispatchThread())
        assertFalse(coordinator.deferSendUntilTranscript { error("Send still deferred after success") })
        sends++
        events += "send:$input"
        input = ""
    }

    private fun edt(action: () -> Unit) {
        SwingUtilities.invokeAndWait(action)
    }

    private class QueuedExecutor : AbstractExecutorService() {
        private val tasks = ArrayDeque<Runnable>()
        private var stopped = false

        override fun execute(command: Runnable) {
            check(!stopped)
            tasks.addLast(command)
        }

        fun runNext() {
            tasks.removeFirst().run()
        }

        override fun shutdown() { stopped = true }

        override fun shutdownNow(): MutableList<Runnable> {
            stopped = true
            return tasks.toMutableList().also { tasks.clear() }
        }

        override fun isShutdown(): Boolean = stopped
        override fun isTerminated(): Boolean = stopped && tasks.isEmpty()
        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = isTerminated
    }
}
