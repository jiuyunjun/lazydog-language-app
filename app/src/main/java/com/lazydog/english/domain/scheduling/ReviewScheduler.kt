package com.lazydog.english.domain.scheduling

import com.lazydog.english.core.model.KnowledgeStage
import com.lazydog.english.core.model.ReviewGrade
import java.time.Instant

/**
 * 一个知识项的记忆状态。字段对齐 ARCHITECTURE.md 的 KnowledgeItem 调度字段，
 * 并为以后迁移 FSRS 预留：stability 以“天”为单位，difficulty 取 1..10。
 */
data class MemoryState(
    /** 记忆稳定度（天）。0 表示还没有成功复习过。 */
    val stability: Double,
    /** 主观难度 1..10，越大越难。 */
    val difficulty: Double,
    val reviewCount: Int,
    val lapseCount: Int,
    val lastReviewedAt: Instant?,
    val nextReviewAt: Instant?,
) {
    companion object {
        /** 新建知识项的初始状态：学过（exposed）但从未复习，次日首次复习。 */
        fun initial(createdAt: Instant): MemoryState = MemoryState(
            stability = 0.0,
            difficulty = 5.0,
            reviewCount = 0,
            lapseCount = 0,
            lastReviewedAt = null,
            nextReviewAt = createdAt.plusSeconds(ONE_DAY_SECONDS),
        )

        const val ONE_DAY_SECONDS: Long = 24 * 60 * 60
    }
}

/** 可替换的复习调度接口（ARCHITECTURE.md §6）。实现必须是纯函数，方便测试与重算。 */
interface ReviewScheduler {
    fun schedule(previous: MemoryState, rating: ReviewGrade, at: Instant): MemoryState
}

/**
 * 首版简化间隔算法：按四档反馈缩放稳定度。
 * 不追求记忆模型的精确性，只保证：忘了会缩短并尽快重来，记得会拉长。
 */
class SimpleIntervalScheduler : ReviewScheduler {

    override fun schedule(previous: MemoryState, rating: ReviewGrade, at: Instant): MemoryState {
        val stability = when (rating) {
            ReviewGrade.Forgot -> (previous.stability * FORGOT_FACTOR).coerceAtLeast(MIN_STABILITY_DAYS)
            ReviewGrade.Hard -> firstOrScaled(previous.stability, first = 0.5, factor = HARD_FACTOR)
            ReviewGrade.Good -> firstOrScaled(previous.stability, first = 1.0, factor = GOOD_FACTOR)
            ReviewGrade.Easy -> firstOrScaled(previous.stability, first = 3.0, factor = EASY_FACTOR)
        }.coerceAtMost(MAX_STABILITY_DAYS)

        val difficulty = (previous.difficulty + when (rating) {
            ReviewGrade.Forgot -> 1.0
            ReviewGrade.Hard -> 0.5
            ReviewGrade.Good -> -0.1
            ReviewGrade.Easy -> -0.5
        }).coerceIn(1.0, 10.0)

        val next = if (rating == ReviewGrade.Forgot) {
            // 忘了：先进入 10 分钟的重学步骤，而不是隔天再见。
            at.plusSeconds(RELEARN_STEP_SECONDS)
        } else {
            at.plusSeconds((stability * MemoryState.ONE_DAY_SECONDS).toLong())
        }

        return MemoryState(
            stability = stability,
            difficulty = difficulty,
            reviewCount = previous.reviewCount + 1,
            lapseCount = previous.lapseCount + if (rating == ReviewGrade.Forgot) 1 else 0,
            lastReviewedAt = at,
            nextReviewAt = next,
        )
    }

    private fun firstOrScaled(stability: Double, first: Double, factor: Double): Double =
        if (stability <= 0.0) first else stability * factor

    companion object {
        const val MIN_STABILITY_DAYS = 0.007 // ≈10 分钟
        const val MAX_STABILITY_DAYS = 365.0
        const val RELEARN_STEP_SECONDS = 10L * 60
        const val FORGOT_FACTOR = 0.4
        const val HARD_FACTOR = 1.2
        const val GOOD_FACTOR = 2.5
        const val EASY_FACTOR = 3.5
    }
}

/**
 * 由记忆状态推导掌握阶段。阶段是状态的展示口径，不单独维护，避免两处失真。
 */
fun deriveStage(state: MemoryState): KnowledgeStage = when {
    state.reviewCount == 0 -> KnowledgeStage.Exposed
    state.stability < 7.0 -> KnowledgeStage.Learning
    state.stability < 21.0 -> KnowledgeStage.Familiar
    else -> KnowledgeStage.Mastered
}
