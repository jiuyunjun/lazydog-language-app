package com.lazydog.english.domain.scheduling

import com.lazydog.english.core.model.KnowledgeStage
import com.lazydog.english.core.model.ReviewGrade
import java.time.Instant
import java.time.temporal.ChronoUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SimpleIntervalSchedulerTest {

    private val scheduler = SimpleIntervalScheduler()
    private val now: Instant = Instant.parse("2026-08-04T12:00:00Z")

    private fun initial() = MemoryState.initial(now)

    @Test
    fun `initial state is due next day and stage is exposed`() {
        val state = initial()
        assertEquals(now.plus(1, ChronoUnit.DAYS), state.nextReviewAt)
        assertEquals(KnowledgeStage.Exposed, deriveStage(state))
    }

    @Test
    fun `first good review schedules one day later`() {
        val state = scheduler.schedule(initial(), ReviewGrade.Good, now)
        assertEquals(1.0, state.stability, 1e-9)
        assertEquals(now.plus(1, ChronoUnit.DAYS), state.nextReviewAt)
        assertEquals(1, state.reviewCount)
        assertEquals(0, state.lapseCount)
        assertEquals(KnowledgeStage.Learning, deriveStage(state))
    }

    @Test
    fun `repeated good reviews grow the interval`() {
        var state = initial()
        repeat(4) { state = scheduler.schedule(state, ReviewGrade.Good, now) }
        // 1 → 2.5 → 6.25 → 15.625 天
        assertEquals(15.625, state.stability, 1e-9)
        assertEquals(KnowledgeStage.Familiar, deriveStage(state))
    }

    @Test
    fun `forgot shrinks stability increments lapse and re-schedules in ten minutes`() {
        var state = initial()
        repeat(3) { state = scheduler.schedule(state, ReviewGrade.Good, now) }
        val before = state.stability

        state = scheduler.schedule(state, ReviewGrade.Forgot, now)

        assertTrue(state.stability < before)
        assertEquals(1, state.lapseCount)
        assertEquals(now.plusSeconds(600), state.nextReviewAt)
    }

    @Test
    fun `difficulty stays in bounds`() {
        var state = initial()
        repeat(20) { state = scheduler.schedule(state, ReviewGrade.Forgot, now) }
        assertEquals(10.0, state.difficulty, 1e-9)
        repeat(40) { state = scheduler.schedule(state, ReviewGrade.Easy, now) }
        assertEquals(1.0, state.difficulty, 1e-9)
    }

    @Test
    fun `stability never exceeds cap`() {
        var state = initial()
        repeat(30) { state = scheduler.schedule(state, ReviewGrade.Easy, now) }
        assertTrue(state.stability <= SimpleIntervalScheduler.MAX_STABILITY_DAYS)
        assertEquals(KnowledgeStage.Mastered, deriveStage(state))
    }

    @Test
    fun `scheduling is a pure function of inputs`() {
        val a = scheduler.schedule(initial(), ReviewGrade.Hard, now)
        val b = scheduler.schedule(initial(), ReviewGrade.Hard, now)
        assertEquals(a, b)
    }
}
