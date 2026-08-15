package com.maxvibes.plugin.voice

import com.maxvibes.shared.result.Result
import java.io.ByteArrayOutputStream
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine

sealed class VoiceRecordingError(val message: String) {
    class Unavailable(details: String) : VoiceRecordingError("Microphone is unavailable: $details")
    class InvalidState(details: String) : VoiceRecordingError(details)
    class Capture(details: String) : VoiceRecordingError("Microphone recording failed: $details")
}

interface VoiceRecorder : AutoCloseable {
    val isRecording: Boolean
    fun start(): Result<Unit, VoiceRecordingError>
    fun stop(): Result<ByteArray, VoiceRecordingError>
}

/** Records mono 16 kHz PCM through Java Sound and returns an in-memory WAV file. */
class JavaSoundVoiceRecorder : VoiceRecorder {
    @Volatile
    override var isRecording: Boolean = false
        private set

    private var line: TargetDataLine? = null
    private var recordingThread: Thread? = null
    private var pcmOutput: ByteArrayOutputStream? = null

    @Volatile
    private var captureFailure: Throwable? = null

    @Synchronized
    override fun start(): Result<Unit, VoiceRecordingError> {
        if (isRecording) {
            return Result.Failure(VoiceRecordingError.InvalidState("Voice recording is already active"))
        }

        return try {
            val format = AudioFormat(
                SAMPLE_RATE.toFloat(),
                BITS_PER_SAMPLE,
                CHANNELS,
                true,
                false
            )
            val dataLine = AudioSystem.getLine(
                DataLine.Info(TargetDataLine::class.java, format)
            ) as TargetDataLine
            dataLine.open(format)

            val output = ByteArrayOutputStream()
            line = dataLine
            pcmOutput = output
            captureFailure = null
            isRecording = true
            dataLine.start()
            recordingThread = Thread(
                { capture(dataLine, output) },
                "MaxVibes-VoiceRecorder"
            ).apply {
                isDaemon = true
                start()
            }
            Result.Success(Unit)
        } catch (e: Exception) {
            releaseLine()
            Result.Failure(
                VoiceRecordingError.Unavailable(e.message ?: e.javaClass.simpleName)
            )
        }
    }

    @Synchronized
    override fun stop(): Result<ByteArray, VoiceRecordingError> {
        if (!isRecording) {
            return Result.Failure(VoiceRecordingError.InvalidState("Voice recording is not active"))
        }

        isRecording = false
        val thread = recordingThread
        releaseLine()
        runCatching { thread?.join(STOP_JOIN_TIMEOUT_MS) }
        recordingThread = null

        val failure = captureFailure
        val pcm = pcmOutput?.toByteArray() ?: byteArrayOf()
        pcmOutput = null
        captureFailure = null
        if (failure != null) {
            return Result.Failure(
                VoiceRecordingError.Capture(failure.message ?: failure.javaClass.simpleName)
            )
        }
        if (pcm.isEmpty()) {
            return Result.Failure(VoiceRecordingError.Capture("No audio samples were captured"))
        }

        return Result.Success(
            WavEncoder.encode(
                pcm = pcm,
                sampleRate = SAMPLE_RATE,
                bitsPerSample = BITS_PER_SAMPLE,
                channels = CHANNELS
            )
        )
    }

    private fun capture(dataLine: TargetDataLine, output: ByteArrayOutputStream) {
        val buffer = ByteArray(BUFFER_SIZE)
        try {
            while (isRecording) {
                val count = dataLine.read(buffer, 0, buffer.size)
                if (count > 0) output.write(buffer, 0, count)
            }
        } catch (e: Exception) {
            if (isRecording) captureFailure = e
        } finally {
            isRecording = false
        }
    }

    private fun releaseLine() {
        line?.let { current ->
            runCatching { current.stop() }
            runCatching { current.flush() }
            runCatching { current.close() }
        }
        line = null
    }

    @Synchronized
    override fun close() {
        isRecording = false
        releaseLine()
        runCatching { recordingThread?.join(STOP_JOIN_TIMEOUT_MS) }
        recordingThread = null
        pcmOutput = null
        captureFailure = null
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val BITS_PER_SAMPLE = 16
        const val CHANNELS = 1
        private const val BUFFER_SIZE = 4_096
        private const val STOP_JOIN_TIMEOUT_MS = 2_000L
    }
}
