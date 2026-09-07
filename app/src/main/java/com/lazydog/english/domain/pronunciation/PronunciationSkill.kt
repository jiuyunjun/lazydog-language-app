package com.lazydog.english.domain.pronunciation

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 听辨能力与发音能力的评估（`发音与音标学习DESIGN.md` §14～§15、§26，D-077）。
 *
 * 全模块最重要的三条口径都写在这个文件里，不写在页面上：
 *
 * 1. **两条线不合并。** 这里不提供把听辨分和发音分平均成一个总分的函数。能听出来不等于
 *    能发出来，合成之后这个差距就没了，而它恰恰是这个模块要解决的问题。
 * 2. **证据不够就不是结论。** 样本少于 [SkillEstimate.MIN_CONFIDENT_SAMPLES] 的估计
 *    `confident` 为 false，不进推荐队列、不排优先级、界面走灰态。
 * 3. **一次异常不改变任何状态。** 分数是最近一窗的加权平均，单次低分拉不动阶段；
 *    「这是个稳定问题」由 [isStableProblem] 判定，它要求连续多次落在低区。
 */

/**
 * 练习目标的身份。可以是一个音位，也可以是一组对比。
 *
 * 两者共用一张进度表，所以要在字符串里带上类型前缀——`/θ/` 单独练和 `/s/ ↔ /θ/`
 * 对着练是两件事，各有各的分数。
 */
object PronunciationTarget {

    private const val PHONEME_PREFIX = "ph:"
    private const val CONTRAST_PREFIX = "ct:"

    fun ofPhoneme(phonemeId: String): String = PHONEME_PREFIX + phonemeId

    fun ofContrast(contrastId: String): String = CONTRAST_PREFIX + contrastId

    fun isContrast(targetId: String): Boolean = targetId.startsWith(CONTRAST_PREFIX)

    fun isPhoneme(targetId: String): Boolean = targetId.startsWith(PHONEME_PREFIX)

    /** 去掉前缀之后的原始 id；不认识的字符串原样返回。 */
    fun rawId(targetId: String): String = targetId.substringAfter(':', targetId)
}

/** 模块内部的学习阶段（设计文档 §19）。**只在这个模块内有效，不映射到全局 Mastery。** */
enum class PronunciationStage(val labelZh: String) {
    /** 还没练过。 */
    Unseen("还没练过"),

    /** 看过音位卡，知道大概是个什么声音。 */
    Introduced("认识了"),

    /** 在最小对立里能稳定听出来。 */
    Discriminating("能听辨"),

    /** 多个词里能发出目标音，但还不稳。 */
    Producing("在练发音"),

    /** 换词、换人、进句子都保持得住。 */
    Stable("稳了"),
}

/** 听辨题的题型。 */
enum class PerceptionExercise {
    /** 二选一最小对立。 */
    MinimalPairTwo,

    /** 三选一。 */
    MinimalPairThree,

    /** 听声音选音标。只在该音已达 [PronunciationStage.Discriminating] 之后出。 */
    SoundToIpa,
}

/** 跟读的层级。逐级开放，刚认识的音不给长句（设计文档 §12.6）。 */
enum class ProductionLevel(val labelZh: String) {
    Sound("单音"),
    Word("单词"),
    Phrase("短语"),
    Sentence("句子"),
}

/** 一次录音的可用性。不可用的录音不给分、不入历史（设计文档 §26.3）。 */
enum class RecordingQuality(val messageZh: String?) {
    Ok(null),
    TooQuiet("这次声音有点小，靠近一点再来一次。"),
    NoSpeech("这次没听到人声，再来一次。"),
    TooShort("这次太短了，整个词读完再松手。"),
    Truncated("这次像是被切掉了一截，再来一次。"),
    ;

    val usable: Boolean get() = this == Ok
}

/** 一次听辨作答（设计文档 §28.4）。 */
data class PerceptionAttempt(
    val targetId: String,
    val exercise: PerceptionExercise,
    val correct: Boolean,
    val replayCount: Int = 0,
    val hintLevel: Int = 0,
    val responseTimeMillis: Long = 0L,
    /** 这一题用的是哪个词对 / 哪个声线，用来判断「换了词还认得吗」。 */
    val variantId: String = "",
    val occurredAt: Long = 0L,
)

/** 一次跟读（设计文档 §28.5）。 */
data class ProductionAttempt(
    val targetId: String,
    val level: ProductionLevel,
    val text: String,
    /** 服务返回的原始分（百分制）。录音不可用时为 null。 */
    val providerScore: Int?,
    /** 这一次里目标音自己的准确度，来自音素级证据；拿不到时退回整体分。 */
    val targetScore: Int?,
    val quality: RecordingQuality = RecordingQuality.Ok,
    val occurredAt: Long = 0L,
) {
    /** 计入历史的那个数。录音不可用的这次不算数。 */
    val usableScore: Int? get() = if (quality.usable) (targetScore ?: providerScore) else null
}

/**
 * 一条能力估计：分数、样本数、置信度。
 *
 * [score] 是 0～1。界面显示百分比时用 [percent]，不要自己乘 100 再取整——
 * 四舍五入的口径只留一份。
 */
data class SkillEstimate(
    val score: Float,
    val sampleCount: Int,
) {

    val percent: Int get() = (score.coerceIn(0f, 1f) * 100).roundToInt()

    /** 样本够了才算数。不够的时候这个分数是给系统看的，不是给用户下结论用的。 */
    val confident: Boolean get() = sampleCount >= MIN_CONFIDENT_SAMPLES

    /** 0～1 的置信度，只用来排序，不显示成百分比——那是假精确。 */
    val confidence: Float
        get() = (sampleCount.toFloat() / MIN_CONFIDENT_SAMPLES).coerceIn(0f, 1f)

    val hasEvidence: Boolean get() = sampleCount > 0

    companion object {

        /**
         * 少于这个数就不下结论。
         *
         * 8 不是算出来的阈值，是个刻意保守的数：二选一题猜也有 50%，样本个位数的时候
         * 62% 和 75% 之间没有区别。宁可让用户多练几次再看到判断，也不要给一个
         * 三次作答就敢标红的「弱项」。
         */
        const val MIN_CONFIDENT_SAMPLES = 8

        val Unknown = SkillEstimate(score = 0f, sampleCount = 0)
    }
}

/** 听辨评分。 */
object PerceptionScoring {

    /** 只看最近这些次。更早的表现说明不了「现在还分不分得清」。 */
    const val WINDOW = 20

    /** 到了这一级提示等于答案摆在脸上，这一题不计入分数（设计文档 §18 Level 5）。 */
    const val ANSWER_HINT_LEVEL = 5

    private const val REPLAY_PENALTY = 0.12f
    private const val HINT_PENALTY = 0.10f
    private const val MIN_CREDIT = 0.30f

    /**
     * 一次答对值多少。
     *
     * 重放三次才听出来和一遍就听出来不是同一件事，用了提示更不是。所以答对不是一律 1 分，
     * 而是按「你为此花了多少外力」打折——但有下限：折到 0 会让「重放两次答对」
     * 和「答错」变成一回事，那是另一种失真。
     */
    fun credit(attempt: PerceptionAttempt): Float {
        if (!attempt.correct) return 0f
        val penalty = REPLAY_PENALTY * attempt.replayCount + HINT_PENALTY * attempt.hintLevel
        return max(MIN_CREDIT, 1f - penalty)
    }

    /**
     * 从作答记录算出听辨估计。[attempts] 任意顺序都可以，内部按时间取最近一窗。
     *
     * 提示已经拉到答案那一级的作答整题剔除：它既不能证明听得出，也不该被记成听不出。
     */
    fun estimate(attempts: List<PerceptionAttempt>): SkillEstimate {
        val counted = attempts
            .filter { it.hintLevel < ANSWER_HINT_LEVEL }
            .sortedBy { it.occurredAt }
            .takeLast(WINDOW)
        if (counted.isEmpty()) return SkillEstimate.Unknown
        val total = counted.sumOf { credit(it).toDouble() }
        return SkillEstimate(
            score = (total / counted.size).toFloat().coerceIn(0f, 1f),
            sampleCount = counted.size,
        )
    }

    /** 最近连对多少次。结束页那句「连着分对 8 次」用的就是它。 */
    fun currentStreak(attempts: List<PerceptionAttempt>): Int =
        attempts.sortedBy { it.occurredAt }
            .reversed()
            .takeWhile { it.correct && it.hintLevel < ANSWER_HINT_LEVEL }
            .count()

    /** 最近一窗的正确率，只用来调难度，不对外显示。 */
    fun recentAccuracy(attempts: List<PerceptionAttempt>, window: Int = 8): Float? {
        val recent = attempts.sortedBy { it.occurredAt }.takeLast(window)
        if (recent.isEmpty()) return null
        return recent.count { it.correct }.toFloat() / recent.size
    }
}

/** 发音评分。 */
object ProductionScoring {

    const val WINDOW = 10

    /** 连续多少次落在低区才算「稳定问题」，而不是运气差（设计文档 §26.2）。 */
    const val STABLE_PROBLEM_RUN = 5

    /** 低区上界（百分制）。 */
    const val WEAK_SCORE = 70

    fun estimate(attempts: List<ProductionAttempt>): SkillEstimate {
        val scores = attempts
            .sortedBy { it.occurredAt }
            .mapNotNull { it.usableScore }
            .takeLast(WINDOW)
        if (scores.isEmpty()) return SkillEstimate.Unknown
        return SkillEstimate(
            score = (scores.sum().toFloat() / scores.size / 100f).coerceIn(0f, 1f),
            sampleCount = scores.size,
        )
    }

    /**
     * 这是不是一个稳定问题。
     *
     * `82 / 79 / 81 / 61 / 80` 里那个 61 什么都不说明——麦克风、环境、一次咬字，
     * 都能造出一个 61。`67 / 64 / 62 / 66 / 63` 才是问题（设计文档 §26.1、§26.2）。
     */
    fun isStableProblem(attempts: List<ProductionAttempt>): Boolean {
        val scores = attempts.sortedBy { it.occurredAt }.mapNotNull { it.usableScore }
        if (scores.size < STABLE_PROBLEM_RUN) return false
        return scores.takeLast(STABLE_PROBLEM_RUN).all { it < WEAK_SCORE }
    }

    /** 这个音在多少个不同的词里被读到过 [WEAK_SCORE] 以上。迁移能力的直接证据。 */
    fun provenWords(attempts: List<ProductionAttempt>): Set<String> =
        attempts.filter { it.level == ProductionLevel.Word && (it.usableScore ?: 0) >= WEAK_SCORE }
            .map { it.text.trim().lowercase() }
            .toSet()

    /** 句子层级上站住过没有。 */
    fun provenInSentence(attempts: List<ProductionAttempt>): Boolean =
        attempts.any {
            it.level == ProductionLevel.Sentence && (it.usableScore ?: 0) >= WEAK_SCORE
        }
}

/** 一个目标当前的完整状态。它由记录算出来，落库只是为了不用每次全表重算。 */
data class PronunciationProgress(
    val targetId: String,
    val perception: SkillEstimate = SkillEstimate.Unknown,
    val production: SkillEstimate = SkillEstimate.Unknown,
    val stage: PronunciationStage = PronunciationStage.Unseen,
    val lastPracticedAt: Long? = null,
) {

    /** 「听得出来但自己读不准」——这一组只该排跟读，耳朵那关已经过了。 */
    val hearsButCannotSay: Boolean
        get() = perception.confident && perception.score >= PronunciationStages.PERCEPTION_OK &&
            production.confident && production.score < PronunciationStages.PRODUCTION_OK

    /** 证据还不够，先别当结论。 */
    val needsMoreEvidence: Boolean
        get() = !perception.confident && !production.confident
}

/** 阶段推导。分散在页面里的话，同一个音在两屏上会显示成两个阶段。 */
object PronunciationStages {

    const val PERCEPTION_OK = 0.85f
    const val PRODUCTION_OK = 0.70f
    const val PRODUCTION_STABLE = 0.80f

    /** 「稳了」至少要在这么多个不同的词里发对过。一个词读顺了不算会。 */
    const val STABLE_WORD_COUNT = 3

    fun stageFor(
        seenCard: Boolean,
        perception: SkillEstimate,
        production: SkillEstimate,
        provenWordCount: Int = 0,
        provenInSentence: Boolean = false,
    ): PronunciationStage {
        if (!seenCard && !perception.hasEvidence && !production.hasEvidence) {
            return PronunciationStage.Unseen
        }
        val hears = perception.confident && perception.score >= PERCEPTION_OK
        if (!hears) return PronunciationStage.Introduced

        val says = production.confident && production.score >= PRODUCTION_OK
        if (!says) return PronunciationStage.Discriminating

        val stable = production.score >= PRODUCTION_STABLE &&
            provenWordCount >= STABLE_WORD_COUNT &&
            provenInSentence
        return if (stable) PronunciationStage.Stable else PronunciationStage.Producing
    }
}
