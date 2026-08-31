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

    @Test
    fun `a kept-alive link needs no wake-up silence`() {
        // 链路一直挂着就没有冷热之分，连蓝牙都只留吸收抖动的那一小段。
        for (route in AudioRoute.entries) {
            for (cold in listOf(false, true)) {
                assertEquals(
                    KEPT_ALIVE_LEAD_IN_MS,
                    leadInSilenceMs(route, coldStart = cold, keptAlive = true),
                )
            }
        }
    }

    @Test
    fun `keep-alive dither stays at one lsb on both channels`() {
        val chunk = keepAliveDither(frames = 2)
        // 两帧：+1 和 -1，左右声道相同。写错高位字节会变成听得见的 255。
        assertArrayEquals(
            byteArrayOf(1, 0, 1, 0, -1, -1, -1, -1),
            chunk,
        )
    }

    @Test
    fun `keeping the link alive is the fastest bluetooth start`() {
        assertTrue(
            leadInSilenceMs(AudioRoute.Bluetooth, coldStart = false, keptAlive = true) <
                leadInSilenceMs(AudioRoute.Bluetooth, coldStart = false),
        )
    }
}
