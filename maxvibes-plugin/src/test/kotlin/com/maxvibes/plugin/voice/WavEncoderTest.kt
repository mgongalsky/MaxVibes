package com.maxvibes.plugin.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WavEncoderTest {
    @Test
    fun encodesMonoPcmWithStandardWaveHeader() {
        val pcm = byteArrayOf(1, 2, 3, 4, 5, 6)

        val wav = WavEncoder.encode(
            pcm = pcm,
            sampleRate = 16_000,
            bitsPerSample = 16,
            channels = 1
        )

        assertEquals("RIFF", wav.asAscii(0, 4))
        assertEquals(36 + pcm.size, wav.littleEndianInt(4))
        assertEquals("WAVE", wav.asAscii(8, 4))
        assertEquals("fmt ", wav.asAscii(12, 4))
        assertEquals(16, wav.littleEndianInt(16))
        assertEquals(1, wav.littleEndianShort(20))
        assertEquals(1, wav.littleEndianShort(22))
        assertEquals(16_000, wav.littleEndianInt(24))
        assertEquals(32_000, wav.littleEndianInt(28))
        assertEquals(2, wav.littleEndianShort(32))
        assertEquals(16, wav.littleEndianShort(34))
        assertEquals("data", wav.asAscii(36, 4))
        assertEquals(pcm.size, wav.littleEndianInt(40))
        assertContentEquals(pcm, wav.copyOfRange(WavEncoder.HEADER_SIZE, wav.size))
    }

    @Test
    fun encodesEmptyPcmAsValidEmptyWave() {
        val wav = WavEncoder.encode(byteArrayOf(), 16_000, 16, 1)

        assertEquals(WavEncoder.HEADER_SIZE, wav.size)
        assertEquals(36, wav.littleEndianInt(4))
        assertEquals(0, wav.littleEndianInt(40))
    }

    @Test
    fun rejectsInvalidAudioFormat() {
        assertFailsWith<IllegalArgumentException> {
            WavEncoder.encode(byteArrayOf(1), sampleRate = 0, bitsPerSample = 16, channels = 1)
        }
        assertFailsWith<IllegalArgumentException> {
            WavEncoder.encode(byteArrayOf(1), sampleRate = 16_000, bitsPerSample = 12, channels = 1)
        }
        assertFailsWith<IllegalArgumentException> {
            WavEncoder.encode(byteArrayOf(1), sampleRate = 16_000, bitsPerSample = 16, channels = 0)
        }
    }

    private fun ByteArray.asAscii(offset: Int, length: Int): String =
        copyOfRange(offset, offset + length).toString(Charsets.US_ASCII)

    private fun ByteArray.littleEndianInt(offset: Int): Int =
        ByteBuffer.wrap(this, offset, Int.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).int

    private fun ByteArray.littleEndianShort(offset: Int): Int =
        ByteBuffer.wrap(this, offset, Short.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .short.toInt() and 0xffff
}
