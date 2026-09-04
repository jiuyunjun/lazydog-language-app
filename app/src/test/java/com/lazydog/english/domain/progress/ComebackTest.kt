package com.lazydog.english.domain.progress

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

/** 中断回归与疲劳（`持续学习DESIGN.md` §25、§26）。 */
class ComebackTest {

    private var clock = Instant.parse("2026-09-04T01:00:00Z")

    private fun review(remembered: Boolean): ProgressEvent {
        clock = clock.plusSeconds(30)
        return ProgressEvent(itemId = 1, activity = ProgressActivity.Review, remembered = remembered, at = clock)
    }

    private fun reviews(correct: Int, wrong: Int) =
        List(correct) { review(true) } + List(wrong) { review(false) }

    @Test
    fun `断三天以上才走热身流程`() {
        assertEquals(Mood.Normal, mood(daysAway = 0, fatigue = Fatigue.Fine))
        assertEquals(Mood.Normal, mood(daysAway = 2, fatigue = Fatigue.Fine))
        assertEquals(Mood.Comeback, mood(daysAway = 3, fatigue = Fatigue.Fine))
        assertEquals(Mood.Comeback, mood(daysAway = 30, fatigue = Fatigue.Fine))
    }

    @Test
    fun `刚回来的人先听到欢迎回来，而不是你看起来累了`() {
        // 他今天还没答几道题，谈不上累；中断的优先级高于疲劳。
        assertEquals(Mood.Comeback, mood(daysAway = 5, fatigue = Fatigue.Tired))
    }

    @Test
    fun `做得不多就不算累`() {
        // 五道题错三道更可能是这批词难，不是人累了。
        assertEquals(Fatigue.Fine, fatigue(reviews(correct = 2, wrong = 3)))
    }

    @Test
    fun `做得多而且最近连着错才算累`() {
        val tired = reviews(correct = 12, wrong = 0) + reviews(correct = 0, wrong = 3)
        assertEquals(Fatigue.Tired, fatigue(tired))
        assertEquals(Mood.Tired, mood(daysAway = 0, fatigue = fatigue(tired)))
    }

    @Test
    fun `做得多但最近手感还在，就不打扰`() {
        val fine = reviews(correct = 3, wrong = 3) + reviews(correct = 10, wrong = 0)
        assertEquals(Fatigue.Fine, fatigue(fine))
    }

    @Test
    fun `新建和遇见不参与疲劳判断`() {
        val noise = List(20) { ProgressEvent(1, ProgressActivity.Create, null, clock) }
        assertEquals(Fatigue.Fine, fatigue(noise))
    }
}
