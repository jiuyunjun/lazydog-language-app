package com.lazydog.english.core.speech

import com.lazydog.english.domain.speaking.SpeechRate
import com.lazydog.english.domain.speaking.SpeechStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SsmlTest {

    @Test
    fun `slow rate lands in prosody`() {
        val ssml = buildSpeechSsml("Hello there.", "en-US-JennyNeural", SpeechRate.Slow)
        assertTrue(ssml.contains("""<prosody rate="-30%">Hello there.</prosody>"""))
        assertTrue(ssml.contains("""<voice name="en-US-JennyNeural">"""))
    }

    @Test
    fun `text is xml escaped`() {
        val ssml = buildSpeechSsml("""Tom & Jerry's <best> "day"""", "v", SpeechRate.Normal)
        assertTrue(ssml.contains("Tom &amp; Jerry&apos;s &lt;best&gt; &quot;day&quot;"))
    }

    @Test
    fun `word style swaps dragon hd for the plain neural voice`() {
        val ssml = buildSpeechSsml("curb", "en-US-Ava:DragonHDLatestNeural", SpeechRate.Normal, SpeechStyle.Word)
        assertTrue(ssml.contains("""<voice name="en-US-AvaNeural">"""))
        assertFalse(ssml.contains("DragonHD"))
        // 前后垫停顿，起音不被吃掉。
        assertTrue(ssml.contains("""<break time="120ms"/><prosody rate="0%" pitch="0%">curb</prosody><break time="120ms"/>"""))
    }

    @Test
    fun `sentence style keeps the configured voice`() {
        val ssml = buildSpeechSsml("Slow down.", "en-GB-Ryan:DragonHDLatestNeural", SpeechRate.Normal, SpeechStyle.Sentence)
        assertTrue(ssml.contains("""<voice name="en-GB-Ryan:DragonHDLatestNeural">"""))
        assertFalse(ssml.contains("<break"))
    }

    @Test
    fun `broadcast voice mapping`() {
        assertEquals("en-US-AndrewNeural", broadcastVoiceOf("en-US-Andrew:DragonHDLatestNeural"))
        assertEquals("en-GB-SoniaNeural", broadcastVoiceOf("en-GB-Sonia:DragonHDLatestNeural"))
        // 已经是标准音色（或用户自填的名字）就别乱改。
        assertEquals("en-US-JennyNeural", broadcastVoiceOf("en-US-JennyNeural"))
    }

    @Test
    fun `rate cycles through all values`() {
        assertEquals(SpeechRate.Normal, SpeechRate.Slow.next())
        assertEquals(SpeechRate.Fast, SpeechRate.Normal.next())
        assertEquals(SpeechRate.Slow, SpeechRate.Fast.next())
    }

    @Test
    fun `unknown stored name falls back to normal`() {
        assertEquals(SpeechRate.Normal, SpeechRate.fromName("weird"))
        assertEquals(SpeechRate.Normal, SpeechRate.fromName(null))
        assertEquals(SpeechRate.Slow, SpeechRate.fromName("Slow"))
    }
}
