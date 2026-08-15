package com.maxvibes.plugin.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Encodes signed little-endian PCM samples into a standard RIFF/WAVE byte array. */
object WavEncoder {
    fun encode(
        pcm: ByteArray,
        sampleRate: Int,
        bitsPerSample: Int,
        channels: Int
    ): ByteArray {
        require(sampleRate > 0) { "Sample rate must be positive" }
        require(bitsPerSample > 0 && bitsPerSample % 8 == 0) {
            "Bits per sample must be a positive multiple of 8"
        }
        require(channels > 0) { "Channel count must be positive" }

        val blockAlign = channels * bitsPerSample / 8
        val byteRate = sampleRate * blockAlign
        val buffer = ByteBuffer.allocate(HEADER_SIZE + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putAscii("RIFF")
        buffer.putInt(36 + pcm.size)
        buffer.putAscii("WAVE")
        buffer.putAscii("fmt ")
        buffer.putInt(16)
        buffer.putShort(PCM_FORMAT.toShort())
        buffer.putShort(channels.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(byteRate)
        buffer.putShort(blockAlign.toShort())
        buffer.putShort(bitsPerSample.toShort())
        buffer.putAscii("data")
        buffer.putInt(pcm.size)
        buffer.put(pcm)
        return buffer.array()
    }

    private fun ByteBuffer.putAscii(value: String) {
        put(value.toByteArray(Charsets.US_ASCII))
    }

    private const val PCM_FORMAT = 1
    const val HEADER_SIZE = 44
}
