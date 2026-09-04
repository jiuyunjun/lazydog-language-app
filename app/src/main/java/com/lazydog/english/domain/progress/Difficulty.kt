package com.lazydog.english.domain.progress

/**
 * 最近的提取成功率（`持续学习DESIGN.md` §11、§24 的 `recentAccuracy`）。
 *
 * 只看最近若干次，不看历史总和：历史总和会被几百次旧记录压住，
 * 用户这两天明显吃力也反映不出来，那就失去了调难度的意义。
 */
data class RecentAccuracy(val attempts: Int, val correct: Int) {

    /** 百分比；样本不够时是 null，而不是拿两三次作答当结论。 */
    val percent: Int? get() = if (attempts < MIN_ATTEMPTS) null else correct * 100 / attempts

    companion object {
        val Unknown = RecentAccuracy(attempts = 0, correct = 0)

        /** 少于这个次数不下结论：三次里错一次算 67%，据此改难度只是在追噪声。 */
        const val MIN_ATTEMPTS = 12

        /** 只看最近这么多次提取。 */
        const val WINDOW = 40
    }
}

/**
 * 这一轮该出难一点还是简单一点。
 *
 * 目标是把成功率长期维持在 75%~85%（§11）：全对说明题太浅，什么都没在练；
 * 一直错说明每一步都在硬撑，只会把人劝退。
 */
enum class DifficultyBias {
    /** 最近太吃力：先给一点脚手架。 */
    Easier,

    /** 在目标区间里，照常出题。 */
    Steady,

    /** 最近太顺：同一个词换个更难的问法。 */
    Harder,
}

/** 高于这个正确率就是题太浅了（§11）。 */
const val TOO_EASY_PERCENT = 90

/** 低于这个正确率就是太吃力了（§11）。 */
const val TOO_HARD_PERCENT = 65

fun difficultyBias(accuracy: RecentAccuracy): DifficultyBias {
    val percent = accuracy.percent ?: return DifficultyBias.Steady
    return when {
        percent > TOO_EASY_PERCENT -> DifficultyBias.Harder
        percent < TOO_HARD_PERCENT -> DifficultyBias.Easier
        else -> DifficultyBias.Steady
    }
}

/**
 * 从学习事件里数最近 [RecentAccuracy.WINDOW] 次提取。[events] 按时间升序。
 *
 * 只数复习：新建和"在语境里遇见"都不是提取，混进来会把正确率稀释成一个没有含义的数。
 */
fun recentAccuracy(events: List<ProgressEvent>): RecentAccuracy {
    val retrievals = events.asReversed()
        .asSequence()
        .filter { it.activity == ProgressActivity.Review && it.remembered != null }
        .take(RecentAccuracy.WINDOW)
        .toList()
    if (retrievals.isEmpty()) return RecentAccuracy.Unknown
    return RecentAccuracy(
        attempts = retrievals.size,
        correct = retrievals.count { it.remembered == true },
    )
}
