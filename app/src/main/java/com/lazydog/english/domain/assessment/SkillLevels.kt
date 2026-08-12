package com.lazydog.english.domain.assessment

/**
 * 分技能连续能力值（CEFR 设计文档 §2「分技能画像」、§9「用户能力画像」）。
 *
 * 存在的理由：偏科的人用一个总等级会被两头坑——词汇 B1 会让 AI 按 B1 讲语法，
 * 而语法其实还在 A2。生成内容时各模块各取各的等级，不再共用总分。
 * null 表示这一项样本不足，调用方回退到总等级。
 */
data class SkillLevels(
    val vocab: Double? = null,
    /** 语法与变形，含纠错短答——两者考的都是形式控制。 */
    val grammar: Double? = null,
    val reading: Double? = null,
    val pragmatics: Double? = null,
    /** 主动产出（开放表达评分换算，粗粒度）。 */
    val expression: Double? = null,
) {
    val isEmpty: Boolean
        get() = listOf(vocab, grammar, reading, pragmatics, expression).all { it == null }
}

/** "词汇 B1+ · 语法 A2 · 阅读 B1 · 表达 A2"；一项都没测出来时返回 null。 */
fun SkillLevels.summaryText(): String? {
    val parts = listOfNotNull(
        vocab?.let { "词汇 ${labelForScore(it)}" },
        grammar?.let { "语法 ${labelForScore(it)}" },
        reading?.let { "阅读 ${labelForScore(it)}" },
        expression?.let { "表达 ${labelForScore(it)}" },
    )
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

object SkillEstimator {

    /**
     * 收缩到总等级的先验权重。分技能样本很少（一场测试每项两三题），
     * 直接用原始估计会抖得厉害，按 n / (n + PRIOR_WEIGHT) 往总等级收一收。
     */
    private const val PRIOR_WEIGHT = 2.0

    /** 在难度 d 的题上答对，说明能力大致不低于 d + 这个余量；答错则反之。 */
    private const val CREDIT_MARGIN = 0.5

    /** 深度阅读只给正确率，没有难度，只用来对阅读估计做一次小幅修正。 */
    private const val DEEP_READING_NUDGE = 0.3

    fun estimate(
        state: AssessmentState,
        deepReading: DeepReadingOutcome?,
        expression: ExpressionAssessment?,
    ): SkillLevels {
        val overall = state.score
        val readingBase = skillScore(state.answered, overall, AssessmentSkill.Reading)
        return SkillLevels(
            vocab = skillScore(state.answered, overall, AssessmentSkill.Vocab),
            grammar = skillScore(
                state.answered,
                overall,
                AssessmentSkill.Grammar,
                AssessmentSkill.Correction,
            ),
            reading = readingBase?.let { nudgeByDeepReading(it, deepReading) }
                ?: deepReading?.let { nudgeByDeepReading(overall, it) },
            pragmatics = skillScore(state.answered, overall, AssessmentSkill.Pragmatics),
            expression = expression?.let { expressionScore(it.display.total) },
        )
    }

    /**
     * 单项技能能力值：每道题给出"能力大约在 itemLevelScore ± 余量"的一次观察，
     * 取平均后往总等级收缩。部分正确不加不减。样本为空返回 null。
     */
    fun skillScore(answered: List<AnsweredItem>, overall: Double, vararg skills: String): Double? {
        val samples = answered.filter { it.skill in skills }
        if (samples.isEmpty()) return null
        val raw = samples.sumOf { item ->
            val margin = when (item.outcome) {
                AnswerOutcome.Correct -> CREDIT_MARGIN
                AnswerOutcome.Partial -> 0.0
                AnswerOutcome.Wrong -> -CREDIT_MARGIN
            }
            item.itemLevelScore + margin
        } / samples.size
        val n = samples.size
        val shrunk = (n * raw + PRIOR_WEIGHT * overall) / (n + PRIOR_WEIGHT)
        return shrunk.coerceIn(0.0, 5.0)
    }

    /** 开放表达 5 维共 20 分，粗略线性映射到 0～5 的能力值。只是估计，不是精确测量。 */
    fun expressionScore(total: Int): Double = (total.coerceIn(0, 20) * 0.25)

    private fun nudgeByDeepReading(base: Double, deepReading: DeepReadingOutcome?): Double {
        val delta = when {
            deepReading == null -> 0.0
            deepReading.pct >= 75 -> DEEP_READING_NUDGE
            deepReading.pct < 45 -> -DEEP_READING_NUDGE
            else -> 0.0
        }
        return (base + delta).coerceIn(0.0, 5.0)
    }
}
