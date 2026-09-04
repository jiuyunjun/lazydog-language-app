package com.lazydog.english.core.designsystem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InteractiveEnglishTextTest {
    private val text = "The room is unavailable. Could you refund the difference?"

    @Test
    fun `word lookup uses tapped character offset`() {
        assertEquals("unavailable", wordAt(text, text.indexOf("available") + 2))
    }

    @Test
    fun `点在词边界外算成刚点过的那个词`() {
        // 原来这两种情况返回 null，实机上的表现是"点了没反应，而且时灵时不灵"：
        // getOffsetForPosition 给的是最近的字符边界，点在最后一个字母右半边就会
        // 落到词尾的下一个位置。长句里这一下会落进下一个词看不出来，
        // 阅读页目标词、词组小块那种整段就一个词的地方，那半个字母就是死区。
        assertEquals("unavailable", wordAt(text, text.indexOf('.')))
        assertEquals("difference", wordAt("difference", "difference".length))
        assertEquals("territory", wordAt("territory.", "territory.".length))
    }

    @Test
    fun `没有英文可查时仍然返回空`() {
        assertNull(wordAt("", 0))
        assertNull(wordAt("中文没有可查的词", 3))
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
