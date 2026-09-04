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
    fun `今天和昨天都没学，但昨天可以算这周的休息`() {
        // 宽容规则之前这里是"断了"。现在缺一天不算断，连着缺两天才算（见下面那条）。
        val activity = learningActivity(days(2, 3, 4), today)
        assertEquals(3, activity.currentStreak)
        assertEquals(1, activity.restDaysUsed)
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

    @Test
    fun `每周允许休一天，连续不断`() {
        // 一路学到昨天，只有三天前那一天空着——那是这一周的休息（§7.2）。
        val activity = learningActivity(days(0, 1, 2, 4, 5, 6), today)
        assertEquals(6, activity.currentStreak)
        assertEquals(1, activity.restDaysUsed)
    }

    @Test
    fun `连着缺两天就是真的断了`() {
        val activity = learningActivity(days(0, 3, 4, 5), today)
        assertEquals(1, activity.currentStreak)
        assertEquals(0, activity.restDaysUsed)
    }

    @Test
    fun `一周只能休一天，第二次缺勤就断`() {
        // 缺 2 和缺 5：两次相隔三天，不到七天，第二次不再给宽容。
        val activity = learningActivity(days(0, 1, 3, 4, 6, 7), today)
        assertEquals(4, activity.currentStreak)
        assertEquals(1, activity.restDaysUsed)
    }

    @Test
    fun `昨天休了、今天还没学，六天的连续要保住`() {
        // 周一到周六学、周日休、今天周一还没开始：这正是宽容规则要保的场景。
        val activity = learningActivity(days(2, 3, 4, 5, 6, 7), today)
        assertEquals(6, activity.currentStreak)
        assertEquals(1, activity.restDaysUsed)
    }

    @Test
    fun `很久没学就是断了，不算休息`() {
        val activity = learningActivity(days(20, 21, 22), today)
        assertEquals(0, activity.currentStreak)
        assertEquals(0, activity.restDaysUsed)
        assertEquals(20, activity.daysAway)
    }

    @Test
    fun `今天学过，离上次就是零天`() {
        assertEquals(0, learningActivity(days(0, 1), today).daysAway)
    }
}
