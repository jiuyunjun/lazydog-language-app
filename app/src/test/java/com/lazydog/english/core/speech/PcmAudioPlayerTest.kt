package com.lazydog.english.core.speech

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmAudioPlayerTest {

    @Test
    fun `each mono sample lands in both channels`() {
        // 两个 16bit 样本：0x0201 和 0x0403（小端）。
        val mono = byteArrayOf(1, 2, 3, 4)
        assertArrayEquals(byteArrayOf(1, 2, 1, 2, 3, 4, 3, 4), monoToStereo(mono, mono.size))
    }

    @Test
    fun `only the first length bytes are used`() {
        val mono = byteArrayOf(1, 2, 3, 4, 9, 9, 9, 9)
        assertArrayEquals(byteArrayOf(1, 2, 1, 2, 3, 4, 3, 4), monoToStereo(mono, 4))
    }

    @Test
    fun `odd trailing byte is dropped instead of crashing`() {
        val mono = byteArrayOf(1, 2, 3)
        assertArrayEquals(byteArrayOf(1, 2, 1, 2), monoToStereo(mono, 3))
    }

    @Test
    fun `empty input produces empty output`() {
        assertEquals(0, monoToStereo(ByteArray(0), 0).size)
    }

    @Test
    fun `bluetooth gets the longest lead-in`() {
        val bluetooth = leadInSilenceMs(AudioRoute.Bluetooth, coldStart = false)
        assertTrue(bluetooth > leadInSilenceMs(AudioRoute.Speaker, coldStart = false))
        assertTrue(bluetooth > leadInSilenceMs(AudioRoute.Wired, coldStart = false))
    }

    @Test
    fun `cold start adds extra silence on every route`() {
        for (route in AudioRoute.entries) {
            assertEquals(
                leadInSilenceMs(route, coldStart = false) + COLD_START_EXTRA_MS,
                leadInSilenceMs(route, coldStart = true),
            )
        }
    }
}
