package com.lazydog.english.domain.progress

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 难度偏置的口径（`持续学习DESIGN.md` §11）。 */
class DifficultyTest {

    private var clock = Instant.parse("2026-09-04T00:00:00Z")

    private fun review(remembered: Boolean): ProgressEvent {
        clock = clock.plusSeconds(60)
        return ProgressEvent(itemId = 1, activity = ProgressActivity.Review, remembered = remembered, at = clock)
    }

    private fun reviews(correct: Int, wrong: Int): List<ProgressEvent> =
        List(correct) { review(true) } + List(wrong) { review(false) }

    @Test
    fun `样本不够就不下结论`() {
        val accuracy = recentAccuracy(reviews(correct = 5, wrong = 0))
        assertNull(accuracy.percent)
        // 五次全对不代表题太浅，可能只是刚开始练。
        assertEquals(DifficultyBias.Steady, difficultyBias(accuracy))
    }

    @Test
    fun `太顺就问深一点`() {
        val accuracy = recentAccuracy(reviews(correct = 19, wrong = 1))
        assertEquals(95, accuracy.percent)
        assertEquals(DifficultyBias.Harder, difficultyBias(accuracy))
    }

    @Test
    fun `太吃力就给脚手架`() {
        val accuracy = recentAccuracy(reviews(correct = 10, wrong = 10))
        assertEquals(50, accuracy.percent)
        assertEquals(DifficultyBias.Easier, difficultyBias(accuracy))
    }

    @Test
    fun `目标区间里不动`() {
        // 80% 正落在 §11 想维持的 75~85% 中间。
        val accuracy = recentAccuracy(reviews(correct = 16, wrong = 4))
        assertEquals(80, accuracy.percent)
        assertEquals(DifficultyBias.Steady, difficultyBias(accuracy))
    }

    @Test
    fun `只数最近这些次，早年的表现不该压住今天`() {
        // 先来一长串全对，再来一串全错：窗口应该只看得见后面这些。
        val events = reviews(correct = 60, wrong = 0) + reviews(correct = 0, wrong = 30)
        val accuracy = recentAccuracy(events)
        assertEquals(RecentAccuracy.WINDOW, accuracy.attempts)
        assertEquals(25, accuracy.percent)
        assertEquals(DifficultyBias.Easier, difficultyBias(accuracy))
    }

    @Test
    fun `新建和遇见不算提取`() {
        val noise = listOf(
            ProgressEvent(1, ProgressActivity.Create, null, clock),
            ProgressEvent(1, ProgressActivity.Exposure, null, clock),
        )
        assertEquals(RecentAccuracy.Unknown, recentAccuracy(noise))
    }
}
