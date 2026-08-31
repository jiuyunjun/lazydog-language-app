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
    fun `reading the full english scores the same as getting it wrong`() {
        // 设计稿「英文放在最后一级」：看到完整英文才明白，和答错是同一件事。
        assertEquals(20, listeningScore(true, 1, ListeningHintLevel.FullText))
        assertEquals(20, listeningScore(correct = false, playCount = 1, hint = ListeningHintLevel.None))
    }

    @Test
    fun `the score preview matches what the next listen can still earn`() {
        // 答题页拿它预告"再听一遍这题最高几分"，必须和真实计分同源。
        assertEquals(85, baseScore(2))
        assertEquals(listeningScore(true, 3, ListeningHintLevel.None), baseScore(3))
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
            correctAnswer(plays = 1, hint = ListeningHintLevel.None),
            correctAnswer(plays = 1, hint = ListeningHintLevel.None),
            correctAnswer(plays = 3, hint = ListeningHintLevel.None),
            correctAnswer(plays = 2, hint = ListeningHintLevel.Scene),
            wrongAnswer(plays = 4, hint = ListeningHintLevel.Keyword),
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
            correctAnswer(plays = 1, hint = ListeningHintLevel.None, features = listOf("linking")),
            correctAnswer(plays = 1, hint = ListeningHintLevel.None, features = listOf("linking")),
            wrongAnswer(plays = 3, hint = ListeningHintLevel.None, features = listOf("reduction")),
        )
        // linking 出现得更多，但那两句都是一遍就听懂的，不能算成弱项。
        assertEquals("reduction", summarizeListening(answers).weakestFeature)
    }

    @Test
    fun `the summary reports which kind of mishearing tripped the user up`() {
        // 设计稿：答错记录的是"你栽在哪一类"，不是"你答错了"。
        val answers = listOf(
            wrongAnswer(plays = 2, hint = ListeningHintLevel.None, pick = 0),
            wrongAnswer(plays = 2, hint = ListeningHintLevel.None, pick = 1),
            wrongAnswer(plays = 2, hint = ListeningHintLevel.None, pick = 1),
        )
        assertEquals(MishearType.Negation, summarizeListening(answers).weakestMishear)
    }

    @Test
    fun `an all-correct round has nothing to replay and no weak spot`() {
        val summary = summarizeListening(listOf(correctAnswer(plays = 1, hint = ListeningHintLevel.None)))
        assertNull(summary.weakestFeature)
        assertNull(summary.weakestMishear)
        assertTrue(summary.worthReplaying.isEmpty())
    }

    @Test
    fun `anything not understood on the first listen is worth replaying`() {
        val answers = listOf(
            correctAnswer(plays = 1, hint = ListeningHintLevel.None),
            correctAnswer(plays = 2, hint = ListeningHintLevel.None),
            wrongAnswer(plays = 1, hint = ListeningHintLevel.None),
        )
        assertEquals(2, summarizeListening(answers).worthReplaying.size)
    }

    private fun correctAnswer(
        plays: Int,
        hint: ListeningHintLevel,
        features: List<String> = listOf("linking"),
    ) = ListeningAnswer(sampleItem(features), sampleItem(features).meaningZh, plays, hint)

    /** [pick] 选的是第几条干扰项，用来控制命中哪一类误听。 */
    private fun wrongAnswer(
        plays: Int,
        hint: ListeningHintLevel,
        features: List<String> = listOf("linking"),
        pick: Int = 0,
    ): ListeningAnswer {
        val item = sampleItem(features)
        return ListeningAnswer(item, item.distractors[pick].meaningZh, plays, hint)
    }
}

internal fun sampleItem(
    audioFeatures: List<String> = listOf("linking"),
    textEn: String = "I barely made it to the meeting on time.",
    keyEn: String = "barely made it",
    distractors: List<ListeningDistractor> = sampleDistractors(),
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
    distractors = distractors,
    sceneHintZh = "这句和迟到、赶时间有关",
    keywordHintZh = "注意听 barely，它和后面的 made 连读了",
)

internal fun sampleDistractors() = listOf(
    ListeningDistractor("我提前参加了会议", MishearType.KeyWord, "barely 被听成了 early。"),
    ListeningDistractor("我没能参加这场会议", MishearType.Negation, "漏掉 made it 会以为事情没做成。"),
    ListeningDistractor("会议准时结束了", MishearType.SimilarScene, "只抓到 the meeting on time。"),
)
