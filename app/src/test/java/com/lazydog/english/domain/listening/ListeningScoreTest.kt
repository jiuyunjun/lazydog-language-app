package com.lazydog.english.domain.listening

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListeningScoreTest {

    @Test
    fun `play count decides the base score`() {
        assertEquals(100, listeningScore(correct = true, playCount = 1, hint = ListeningHintLevel.None))
        assertEquals(85, listeningScore(correct = true, playCount = 2, hint = ListeningHintLevel.None))
        assertEquals(70, listeningScore(correct = true, playCount = 3, hint = ListeningHintLevel.None))
        assertEquals(60, listeningScore(correct = true, playCount = 9, hint = ListeningHintLevel.None))
    }

    @Test
    fun `hints cost more the later you understand`() {
        assertEquals(85, listeningScore(true, 1, ListeningHintLevel.Scene))
        assertEquals(70, listeningScore(true, 1, ListeningHintLevel.Keyword))
    }

    @Test
    fun `partial text is capped at fifty even on the first listen`() {
        // §21"看部分字幕：最多 50"——挖空英文已经算看了字幕，一遍听懂也不能给高分。
        assertEquals(50, listeningScore(true, 1, ListeningHintLevel.PartialText))
        assertEquals(30, listeningScore(true, 9, ListeningHintLevel.PartialText))
    }

    @Test
    fun `a wrong answer scores the same as understanding only after the full text`() {
        assertEquals(20, listeningScore(correct = false, playCount = 1, hint = ListeningHintLevel.None))
        assertEquals(20, listeningScore(correct = false, playCount = 9, hint = ListeningHintLevel.PartialText))
    }

    @Test
    fun `key expression is blanked out for the partial-text hint`() {
        assertEquals(
            "I _____ to the meeting on time.",
            maskKeyExpression("I barely made it to the meeting on time.", "barely made it"),
        )
    }

    @Test
    fun `blanking falls back to the longest word when the expression does not match`() {
        // 关键表达对不上时不能整句照抄——那等于把答案直接给出去。
        val masked = maskKeyExpression("I made it on time.", "totally different")
        assertTrue(masked.contains("_____"))
        assertTrue(masked != "I made it on time.")
    }

    @Test
    fun `summary buckets are mutually exclusive and cover every answer`() {
        val answers = listOf(
            answer(correct = true, plays = 1, hint = ListeningHintLevel.None),
            answer(correct = true, plays = 1, hint = ListeningHintLevel.None),
            answer(correct = true, plays = 3, hint = ListeningHintLevel.None),
            answer(correct = true, plays = 2, hint = ListeningHintLevel.Scene),
            answer(correct = false, plays = 4, hint = ListeningHintLevel.Keyword),
        )
        val summary = summarizeListening(answers)
        assertEquals(2, summary.firstListenCount)
        assertEquals(1, summary.repeatListenCount)
        assertEquals(1, summary.afterHintCount)
        assertEquals(1, summary.missedCount)
        assertEquals(
            summary.total,
            summary.firstListenCount + summary.repeatListenCount +
                summary.afterHintCount + summary.missedCount,
        )
        assertEquals(2.2, summary.averagePlays, 0.001)
    }

    @Test
    fun `the weakest feature comes only from sentences that were not understood first time`() {
        val answers = listOf(
            answer(correct = true, plays = 1, hint = ListeningHintLevel.None, features = listOf("linking")),
            answer(correct = true, plays = 1, hint = ListeningHintLevel.None, features = listOf("linking")),
            answer(correct = false, plays = 3, hint = ListeningHintLevel.None, features = listOf("reduction")),
        )
        // linking 出现得更多，但那两句都是一遍就听懂的，不能算成弱项。
        assertEquals("reduction", summarizeListening(answers).weakestFeature)
    }

    @Test
    fun `an all-correct round has no weakest feature`() {
        val answers = listOf(answer(correct = true, plays = 1, hint = ListeningHintLevel.None))
        assertNull(summarizeListening(answers).weakestFeature)
    }

    private fun answer(
        correct: Boolean,
        plays: Int,
        hint: ListeningHintLevel,
        features: List<String> = listOf("linking"),
    ) = ListeningAnswer(
        item = sampleItem(features),
        correct = correct,
        playCount = plays,
        hintLevel = hint,
    )
}

internal fun sampleItem(
    audioFeatures: List<String> = listOf("linking"),
    textEn: String = "I barely made it to the meeting on time.",
    keyEn: String = "barely made it",
    wrong: List<String> = listOf("我提前参加了会议", "我几乎没有参加会议"),
) = ListeningItem(
    textEn = textEn,
    meaningZh = "我勉强准时赶到了会议",
    sceneZh = "商务职场",
    subSceneZh = "会议",
    intentZh = "解释",
    toneZh = "Nervous",
    registerZh = "口语",
    cefr = "B1",
    listeningDifficulty = 3,
    audioFeatures = audioFeatures,
    keyExpression = ListeningKeyExpression(keyEn, "差一点没赶上"),
    wrongMeaningsZh = wrong,
    sceneHintZh = "这句和迟到、赶时间有关",
    keywordHintZh = "注意听 barely，它和后面的 made 连读了",
)
