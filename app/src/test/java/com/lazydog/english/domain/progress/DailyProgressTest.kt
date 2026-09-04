package com.lazydog.english.domain.progress

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 战报的口径（`持续学习DESIGN.md` §14.1、§6）。 */
class DailyProgressTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val today: LocalDate = LocalDate.of(2026, 9, 4)

    private fun at(day: LocalDate, hour: Int): Instant =
        day.atTime(hour, 0).atZone(zone).toInstant()

    private fun review(itemId: Long, day: LocalDate, hour: Int, remembered: Boolean) =
        ProgressEvent(itemId, ProgressActivity.Review, remembered, at(day, hour))

    private fun create(itemId: Long, day: LocalDate, hour: Int) =
        ProgressEvent(itemId, ProgressActivity.Create, null, at(day, hour))

    @Test
    fun `只统计今天的事件`() {
        val events = listOf(
            create(1, today.minusDays(1), 9),
            review(1, today.minusDays(1), 10, remembered = true),
            create(2, today, 9),
            review(2, today, 10, remembered = true),
            review(3, today, 11, remembered = false),
        )
        val progress = dailyProgress(events, today, zone)
        assertEquals(1, progress.learned)
        assertEquals(2, progress.reviewed)
        assertEquals(1, progress.remembered)
        assertEquals(50, progress.rememberedPercent)
    }

    @Test
    fun `上次忘了今天想起来才算翻身`() {
        val events = listOf(
            review(7, today.minusDays(3), 9, remembered = false),
            review(8, today.minusDays(3), 9, remembered = true),
            review(7, today, 9, remembered = true),
            // 上次就记得的，今天再记得一次不算"重新记住"，那是保持不是翻身。
            review(8, today, 9, remembered = true),
        )
        assertEquals(listOf(7L), dailyProgress(events, today, zone).recovered)
    }

    @Test
    fun `同一个词今天做对多次只报一次翻身`() {
        val events = listOf(
            review(7, today.minusDays(1), 9, remembered = false),
            review(7, today, 9, remembered = true),
            review(7, today, 10, remembered = true),
        )
        assertEquals(listOf(7L), dailyProgress(events, today, zone).recovered)
    }

    @Test
    fun `今天先忘后想起来也算翻身`() {
        val events = listOf(
            review(7, today.minusDays(1), 9, remembered = false),
            review(7, today, 9, remembered = false),
            review(7, today, 10, remembered = true),
        )
        val progress = dailyProgress(events, today, zone)
        assertEquals(listOf(7L), progress.recovered)
        assertEquals(2, progress.reviewed)
        assertEquals(1, progress.remembered)
    }

    @Test
    fun `没考过不等于全错`() {
        val progress = dailyProgress(listOf(create(1, today, 9)), today, zone)
        assertNull(progress.rememberedPercent)
        assertTrue(progress.hasAnything)
    }

    @Test
    fun `在语境里遇见不算进战报`() {
        val events = listOf(ProgressEvent(1, ProgressActivity.Exposure, null, at(today, 9)))
        val progress = dailyProgress(events, today, zone)
        assertEquals(DailyProgress.Empty, progress)
        assertFalse(progress.hasAnything)
    }

    @Test
    fun `最低目标：五次回忆，或者走完任意一步`() {
        val fourRetrievals = DailyProgress(learned = 0, reviewed = 4, remembered = 4, recovered = emptyList())
        assertFalse(reachedDailyMinimum(fourRetrievals, doneStepCount = 0))
        assertTrue(reachedDailyMinimum(fourRetrievals, doneStepCount = 1))

        val fiveRetrievals = fourRetrievals.copy(reviewed = MINIMUM_RETRIEVALS)
        assertTrue(reachedDailyMinimum(fiveRetrievals, doneStepCount = 0))
    }
}
