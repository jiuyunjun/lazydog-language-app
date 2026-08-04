package com.lazydog.english.core.speech

import com.lazydog.english.domain.speaking.SpeechRate
import org.junit.Assert.assertEquals
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
