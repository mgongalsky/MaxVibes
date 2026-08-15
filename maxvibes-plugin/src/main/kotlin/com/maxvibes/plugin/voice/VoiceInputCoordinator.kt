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
            when (val result = recorder.start()) {
                is Result.Success -> publish {
                    updateState(VoiceInputState.RECORDING, "Recording voice — press microphone to stop")
                }

                is Result.Failure -> publish {
                    updateState(VoiceInputState.IDLE, result.error.message)
                }
            }
        }
    }

    private fun stopAndTranscribe() {
        val config = configuration()
        updateState(VoiceInputState.TRANSCRIBING, "Transcribing voice...")
        executor.submit {
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
                                onTranscript(result.value.text)
                                updateState(VoiceInputState.IDLE, "Voice transcript inserted")
                            }

                            is Result.Failure -> {
                                updateState(VoiceInputState.IDLE, result.error.message)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun updateState(newState: VoiceInputState, status: String) {
        state = newState
        onState(newState)
        onStatus(status)
    }

    private fun publish(action: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) action() else SwingUtilities.invokeLater(action)
    }

    override fun close() {
        state = VoiceInputState.IDLE
        runCatching { recorder.close() }
        executor.shutdownNow()
    }
}
