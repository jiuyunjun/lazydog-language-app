package com.lazydog.english.domain.vocabulary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ScanAimTest {
    @Test
    fun `misread alphanumeric words are not silently truncated`() {
        listOf("wor1d", "1ife", "abc123", "hello/world").forEach {
            assertNull(it, ScanAim.englishWordIn(it))
        }
        assertEquals("well-known", ScanAim.englishWordIn("(well-known),"))
    }

    @Test
    fun `stickiness never extends the maximum aiming distance`() {
        val blocks = listOf(ScannedBlock(listOf(line(0f, "alpha"))))
        val locked = ScanAim.aim(blocks, 20f, 10f, 10f)!!
        assertNull(ScanAim.aim(blocks, 55f, 10f, 10f, prefer = locked, stickiness = 12f))
    }

    @Test
    fun `sentence boundaries preserve titles initials decimals and closing quotes`() {
        val text = "Dr. A. Smith paid 3.50 dollars. “Really?!” Yes."
        fun sentence(word: String) = text.substring(sentenceRange(text, text.indexOf(word))).trim()
        assertEquals("Dr. A. Smith paid 3.50 dollars.", sentence("Smith"))
        assertEquals("Dr. A. Smith paid 3.50 dollars.", sentence("dollars"))
        assertEquals("“Really?!”", sentence("Really"))
        assertEquals("Yes.", sentence("Yes"))
        val blocks = listOf(ScannedBlock(listOf(line(0f, "Dr.", "Smith", "left.", "“Really?!”", "Yes."))))
        assertEquals(listOf("Dr. Smith left.", "“Really?!”", "Yes."), ScanAim.sentencesIn(blocks))
    }

    @Test
    fun `dotted abbreviations inside a sentence do not create fragments`() {
        val text = "Use examples, e.g. a U.S. city. Then stop."
        assertEquals("Use examples, e.g. a U.S. city.", text.substring(sentenceRange(text, text.indexOf("city"))))
    }

    /** 一行字，每个词 40 宽、20 高，词间隔 10。 */
    private fun line(y: Float, vararg words: String, startX: Float = 0f): ScannedLine {
        var x = startX
        return ScannedLine(
            words.map { text ->
                ScannedWord(text, TextBox(x, y, x + 40f, y + 20f)).also { x += 50f }
            },
        )
    }

    @Test
    fun `crosshair inside a word box picks that word without punctuation`() {
        val blocks = listOf(ScannedBlock(listOf(line(0f, "Hello,", "brave", "world."))))

        val target = ScanAim.aim(blocks, x = 120f, y = 10f, tolerance = 10f)!!

        assertEquals("world", target.word)
        assertEquals("Hello, brave world.", target.sentence)
    }

    @Test
    fun `crosshair in the gap snaps to the nearest word within tolerance`() {
        val blocks = listOf(ScannedBlock(listOf(line(0f, "alpha", "beta"))))

        // 第一个词右边界 40，第二个词左边界 50；43 离 alpha 更近。
        assertEquals("alpha", ScanAim.aim(blocks, 43f, 10f, 10f)!!.word)
        assertEquals("beta", ScanAim.aim(blocks, 48f, 10f, 10f)!!.word)
    }

    @Test
    fun `blank area farther than tolerance aims at nothing`() {
        val blocks = listOf(ScannedBlock(listOf(line(0f, "alpha"))))

        assertNull(ScanAim.aim(blocks, 300f, 300f, 28f))
    }

    @Test
    fun `numbers and non latin tokens are never targets`() {
        val blocks = listOf(ScannedBlock(listOf(line(0f, "2024", "你好", "cat"))))

        // 准星压在数字上，但数字不是词；最近的英文词 cat 在 100 之外，超出容差。
        assertNull(ScanAim.aim(blocks, 20f, 10f, 10f))
        assertEquals("cat", ScanAim.aim(blocks, 120f, 10f, 10f)!!.word)
    }

    @Test
    fun `curly apostrophe is normalised and kept inside the word`() {
        val blocks = listOf(ScannedBlock(listOf(line(0f, "“Don’t”"))))

        assertEquals("Don't", ScanAim.aim(blocks, 20f, 10f, 5f)!!.word)
    }

    @Test
    fun `sentence spans lines and stops at sentence ends`() {
        val blocks = listOf(
            ScannedBlock(
                listOf(
                    line(0f, "It", "rained.", "We", "stayed"),
                    line(30f, "inside", "all", "day.", "Then"),
                ),
            ),
        )

        val target = ScanAim.aim(blocks, 70f, 40f, 5f)!!

        assertEquals("all", target.word)
        assertEquals("We stayed inside all day.", target.sentence)
        // We / stayed / inside / all / day. 五个框整句高亮。
        assertEquals(5, target.sentenceBoxes.size)
    }

    @Test
    fun `word broken by a hyphen at line end is joined back`() {
        val blocks = listOf(
            ScannedBlock(
                listOf(
                    line(0f, "A", "beauti-"),
                    line(30f, "ful", "day."),
                ),
            ),
        )

        val fromSecondHalf = ScanAim.aim(blocks, 20f, 40f, 5f)!!
        assertEquals("beautiful", fromSecondHalf.word)
        assertEquals(2, fromSecondHalf.wordBoxes.size)
        assertEquals("A beautiful day.", fromSecondHalf.sentence)
        assertEquals("beautiful", ScanAim.aim(blocks, 70f, 10f, 5f)!!.word)
    }

    @Test
    fun `hyphen before a capitalised next line is not a broken word`() {
        val blocks = listOf(ScannedBlock(listOf(line(0f, "well-"), line(30f, "Known"))))

        assertEquals("well", ScanAim.aim(blocks, 20f, 10f, 5f)!!.word)
        assertEquals("Known", ScanAim.aim(blocks, 20f, 40f, 5f)!!.word)
    }

    @Test
    fun `sentences never cross blocks`() {
        val blocks = listOf(
            ScannedBlock(listOf(line(0f, "Chapter", "One"))),
            ScannedBlock(listOf(line(100f, "The", "night", "was", "cold."))),
        )

        assertEquals("Chapter One", ScanAim.aim(blocks, 20f, 10f, 5f)!!.sentence)
    }

    @Test
    fun `short labels give no context sentence`() {
        val heading = ScanAim.aim(listOf(ScannedBlock(listOf(line(0f, "Chapter", "One")))), 20f, 10f, 5f)!!
        val prose = ScanAim.aim(listOf(ScannedBlock(listOf(line(0f, "The", "night", "was", "cold.")))), 20f, 10f, 5f)!!

        assertEquals("", heading.contextSentence)
        assertEquals("The night was cold.", prose.contextSentence)
    }

    @Test
    fun `stickiness keeps the locked word when a neighbour is only slightly closer`() {
        val blocks = listOf(ScannedBlock(listOf(line(0f, "alpha", "beta"))))
        val locked = ScanAim.aim(blocks, 43f, 10f, 10f)!!

        // 48 离 beta 2、离 alpha 8；不给粘性会换成 beta，给 12 的粘性就留在 alpha。
        assertEquals("beta", ScanAim.aim(blocks, 48f, 10f, 10f)!!.word)
        assertEquals("alpha", ScanAim.aim(blocks, 48f, 10f, 10f, prefer = locked, stickiness = 12f)!!.word)
        // 真挪过去了（准星压进 beta 深处）粘性也留不住。
        assertEquals("beta", ScanAim.aim(blocks, 85f, 10f, 10f, prefer = locked, stickiness = 12f)!!.word)
    }

    @Test
    fun `same text on another line is a different word`() {
        val blocks = listOf(ScannedBlock(listOf(line(0f, "the", "cat."), line(100f, "the", "dog."))))
        val top = ScanAim.aim(blocks, 20f, 10f, 5f)!!
        val bottom = ScanAim.aim(blocks, 20f, 110f, 5f)!!

        assertEquals(false, ScanAim.sameWord(top, bottom))
        assertEquals(true, ScanAim.sameWord(top, top))
    }

    @Test
    fun `sentence range matches the tap lookup rules`() {
        val text = "First one. Second one! Third?"

        assertEquals(" Second one!", text.substring(sentenceRange(text, 14).first, sentenceRange(text, 14).last + 1))
        assertEquals(IntRange.EMPTY, sentenceRange("", 0))
    }
}
