package com.titus.dynamicisland.data.assistant

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GoogleTtsTest {
    private fun wav(samples: ByteArray, extraChunk: Boolean = false): ByteArray {
        val extra = if (extraChunk) 8 + 3 + 1 else 0 // odd-sized "LIST" chunk plus pad byte
        val buf = ByteBuffer.allocate(12 + 24 + extra + 8 + samples.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray()).putInt(4 + 24 + extra + 8 + samples.size).put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(24_000).putInt(48_000).putShort(2).putShort(16)
        if (extraChunk) buf.put("LIST".toByteArray()).putInt(3).put(byteArrayOf(1, 2, 3, 0))
        buf.put("data".toByteArray()).putInt(samples.size).put(samples)
        return buf.array()
    }

    @Test fun stripsWavHeader() {
        val samples = byteArrayOf(1, 2, 3, 4, 5, 6)
        assertArrayEquals(samples, GoogleTts.pcmFromWav(wav(samples)))
    }

    @Test fun skipsOtherChunksIncludingOddSizedOnes() {
        val samples = byteArrayOf(9, 8, 7, 6)
        assertArrayEquals(samples, GoogleTts.pcmFromWav(wav(samples, extraChunk = true)))
    }

    @Test fun passesRawPcmThrough() {
        val raw = byteArrayOf(1, 2, 3, 4)
        assertArrayEquals(raw, GoogleTts.pcmFromWav(raw))
    }
}
