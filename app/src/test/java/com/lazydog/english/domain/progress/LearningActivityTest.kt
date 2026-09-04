package com.lazydog.english.domain.progress

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/** 活跃度三个数（`持续学习DESIGN.md` §7.1）。 */
class LearningActivityTest {

    private val today: LocalDate = LocalDate.of(2026, 9, 4)

    private fun days(vararg daysAgo: Int): Set<LocalDate> =
        daysAgo.map { today.minusDays(it.toLong()) }.toSet()

    @Test
    fun `没学过就是三个零`() {
        assertEquals(LearningActivity.None, learningActivity(emptySet(), today))
    }

    @Test
    fun `连续从今天往回数`() {
        assertEquals(3, learningActivity(days(0, 1, 2, 5), today).currentStreak)
    }

    @Test
    fun `今天还没学不算断`() {
        // 零点一过就归零的话，用户睁眼看到的是"连续 0 天"——那是在为难人。
        assertEquals(2, learningActivity(days(1, 2), today).currentStreak)
    }

    @Test
    fun `今天和昨天都没学才算断`() {
        assertEquals(0, learningActivity(days(2, 3, 4), today).currentStreak)
    }

    @Test
    fun `旅程含头尾，断过也照算`() {
        val activity = learningActivity(days(0, 9), today)
        assertEquals(10, activity.journeyDays)
        assertEquals(1, activity.currentStreak)
    }

    @Test
    fun `最近三十天只看窗口内的`() {
        val activity = learningActivity(days(0, 5, 29, 30, 60), today)
        assertEquals(3, activity.activeDaysIn30)
        // 连续断了，旅程和最近三十天还在——这就是三个数一起给的意义。
        assertEquals(61, activity.journeyDays)
    }
}
