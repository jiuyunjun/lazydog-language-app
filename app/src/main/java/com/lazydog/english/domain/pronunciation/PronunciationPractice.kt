package com.lazydog.english.domain.pronunciation

/**
 * 练什么、有多难、给到第几级提示（`发音与音标学习DESIGN.md` §17、§18、§20，D-077）。
 *
 * 全是纯函数，跑在 JVM 单测里。这些判断一旦漏进页面，同一条规则就会在
 * 首页和训练页各写一遍，然后慢慢说不一样的话。
 */

/**
 * 提示阶梯（设计文档 §18）。
 *
 * 提示不能只有「看答案」一档：只有一档的时候，用户要么硬猜要么直接翻牌，
 * 中间那些「再听一次就想起来了」「看到音标就明白了」的信息全都收集不到。
 * 用到第几级会被记进 [PerceptionAttempt.hintLevel]，是这一题难度的直接证据。
 */
enum class PronunciationHint(val level: Int, val labelZh: String, val descriptionZh: String) {
    None(0, "只有音频", "没有音标、没有口型、没有中文。"),
    Replay(1, "再放一次", "同一条音频、同一个声线，再放一遍。"),
    ContrastPlay(2, "先听两个音", "把两个目标音单独、连着放一遍，让差异先立起来。"),
    ShowIpa(3, "给音标", "显示两个候选的音标，差的那个音位标出来。"),
    ShowArticulation(4, "给口型", "显示发音位置：舌头在哪、嘴唇什么状态、气怎么走。"),
    RevealAnswer(5, "给答案", "给答案，并且说清这两个音差在哪。这一题不计入听辨分。"),
    ;

    fun next(): PronunciationHint = entries.getOrElse(ordinal + 1) { RevealAnswer }

    val isFinal: Boolean get() = this == RevealAnswer

    companion object {
        fun ofLevel(level: Int): PronunciationHint =
            entries.firstOrNull { it.level == level } ?: None
    }
}

/**
 * 听辨题的难度档。往难了调 = 换词、换题型、进句子；往简单调就是反过来。
 *
 * 档位本身不显示给用户。降级在界面上必须是静默的——题变简单就行了，
 * 不出现任何「退步了」的文案（`UI_BRIEF.md` §2.1 的硬边界）。
 */
enum class PerceptionDifficulty(val exercise: PerceptionExercise, val allowsReplay: Boolean) {
    /** 固定词对，二选一，随便放。 */
    FixedPair(PerceptionExercise.MinimalPairTwo, allowsReplay = true),

    /** 换词，二选一。防止用户只记住「ship 那道题选左边」。 */
    RotatingPair(PerceptionExercise.MinimalPairTwo, allowsReplay = true),

    /** 三选一。 */
    ThreeWay(PerceptionExercise.MinimalPairThree, allowsReplay = true),

    /** 听声音选音标。 */
    SymbolRecall(PerceptionExercise.SoundToIpa, allowsReplay = false),
    ;

    fun harder(): PerceptionDifficulty = entries.getOrElse(ordinal + 1) { SymbolRecall }

    fun easier(): PerceptionDifficulty = entries.getOrElse(ordinal - 1) { FixedPair }
}

/**
 * 动态难度（设计文档 §17）。
 *
 * 目标不是让用户一直全对，而是把成功率维持在一个还在学的区间。全对说明题太简单，
 * 已经在浪费时间；一直错说明还没建立类别，再练也只是在猜。
 */
object DifficultyPolicy {

    /** 低于这条往简单调。 */
    const val TOO_HARD = 0.65f

    /** 高于这条往难了调。 */
    const val TOO_EASY = 0.90f

    /** 至少要这么多次作答才动档。两三题的正确率是噪声。 */
    const val MIN_SAMPLES = 4

    fun adjust(
        current: PerceptionDifficulty,
        recentAccuracy: Float?,
        sampleCount: Int,
    ): PerceptionDifficulty = when {
        recentAccuracy == null || sampleCount < MIN_SAMPLES -> current
        recentAccuracy > TOO_EASY -> current.harder()
        recentAccuracy < TOO_HARD -> current.easier()
        else -> current
    }

    /**
     * 这个档位下，一道题最多能给到第几级提示。
     *
     * 简单档不设上限（要几级给几级，卡住了没意义）；难档收紧，否则「逐级要提示」
     * 会退化成点几下看答案。
     */
    fun maxHint(difficulty: PerceptionDifficulty): PronunciationHint =
        if (difficulty.allowsReplay) PronunciationHint.RevealAnswer else PronunciationHint.ShowIpa
}

/** 一次练习里的一步。 */
sealed interface PracticeStep {

    val targetId: String

    data class Perceive(
        override val targetId: String,
        val contrastId: String,
        val difficulty: PerceptionDifficulty,
    ) : PracticeStep

    data class Produce(
        override val targetId: String,
        val phonemeId: String,
        val level: ProductionLevel,
    ) : PracticeStep

    /** 先看一眼音位卡。新音位的第一步永远是这个，不是直接考。 */
    data class Introduce(
        override val targetId: String,
        val phonemeId: String,
    ) : PracticeStep
}

/** 一次练习的规模。 */
enum class SessionLength(val labelZh: String, val stepCount: Int) {
    Quick("2 分钟", 6),
    Standard("5 分钟", 14),
    Deep("10 分钟", 26),
}

/**
 * 模块内部的推荐队列（设计文档 §20）。
 *
 * 本模块不接全局 Daily Learning Queue，但仍然需要回答「今天最值得练什么」——
 * 让用户自己在音标表里挑，等于把最难的决定丢回给他。
 */
object PracticeQueue {

    /** 队列配比：最弱对比 / 发音不稳 / 保持测试 / 新音位。 */
    const val WEAK_CONTRAST_SHARE = 0.40
    const val UNSTABLE_PRODUCTION_SHARE = 0.25
    const val RETENTION_SHARE = 0.20
    const val NEW_PHONEME_SHARE = 0.15

    /** 多久没练就该回来抽查一次（毫秒）。稳定的音也不该就此消失。 */
    const val RETENTION_INTERVAL_MILLIS = 7L * 24 * 60 * 60 * 1000

    /**
     * 排序权重：弱到什么程度 × 证据够不够 × 这个音有多常用 × 多久没练了。
     *
     * 四项都占权重，所以**最弱的那个不一定排第一**——只练过三次的 62% 不该插队，
     * 它需要的是多测几次，不是马上开练。
     */
    fun priority(
        progress: PronunciationProgress,
        baseValue: Float,
        now: Long,
    ): Double {
        val perception = progress.perception
        val production = progress.production
        val weakness = when {
            perception.hasEvidence && production.hasEvidence ->
                1f - minOf(perception.score, production.score)
            perception.hasEvidence -> 1f - perception.score
            production.hasEvidence -> 1f - production.score
            else -> 0.5f
        }
        val confidence = maxOf(perception.confidence, production.confidence)
        val idleDays = progress.lastPracticedAt
            ?.let { (now - it).coerceAtLeast(0L) / (24.0 * 60 * 60 * 1000) }
            ?: 0.0
        val recency = 1.0 + minOf(idleDays / 14.0, 1.0)
        return weakness.toDouble() * confidence * baseValue * recency
    }

    /**
     * 需要优先练的目标，按 [priority] 排序。
     *
     * **证据不足的先不进来**：它们不排优先级，只安排多出现几次（那部分由
     * [needsMoreEvidence] 单独给）。
     */
    fun weakest(
        progress: List<PronunciationProgress>,
        baseValueOf: (String) -> Float = { 1f },
        now: Long,
    ): List<PronunciationProgress> = progress
        .filter { it.perception.confident || it.production.confident }
        .filter { it.stage != PronunciationStage.Stable }
        .sortedByDescending { priority(it, baseValueOf(it.targetId), now) }

    /** 样本还不够的目标。它们该多测，不该被标成弱项。 */
    fun needsMoreEvidence(progress: List<PronunciationProgress>): List<PronunciationProgress> =
        progress.filter { it.perception.hasEvidence && !it.perception.confident }
            .sortedBy { it.perception.sampleCount }

    /** 该回来抽查的稳定项。 */
    fun dueForRetention(progress: List<PronunciationProgress>, now: Long): List<PronunciationProgress> =
        progress.filter { it.stage == PronunciationStage.Stable }
            .filter { now - (it.lastPracticedAt ?: 0L) >= RETENTION_INTERVAL_MILLIS }
            .sortedBy { it.lastPracticedAt ?: 0L }

    /**
     * 冷启动：还没有任何记录时推什么。
     *
     * 摸底没做完的用户也得有东西可练，所以按目录自带的 [PhonemeContrast.difficulty]
     * 推最常见的几组对比，而不是显示一个空首页。
     */
    fun coldStart(catalog: PhonemeCatalog, count: Int = 3): List<PhonemeContrast> =
        catalog.contrasts.sortedByDescending { it.difficulty }.take(count)
}
