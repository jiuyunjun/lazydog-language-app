package com.lazydog.english.domain.vocabulary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanTextFusionTest {
    @Test
    fun `refine rejects ambiguous similar sentences regardless of block order`() {
        val live = ScanTarget("cat", "The cat sat here.", listOf(BOX), listOf(BOX), 1)
        val still = listOf(
            ScannedBlock(listOf(line("The", "cat", "sat", "here."))),
            ScannedBlock(listOf(line("The", "cat", "sat", "there."))),
        )
        assertNull(ScanRefine.refine(live, still))
        assertNull(ScanRefine.refine(live, still.reversed()))
    }

    @Test
    fun `refine never pairs a missing aimed word with a new sentence`() {
        val live = ScanTarget("quiet", "The morning was quiet and clear.", listOf(BOX), listOf(BOX), 3)
        val still = listOf(ScannedBlock(listOf(line("The", "morning", "was", "and", "clear."))))
        assertNull(ScanRefine.refine(live, still))
    }

    @Test
    fun `short isolated labels need exact evidence`() {
        val live = ScanTarget("cat", "cat", listOf(BOX), listOf(BOX), 0)
        assertNull(ScanRefine.refine(live, listOf(ScannedBlock(listOf(line("cut"))))))
        assertEquals("cat", ScanRefine.refine(live, listOf(ScannedBlock(listOf(line("cat")))))!!.word)
    }

    @Test
    fun `alignment keeps a misread word in its slot`() {
        val result = TokenAlign.align(
            listOf("The", "quick", "brown", "fox."),
            listOf("The", "qu1ck", "fox."),
        )

        assertEquals(listOf(0, 1, -1, 2), result.aToB.toList())
    }

    @Test
    fun `a misread in one frame is outvoted`() {
        val fusion = SentenceFusion()
        fusion.add("She opened the window.", 1)
        fusion.add("She opened the window.", 1)
        val fused = fusion.add("She opcned the wind0w.", 1)

        assertEquals("She opened the window.", fused.sentence)
        assertEquals("opened", fused.word)
        assertEquals(1, fused.wordIndex)
    }

    @Test
    fun `different misreads in different frames still converge on the right text`() {
        val fusion = SentenceFusion()
        val frames = listOf(
            "We stayed at home all day.",
            "We stayod at home all day.",
            "We stayed at hame all day.",
            "We stayed at home al day.",
            "We stayed at home all doy.",
        )
        val last = frames.map { fusion.add(it, 1) }.last()

        assertEquals("We stayed at home all day.", last.sentence)
    }

    @Test
    fun `a word misread at lock time is corrected by later frames`() {
        val fusion = SentenceFusion()
        assertNull(fusion.add("Hello wor1d today.", 1).word)
        fusion.add("Hello world today.", 1)

        assertEquals("world", fusion.add("Hello world today.", 1).word)
    }

    @Test
    fun `a word dropped in one frame is kept`() {
        val fusion = SentenceFusion()
        fusion.add("It is a long road.", 3)
        fusion.add("It is a long road.", 3)
        val fused = fusion.add("It is long road.", 2)

        assertEquals("It is a long road.", fused.sentence)
        assertEquals("long", fused.word)
    }

    @Test
    fun `moving to another sentence starts over`() {
        val fusion = SentenceFusion()
        repeat(4) { fusion.add("The cat sat on the mat.", 1) }
        val fused = fusion.add("Prices rose sharply last year.", 0)

        assertEquals("Prices rose sharply last year.", fused.sentence)
        assertEquals("Prices", fused.word)
    }

    @Test
    fun `sentences in blocks are split like aiming does`() {
        val blocks = listOf(
            ScannedBlock(
                listOf(
                    line("Rain", "fell."),
                    line("Then", "the", "sun", "came", "out."),
                ),
            ),
            ScannedBlock(listOf(line("Chapter", "Two"))),
        )

        assertEquals(listOf("Rain fell.", "Then the sun came out.", "Chapter Two"), ScanAim.sentencesIn(blocks))
    }

    @Test
    fun `refine replaces live misreads with the still's reading`() {
        val live = ScanTarget(
            word = "quiet",
            sentence = "The rnorning was quiet and cIear.",
            wordBoxes = listOf(BOX),
            sentenceBoxes = listOf(BOX),
            wordIndex = 3,
        )
        val still = listOf(
            ScannedBlock(listOf(line("Nothing", "else", "mattered."))),
            ScannedBlock(listOf(line("The", "morning", "was", "quiet"), line("and", "clear."))),
        )

        val refined = ScanRefine.refine(live, still)!!

        assertEquals("The morning was quiet and clear.", refined.sentence)
        assertEquals("quiet", refined.word)
        assertEquals(listOf(BOX), refined.wordBoxes)
    }

    @Test
    fun `refine fixes the aimed word when the still reads it better`() {
        val live = ScanTarget("rnorning", "The rnorning was quiet.", listOf(BOX), listOf(BOX), wordIndex = 1)
        val still = listOf(ScannedBlock(listOf(line("The", "morning", "was", "quiet."))))

        assertEquals("morning", ScanRefine.refine(live, still)!!.word)
    }

    @Test
    fun `refine gives up when the still shows something else`() {
        val live = ScanTarget("quiet", "The morning was quiet.", listOf(BOX), listOf(BOX), wordIndex = 3)
        val still = listOf(ScannedBlock(listOf(line("Totally", "unrelated", "page", "text."))))

        assertNull(ScanRefine.refine(live, still))
        assertNull(ScanRefine.refine(live, emptyList()))
    }

    @Test
    fun `aim reports the word index within its sentence`() {
        val blocks = listOf(ScannedBlock(listOf(line("Go.", "We", "stayed", "home."))))
        // 第三个词 stayed 的框在 x = 100..140。
        val target = ScanAim.aim(blocks, 120f, 10f, tolerance = 28f)!!

        assertEquals("We stayed home.", target.sentence)
        assertEquals(1, target.wordIndex)
        assertTrue(target.sentence.split(' ')[target.wordIndex].startsWith(target.word))
    }

    private fun line(vararg words: String): ScannedLine {
        var x = 0f
        return ScannedLine(words.map { ScannedWord(it, TextBox(x, 0f, x + 40f, 20f)).also { x += 50f } })
    }

    private companion object {
        val BOX = TextBox(0f, 0f, 10f, 10f)
    }
}
