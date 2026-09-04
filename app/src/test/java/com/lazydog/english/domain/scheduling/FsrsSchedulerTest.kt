package com.lazydog.english.domain.scheduling

import com.lazydog.english.core.model.ReviewGrade
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FSRS 的行为约定（`持续学习DESIGN.md` §10）。
 *
 * 这里不复刻公式——那样只是把实现抄一遍——而是钉住几条"改坏了一定看得出来"的性质。
 */
class FsrsSchedulerTest {

    private val scheduler = FsrsScheduler()
    private val now: Instant = Instant.parse("2026-09-04T08:00:00Z")

    private fun fresh() = MemoryState(
        stability = 0.0,
        difficulty = 5.0,
        reviewCount = 0,
        lapseCount = 0,
        lastReviewedAt = null,
        nextReviewAt = now,
    )

    private fun reviewed(stability: Double, difficulty: Double = 5.0, daysAgo: Long = 10) = MemoryState(
        stability = stability,
        difficulty = difficulty,
        reviewCount = 3,
        lapseCount = 0,
        lastReviewedAt = now.minus(Duration.ofDays(daysAgo)),
        nextReviewAt = now,
    )

    private fun intervalDays(state: MemoryState): Double =
        Duration.between(state.lastReviewedAt, state.nextReviewAt).toMillis() / 86_400_000.0

    @Test
    fun `第一次复习的间隔按这次的评分给`() {
        val again = scheduler.schedule(fresh(), ReviewGrade.Forgot, now)
        val hard = scheduler.schedule(fresh(), ReviewGrade.Hard, now)
        val good = scheduler.schedule(fresh(), ReviewGrade.Good, now)
        val easy = scheduler.schedule(fresh(), ReviewGrade.Easy, now)

        assertTrue(again.stability < hard.stability)
        assertTrue(hard.stability < good.stability)
        assertTrue(good.stability < easy.stability)
        // Good 大约三天，Easy 大约两周——和 Anki 上 FSRS 的手感一致。
        assertEquals(3.17, good.stability, 0.1)
        assertEquals(15.69, easy.stability, 0.1)
    }

    @Test
    fun `期望保留率 0_9 时间隔约等于稳定度`() {
        val state = scheduler.schedule(reviewed(stability = 20.0), ReviewGrade.Good, now)
        assertEquals(state.stability, intervalDays(state), 0.5)
    }

    @Test
    fun `隔得越久才想起来，稳定度涨得越多`() {
        // 提取难度效应：卡在快忘了的边缘那一次成功回忆最有价值。
        // 这正是原来"一律乘 2.5"看不见的东西，所以专门钉一条。
        val justReviewed = scheduler.schedule(reviewed(stability = 10.0, daysAgo = 1), ReviewGrade.Good, now)
        val nearlyForgotten = scheduler.schedule(reviewed(stability = 10.0, daysAgo = 20), ReviewGrade.Good, now)
        assertTrue(
            "隔 20 天想起来该比隔 1 天涨得多：${justReviewed.stability} vs ${nearlyForgotten.stability}",
            nearlyForgotten.stability > justReviewed.stability * 1.5,
        )
    }

    @Test
    fun `已经很稳的词涨得慢，边际递减`() {
        val young = scheduler.schedule(reviewed(stability = 5.0, daysAgo = 5), ReviewGrade.Good, now)
        val old = scheduler.schedule(reviewed(stability = 100.0, daysAgo = 100), ReviewGrade.Good, now)
        assertTrue(young.stability / 5.0 > old.stability / 100.0)
    }

    @Test
    fun `难的词涨得比简单的词慢`() {
        val easyWord = scheduler.schedule(reviewed(stability = 10.0, difficulty = 2.0), ReviewGrade.Good, now)
        val hardWord = scheduler.schedule(reviewed(stability = 10.0, difficulty = 9.0), ReviewGrade.Good, now)
        assertTrue(hardWord.stability < easyWord.stability)
    }

    @Test
    fun `忘了：稳定度掉下来但不清零，十分钟后重来`() {
        val before = reviewed(stability = 10.0)
        val after = scheduler.schedule(before, ReviewGrade.Forgot, now)

        assertTrue(after.stability < before.stability)
        // 学过一轮的词底子比全新的好，不该被打回原形。
        assertTrue(after.stability > 0.0)
        assertEquals(1, after.lapseCount)
        assertEquals(now.plusSeconds(600), after.nextReviewAt)
    }

    @Test
    fun `四档评分的间隔单调递增`() {
        val before = reviewed(stability = 10.0)
        val hard = scheduler.schedule(before, ReviewGrade.Hard, now)
        val good = scheduler.schedule(before, ReviewGrade.Good, now)
        val easy = scheduler.schedule(before, ReviewGrade.Easy, now)
        assertTrue(hard.stability < good.stability)
        assertTrue(good.stability < easy.stability)
    }

    @Test
    fun `难度答错升答对降，并且夹在 1 到 10`() {
        var state = reviewed(stability = 10.0, difficulty = 5.0)
        val harder = scheduler.schedule(state, ReviewGrade.Forgot, now)
        val easier = scheduler.schedule(state, ReviewGrade.Easy, now)
        assertTrue(harder.difficulty > 5.0)
        assertTrue(easier.difficulty < 5.0)

        // 一路答错也不该冲出上界。
        state = reviewed(stability = 10.0, difficulty = 9.9)
        repeat(10) { state = scheduler.schedule(state, ReviewGrade.Forgot, now) }
        assertTrue(state.difficulty <= FsrsScheduler.MAX_DIFFICULTY)

        // 一路 Easy 也不该跌破下界。
        state = reviewed(stability = 10.0, difficulty = 1.1)
        repeat(10) { state = scheduler.schedule(state, ReviewGrade.Easy, now) }
        assertTrue(state.difficulty >= FsrsScheduler.MIN_DIFFICULTY)
    }

    @Test
    fun `间隔有上限，不会排到几十年后`() {
        val state = scheduler.schedule(reviewed(stability = 400.0, daysAgo = 400), ReviewGrade.Easy, now)
        assertTrue(state.stability <= FsrsScheduler.MAX_STABILITY_DAYS)
        assertTrue(intervalDays(state) <= FsrsScheduler.MAX_INTERVAL_DAYS + 0.01)
    }

    @Test
    fun `存量数据直接读得懂，不需要迁移`() {
        // 老算法留下的状态：stability 本来就是天、difficulty 本来就是 1~10。
        val legacy = MemoryState(
            stability = 7.5,
            difficulty = 6.2,
            reviewCount = 4,
            lapseCount = 1,
            lastReviewedAt = now.minus(Duration.ofDays(7)),
            nextReviewAt = now,
        )
        val next = scheduler.schedule(legacy, ReviewGrade.Good, now)
        assertTrue(next.stability > legacy.stability)
        assertEquals(5, next.reviewCount)
        assertEquals(1, next.lapseCount)
    }

    @Test
    fun `遗忘曲线：到了间隔那天刚好掉到期望保留率`() {
        val stability = 30.0
        val due = scheduler.intervalDays(stability)
        assertEquals(0.9, scheduler.retrievability(due, stability), 0.01)
        // 幂函数的尾巴长：三倍间隔之后仍然记得不少，不是指数式地掉到零。
        assertTrue(scheduler.retrievability(due * 3, stability) > 0.7)
    }
}
