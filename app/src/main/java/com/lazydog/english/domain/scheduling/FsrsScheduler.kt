package com.lazydog.english.domain.scheduling

import com.lazydog.english.core.model.ReviewGrade
import java.time.Duration
import java.time.Instant
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow

/**
 * FSRS（Free Spaced Repetition Scheduler）调度器，替换原来的固定倍数算法
 * （`持续学习DESIGN.md` §10）。
 *
 * 和原来那版的根本区别是**它看"你现在还记得多少"**：同样是答对，一个刚复习完两天的词
 * 和一个搁了三个月才想起来的词，后者说明记忆牢固得多，间隔该拉得更开。原来的算法一律
 * 乘 2.5，把这两种情况当成一回事。
 *
 * 三个量（DSR 模型）：
 * - **S（稳定度）**：还能记住多久，单位天。间隔就是从它推出来的。
 * - **D（难度）**：这个词对这个人有多难，1~10。答错升、答对降，并且向初始值回归。
 * - **R（可提取性）**：现在还记得的概率，由 S 和距上次复习的天数推。
 *
 * 参数用官方默认值（20k 用户数据拟合出来的中位数）。**本仓库不做参数优化**：
 * 那需要成百上千条复习记录才有意义，个人自用 App 攒到那个量之前，
 * 用默认值就已经比固定倍数好得多。
 *
 * 存量数据不迁移：老词的 `stability` 本来就以天为单位、`difficulty` 本来就是 1~10，
 * FSRS 直接读得懂，下一次复习起自然按新算法走（`DECISIONS.md` D-047）。
 */
class FsrsScheduler(
    /** 期望保留率：复习时希望还记得的概率。0.9 是官方默认，也是记忆效率较好的一档。 */
    private val desiredRetention: Double = DEFAULT_DESIRED_RETENTION,
    private val w: DoubleArray = DEFAULT_PARAMETERS,
) : ReviewScheduler {

    override fun schedule(previous: MemoryState, rating: ReviewGrade, at: Instant): MemoryState {
        val grade = rating.fsrsGrade()
        val firstTime = previous.stability <= 0.0 || previous.lastReviewedAt == null

        val stability: Double
        val difficulty: Double
        if (firstTime) {
            // 第一次复习：初始稳定度和难度直接由这次的评分给出。
            stability = w[grade - 1].coerceIn(MIN_STABILITY_DAYS, MAX_STABILITY_DAYS)
            difficulty = initialDifficulty(grade)
        } else {
            val elapsedDays = max(
                0.0,
                Duration.between(previous.lastReviewedAt, at).toMillis() / MILLIS_PER_DAY,
            )
            val retrievability = retrievability(elapsedDays, previous.stability)
            difficulty = nextDifficulty(previous.difficulty, grade)
            stability = if (grade == GRADE_AGAIN) {
                forgetStability(previous.stability, previous.difficulty, retrievability)
            } else {
                recallStability(previous.stability, previous.difficulty, retrievability, grade)
            }.coerceIn(MIN_STABILITY_DAYS, MAX_STABILITY_DAYS)
        }

        val next = if (rating == ReviewGrade.Forgot) {
            // 忘了先进 10 分钟的重学步骤，而不是隔天再见——这条是原来就有的体验，保留。
            // 稳定度已经按 FSRS 掉下来了，重学答对之后的间隔自然是短的。
            at.plusSeconds(RELEARN_STEP_SECONDS)
        } else {
            at.plusSeconds((intervalDays(stability) * SECONDS_PER_DAY).toLong())
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

    /**
     * 遗忘曲线：距上次复习 [elapsedDays] 天之后还记得的概率。
     *
     * 幂函数而不是指数——真实的遗忘尾巴比指数衰减长，这是 FSRS 相对早期算法的一处关键修正。
     */
    fun retrievability(elapsedDays: Double, stability: Double): Double {
        if (stability <= 0.0) return 0.0
        return (1.0 + FACTOR * elapsedDays / stability).pow(DECAY)
    }

    /** 稳定度 [stability] 对应的复习间隔：让 R 掉到 [desiredRetention] 需要多少天。 */
    fun intervalDays(stability: Double): Double {
        val raw = stability / FACTOR * (desiredRetention.pow(1.0 / DECAY) - 1.0)
        return raw.coerceIn(MIN_INTERVAL_DAYS, MAX_INTERVAL_DAYS)
    }

    private fun initialDifficulty(grade: Int): Double =
        (w[4] - exp(w[5] * (grade - 1)) + 1.0).coerceIn(MIN_DIFFICULTY, MAX_DIFFICULTY)

    /**
     * 难度更新：答得越差升得越多，同时向"一路 Easy"的初始难度回归。
     *
     * 回归项（[w] 7）是防止难度单调爬到 10 就再也下不来——一个词偶尔忘几次不该被永久判死刑。
     */
    private fun nextDifficulty(difficulty: Double, grade: Int): Double {
        val delta = -w[6] * (grade - 3)
        // 线性阻尼：难度越接近 10，同样的 delta 推得越少。
        val damped = difficulty + delta * ((MAX_DIFFICULTY - difficulty) / 9.0)
        val reverted = w[7] * initialDifficulty(GRADE_EASY) + (1.0 - w[7]) * damped
        return reverted.coerceIn(MIN_DIFFICULTY, MAX_DIFFICULTY)
    }

    /**
     * 答对之后的新稳定度。
     *
     * 三个因子的方向都值得记住：**难度越高涨得越少**、**已经很稳的词涨得越少**（边际递减）、
     * **越是快忘了才想起来涨得越多**——最后这条就是"提取难度效应"，卡在遗忘边缘的那一次
     * 成功回忆最有价值。
     */
    private fun recallStability(
        stability: Double,
        difficulty: Double,
        retrievability: Double,
        grade: Int,
    ): Double {
        val hardPenalty = if (grade == GRADE_HARD) w[15] else 1.0
        val easyBonus = if (grade == GRADE_EASY) w[16] else 1.0
        val increase = exp(w[8]) *
            (11.0 - difficulty) *
            stability.pow(-w[9]) *
            (exp(w[10] * (1.0 - retrievability)) - 1.0) *
            hardPenalty *
            easyBonus
        return stability * (1.0 + increase)
    }

    /**
     * 忘了之后的新稳定度。**取和原来稳定度的较小值**：忘了一次不该让一个词变得更稳，
     * 但也不必清零——它毕竟学过一轮，比全新的词底子好。
     */
    private fun forgetStability(
        stability: Double,
        difficulty: Double,
        retrievability: Double,
    ): Double {
        val lapsed = w[11] *
            difficulty.pow(-w[12]) *
            ((stability + 1.0).pow(w[13]) - 1.0) *
            exp(w[14] * (1.0 - retrievability))
        return minOf(lapsed, stability)
    }

    private fun ReviewGrade.fsrsGrade(): Int = when (this) {
        ReviewGrade.Forgot -> GRADE_AGAIN
        ReviewGrade.Hard -> GRADE_HARD
        ReviewGrade.Good -> GRADE_GOOD
        ReviewGrade.Easy -> GRADE_EASY
    }

    companion object {
        /**
         * FSRS 官方默认参数（20k 份用户数据拟合结果的中位数）。
         *
         * 分工：0~3 初始稳定度（按四档评分）、4~5 初始难度、6~7 难度更新与回归、
         * 8~10 答对后的稳定度增长、11~14 忘记后的稳定度、15~16 Hard 惩罚与 Easy 加成。
         * （17~18 是同日重复复习的短期项，这里没用到，见 [FsrsScheduler] 的说明。）
         */
        val DEFAULT_PARAMETERS = doubleArrayOf(
            0.40255, 1.18385, 3.173, 15.69105, // S0：Again / Hard / Good / Easy
            7.1949, 0.5345, // D0
            1.4604, 0.0046, // 难度更新、均值回归
            1.54575, 0.1192, 1.01925, // 答对后的稳定度
            1.9395, 0.11, 0.29605, 2.2698, // 忘记后的稳定度
            0.2315, 2.9898, // Hard 惩罚、Easy 加成
            0.51655, 0.6621, // 短期项（未使用）
        )

        const val DEFAULT_DESIRED_RETENTION = 0.9

        /** 遗忘曲线的两个常数，和 [DEFAULT_PARAMETERS] 是配套的，不要单独改。 */
        const val DECAY = -0.5
        const val FACTOR = 19.0 / 81.0

        const val MIN_DIFFICULTY = 1.0
        const val MAX_DIFFICULTY = 10.0

        /** 稳定度下限约 10 分钟，和重学步骤对齐。 */
        const val MIN_STABILITY_DAYS = 0.007
        const val MAX_STABILITY_DAYS = 365.0

        /** 间隔至少一天：同一个词一天内反复考不是复习，是刷题。 */
        const val MIN_INTERVAL_DAYS = 1.0
        const val MAX_INTERVAL_DAYS = 365.0

        const val RELEARN_STEP_SECONDS = 10L * 60

        const val GRADE_AGAIN = 1
        const val GRADE_HARD = 2
        const val GRADE_GOOD = 3
        const val GRADE_EASY = 4

        private const val MILLIS_PER_DAY = 24.0 * 60 * 60 * 1000
        private const val SECONDS_PER_DAY = 24.0 * 60 * 60
    }
}
