package com.lazydog.english.domain.speaking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PronunciationTipsTest {

    private fun feedback(vararg words: WordFeedback) = PronunciationFeedback(
        recognizedText = "text",
        accuracyScore = 80,
        fluencyScore = 80,
        completenessScore = 100,
        pronunciationScore = 80,
        words = words.toList(),
    )

    @Test
    fun `validation drops blank fields and caps at three`() {
        val tips = listOf(
            PronunciationTip(TipKind.Good, "", "body"),
            PronunciationTip(TipKind.Good, "title", ""),
            PronunciationTip(TipKind.Good, "ok1", "body1"),
            PronunciationTip(TipKind.Attention, "ok2", "body2"),
            PronunciationTip(TipKind.Attention, "ok3", "body3"),
            PronunciationTip(TipKind.Attention, "ok4", "body4"),
        )
        val validated = validatePronunciationTips(tips)
        assertEquals(3, validated.size)
        assertEquals(listOf("ok1", "ok2", "ok3"), validated.map { it.titleZh })
    }

    @Test
    fun `local fallback praises a clean reading with no problem words`() {
        val clean = feedback(WordFeedback("hello", 95, WordErrorType.None))
        val tips = localPronunciationTips(clean)
        assertEquals(1, tips.size)
        assertEquals(TipKind.Good, tips.first().kind)
    }

    @Test
    fun `local fallback flags problem words without ever printing a number`() {
        val messy = feedback(
            WordFeedback("kitchen", 40, WordErrorType.Mispronunciation),
            WordFeedback("the", 90, WordErrorType.None),
        )
        val tips = localPronunciationTips(messy)
        assertEquals(1, tips.size)
        assertTrue(tips.first().titleZh.contains("kitchen"))
        assertTrue(tips.none { it.titleZh.any(Char::isDigit) || it.bodyZh.any(Char::isDigit) })
    }

    @Test
    fun `local fallback caps at three problem words`() {
        val messy = feedback(
            WordFeedback("a", 10, WordErrorType.Mispronunciation),
            WordFeedback("b", 10, WordErrorType.Omission),
            WordFeedback("c", 10, WordErrorType.Insertion),
            WordFeedback("d", 10, WordErrorType.Mispronunciation),
        )
        assertEquals(3, localPronunciationTips(messy).size)
    }
}
