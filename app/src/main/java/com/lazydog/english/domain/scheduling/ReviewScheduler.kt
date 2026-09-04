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
 * 由记忆状态推导掌握阶段。阶段是状态的展示口径，不单独维护，避免两处失真。
 */
fun deriveStage(state: MemoryState): KnowledgeStage = when {
    state.reviewCount == 0 -> KnowledgeStage.Exposed
    state.stability < 7.0 -> KnowledgeStage.Learning
    state.stability < 21.0 -> KnowledgeStage.Familiar
    else -> KnowledgeStage.Mastered
}
