package com.lazydog.english.domain.books

import org.junit.Assert.assertEquals
import org.junit.Test

class ChapterTextTest {

    @Test
    fun `chapter text is title then indented paragraphs`() {
        assertEquals("第一章\n　　他来了。\n　　他走了。", ChapterText.build("第一章", listOf("他来了。", "他走了。"), BookLanguage.Chinese))
        assertEquals("One\n  He came.", ChapterText.build("One", listOf("He came."), BookLanguage.English))
    }

    @Test
    fun `progress counts earlier chapters and clamps offsets`() {
        val counts = listOf(100, 300, 600)
        assertEquals(0.0, ChapterText.progress(counts, BookLocator.Start), 1e-9)
        assertEquals(0.25, ChapterText.progress(counts, BookLocator(1, 150)), 1e-9)
        // 从上一章末尾进来时偏移是 Int.MAX_VALUE，夹到本章字数。
        assertEquals(0.4, ChapterText.progress(counts, BookLocator(1, Int.MAX_VALUE)), 1e-9)
        assertEquals(0.0, ChapterText.progress(emptyList(), BookLocator(3, 10)), 1e-9)
    }

    @Test
    fun `font size stays within bounds`() {
        assertEquals(ReaderSettings.MAX_FONT, ReaderSettings(fontSizeSp = ReaderSettings.MAX_FONT).withFontSize(1).fontSizeSp)
        assertEquals(ReaderSettings.MIN_FONT, ReaderSettings(fontSizeSp = ReaderSettings.MIN_FONT).withFontSize(-1).fontSizeSp)
    }
}
