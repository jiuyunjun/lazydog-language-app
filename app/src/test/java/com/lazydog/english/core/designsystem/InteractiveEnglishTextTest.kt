package com.lazydog.english.core.designsystem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InteractiveEnglishTextTest {
    private val text = "The room is unavailable. Could you refund the difference?"

    @Test
    fun `word lookup uses tapped character offset`() {
        assertEquals("unavailable", wordAt(text, text.indexOf("available") + 2))
        assertNull(wordAt(text, text.indexOf('.')))
    }

    @Test
    fun `sentence lookup keeps only tapped sentence`() {
        assertEquals(
            "Could you refund the difference?",
            sentenceAround(text, text.indexOf("refund")),
        )
    }

    @Test
    fun `partial json value is readable before response finishes`() {
        assertEquals(
            "这里指控制\n车流",
            partialJsonStringValue(
                """```json {"meaningZh":"控制","usageNoteZh":"这里指控制\n车流"""",
                "usageNoteZh",
            ),
        )
    }

    @Test
    fun `partial json value tolerates fields not received yet`() {
        assertEquals("", partialJsonStringValue("""{"translationZh":"译文"}""", "explanationZh"))
    }
}
