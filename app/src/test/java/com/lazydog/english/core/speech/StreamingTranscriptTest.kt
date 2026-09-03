package com.lazydog.english.core.speech

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamingTranscriptTest {

    @Test
    fun `interim text replaces the previous draft instead of duplicating it`() {
        val transcript = StreamingTranscript()

        assertEquals("我想", transcript.preview("我想"))
        assertEquals("我想问", transcript.preview("我想问"))
        assertEquals("我想问这个词。", transcript.commit("我想问这个词。"))
        assertEquals("我想问这个词。 why", transcript.preview("why"))
        assertEquals("我想问这个词。 why is it used here?", transcript.preview("why is it used here?"))
    }

    @Test
    fun `final transcript contains only committed utterances`() {
        val transcript = StreamingTranscript()

        transcript.commit("第一句。")
        transcript.preview("还没定稿")
        transcript.commit("Second sentence.")

        assertEquals("第一句。 Second sentence.", transcript.finalText())
    }
}
