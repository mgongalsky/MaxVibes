package com.maxvibes.plugin.voice

import com.maxvibes.application.service.VoiceTranscriptionService
import com.maxvibes.plugin.settings.VoiceTranscriptionConfiguration
import com.maxvibes.shared.result.Result
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.swing.SwingUtilities

enum class VoiceInputState { IDLE, STARTING, RECORDING, TRANSCRIBING }

/** Coordinates microphone capture, cloud transcription and UI state without blocking the EDT. */
class VoiceInputCoordinator(
    private val projectName: String,
    private val configuration: () -> VoiceTranscriptionConfiguration,
    private val openSettings: () -> Unit,
    private val recorder: VoiceRecorder = JavaSoundVoiceRecorder(),
    private val onState: (VoiceInputState) -> Unit,
    private val onTranscript: (String) -> Unit,
    private val onStatus: (String) -> Unit,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "MaxVibes-VoiceInput").apply { isDaemon = true }
    }
) : AutoCloseable {
    @Volatile
    private var state = VoiceInputState.IDLE

    fun toggle() {
        if (closed) return
        when (state) {
            VoiceInputState.IDLE -> startRecording()
            VoiceInputState.RECORDING -> stopAndTranscribe()
            VoiceInputState.STARTING, VoiceInputState.TRANSCRIBING -> Unit
        }
    }

    private fun startRecording() {
        val config = configuration()
        if (!config.isConfigured) {
            onStatus("Configure voice transcription in Settings → Tools → MaxVibes")
            openSettings()
            return
        }

        updateState(VoiceInputState.STARTING, "Opening microphone...")
        executor.submit {
            try {
                when (val result = recorder.start()) {
                    is Result.Success -> publish {
                        updateState(
                            VoiceInputState.RECORDING,
                            "Recording voice — press microphone to stop or Send to transcribe and send"
                        )
                        if (pendingSend != null) stopAndTranscribe()
                    }

                    is Result.Failure -> publish {
                        updateState(VoiceInputState.IDLE, result.error.message)
                    }
                }
            } catch (_: Exception) {
                publish { updateState(VoiceInputState.IDLE, "Could not start voice recording. Please try again.") }
            }
        }
    }

    private fun stopAndTranscribe() {
        val config = configuration()
        updateState(
            VoiceInputState.TRANSCRIBING,
            if (pendingSend == null) "Transcribing voice..." else "Waiting for voice transcript to send..."
        )
        executor.submit {
            try {
                when (val recording = recorder.stop()) {
                    is Result.Failure -> publish {
                        updateState(VoiceInputState.IDLE, recording.error.message)
                    }

                    is Result.Success -> {
                        val port = OpenAiCompatibleTranscriptionAdapter(
                            endpoint = config.endpoint,
                            apiKey = config.apiKey
                        )
                        val terms = VoiceContextPromptBuilder.build(
                            projectName = projectName,
                            glossaryTerms = config.glossaryTerms()
                        )
                        val result = kotlinx.coroutines.runBlocking {
                            VoiceTranscriptionService(port).transcribe(
                                audio = recording.value,
                                model = config.model,
                                language = config.language,
                                contextTerms = terms
                            )
                        }
                        publish {
                            when (result) {
                                is Result.Success -> {
                                    if (result.value.text.isBlank()) {
                                        updateState(VoiceInputState.IDLE, "No speech recognized. Message was not sent.")
                                    } else {
                                        val send = pendingSend
                                        onTranscript(result.value.text)
                                        updateState(VoiceInputState.IDLE, "Voice transcript inserted")
                                        send?.invoke()
                                    }
                                }

                                is Result.Failure -> updateState(VoiceInputState.IDLE, result.error.message)
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                publish {
                    updateState(
                        VoiceInputState.IDLE,
                        "Voice transcription failed. Message was not sent; please try again."
                    )
                }
            }
        }
    }

    private fun updateState(newState: VoiceInputState, status: String) {
        state = newState
        if (newState == VoiceInputState.IDLE) pendingSend = null
        onState(newState)
        onStatus(status)
    }

    private fun publish(action: () -> Unit) {
        val guardedAction = { if (!closed) action() }
        if (SwingUtilities.isEventDispatchThread()) guardedAction() else SwingUtilities.invokeLater(guardedAction)
    }

    override fun close() {
        closed = true
        pendingSend = null
        state = VoiceInputState.IDLE
        runCatching { recorder.close() }
        executor.shutdownNow()
    }
    private var pendingSend: (() -> Unit)? = null

    @Volatile
    private var closed = false

    /** Called on the EDT before consuming input. Returns true when voice input owns this send. */
    fun deferSendUntilTranscript(send: () -> Unit): Boolean {
        if (closed) return true
        if (state == VoiceInputState.IDLE) return false
        if (pendingSend == null) pendingSend = send
        if (state == VoiceInputState.RECORDING) stopAndTranscribe()
        onStatus("Waiting for voice transcript to send...")
        return true
    }
}
