package com.lazydog.english.domain.vocabulary

import org.junit.Assert.*
import org.junit.Test

class ScanQualityTest {
    private fun line(y: Float, vararg text: String, x: Float = 0f): ScannedLine = ScannedLine(
        text.mapIndexed { index, word -> ScannedWord(word, TextBox(x + index * 50, y, x + index * 50 + 40, y + 20), 0.95f) },
    )
    private fun target(text: String) = ScanTarget("camera", text, emptyList(), emptyList(), confirmedFrames = 3)

    @Test fun `reported mixed screenshot garbage is recognised as noisy text`() {
        val bad = "STBE970852c,validate camera scan words and sentence matching (A A09AS7E), FRERSZ EITES, t7, A lint Gt E85 push (R4 T0E970852c)."
        assertEquals(ScanIssue.NoisyText, ScanQuality.textIssue(bad, ScanMode.Sentence))
        assertNull(ScanQuality.textIssue("validate camera scan words and sentence matching", ScanMode.Sentence))
    }

    @Test fun `Chinese separates English runs instead of joining both sides`() {
        val blocks = listOf(ScannedBlock(listOf(
            line(0f, "标题是", "validate", "camera", "scan", "words", "and", "sentence", "matching", "校验扫描"),
            line(30f, "但没跑", "lint", "和单元测试"),
            line(60f, "我的", "970852c"),
        )))
        val result = ScanAim.aim(blocks, 170f, 10f, 5f)!!
        assertEquals("validate camera scan words and sentence matching", result.sentence)
        assertEquals(7, result.sentenceBoxes.size)
        assertEquals(2, result.wordIndex)
        assertFalse(result.sentence.contains("lint"))
    }

    @Test fun `large line gaps and separate columns never form one sentence`() {
        val gaps = listOf(ScannedBlock(listOf(line(0f, "The", "first", "line"), line(110f, "Another", "paragraph"))))
        assertEquals(listOf("The first line", "Another paragraph"), ScanAim.sentencesIn(gaps))
        val columns = listOf(ScannedBlock(listOf(line(0f, "Left", "column"), line(30f, "Right", "column", x = 300f))))
        assertEquals(listOf("Left column", "Right column"), ScanAim.sentencesIn(columns))
    }

    @Test fun `ordinary English wraps still join`() {
        val blocks = listOf(ScannedBlock(listOf(line(0f, "We", "read", "the"), line(30f, "book", "today."))))
        assertEquals(listOf("We read the book today."), ScanAim.sentencesIn(blocks))
    }

    @Test fun `wide gaps within a line also stop next line joining`() {
        val columns = ScannedLine(line(0f, "Left", "column").words + line(0f, "Right", "column", x = 300f).words)
        val blocks = listOf(ScannedBlock(listOf(columns, line(30f, "Next", "line"))))
        assertEquals(listOf("Left column", "Right column", "Next line"), ScanAim.sentencesIn(blocks))
    }

    @Test fun `Chinese only has no target and a long unpunctuated block is bounded`() {
        assertNull(ScanAim.aim(listOf(ScannedBlock(listOf(line(0f, "只包含中文")))), 20f, 10f, 5f))
        val blocks = listOf(ScannedBlock((0..10).map { line(it * 30f, "Repeated", "text") }))
        assertTrue(ScanAim.aim(blocks, 20f, 10f, 5f)!!.sentenceBoxes.size <= 12)
    }

    @Test fun `proper nouns abbreviations uncommon words and decimals are allowed`() {
        listOf("NASA studies photosynthesis.", "Dr. Smith paid 3.50 dollars.", "I have 2 cats.", "PLEASE KEEP THIS DOOR CLOSED").forEach {
            assertNull(it, ScanQuality.textIssue(it, ScanMode.Sentence))
        }
        assertNull(ScanQuality.textIssue("photosynthesis", ScanMode.Word))
        assertNotNull(ScanQuality.textIssue("adb_install --device=970852c", ScanMode.Sentence))
    }

    @Test fun `bad context is not attached to a valid word lookup`() {
        assertEquals("", target("STBE970852c camera FRERSZ A09AS7E").contextSentence)
    }

    @Test fun `still recognition preserves quality evidence`() {
        val live = target("The camera works.").copy(wordIndex = 1)
        val still = listOf(ScannedBlock(listOf(ScannedLine(line(0f, "The", "camera", "works.").words.map { it.copy(confidence = 0.2f) }))))
        val refined = ScanRefine.refine(live, still)!!
        assertTrue(refined.sentenceConfidence!! < ScanQuality.MIN_CONFIDENCE)
    }
}
