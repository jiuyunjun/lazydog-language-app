package com.lazydog.english.domain.assessment

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 把客观题梯度 + 深度阅读 + 开放表达合成最终报告，对齐《CEFR 英语能力评测与个性化学习系统设计.md》§5：
 * 不只报总分，而是给 CEFR 区间 + 加权分项画像 + 理解/输出差距提示。
 * 没有测听力口语时，标题必须是"阅读与书面表达摸底"，不能自称"综合英语能力"。
 */
data class AssessmentOutcome(
    val levelLabel: String,
    val plausibleRange: String,
    val confidencePercent: Int,
    val correctCount: Int,
    val totalCount: Int,
    val vocabRangeText: String,
    /** 加权分项画像：词汇语法 25% / 阅读理解 30% / 开放表达 35% / 语用沟通 10%。 */
    val profile: List<SkillProfileRow>,
    /** 分技能连续能力值，供生成内容时各取各的等级。 */
    val skills: SkillLevels,
    val watchNoteZh: String,
    /** 理解和主动输出差距明显时才非空。 */
    val gapNoteZh: String?,
    val expressionNeedsReview: Boolean,
) {
    val confidenceLabel: String
        get() = when {
            confidencePercent >= 75 -> "较有把握"
            confidencePercent >= 50 -> "大致靠谱"
            else -> "只是初步估计"
        }
}

object AssessmentReport {

    private const val VOCAB_GRAMMAR_WEIGHT = 0.25
    private const val READING_WEIGHT = 0.30
    private const val EXPRESSION_WEIGHT = 0.35
    private const val PRAGMATICS_WEIGHT = 0.10

    fun build(
        state: AssessmentState,
        deepReading: DeepReadingOutcome?,
        expression: ExpressionAssessment?,
    ): AssessmentOutcome {
        // 纠错短答（§3.4 第 5 类覆盖技能）折进词汇语法这一项——权重表（§5.2）本身没有单列"纠错"。
        val vocabGrammarPct = combinedAccuracy(
            state.answered,
            AssessmentSkill.Vocab,
            AssessmentSkill.Grammar,
            AssessmentSkill.Correction,
        )
        val readingPct = readingComponentPct(state, deepReading)
        val expressionPct = expression?.let { (it.display.total.toDouble() / 20 * 100).roundToInt() }
        val pragmaticsPct = skillAccuracyPct(state.answered, AssessmentSkill.Pragmatics)

        val profile = listOf(
            weightedRow("词汇与语法控制", vocabGrammarPct, VOCAB_GRAMMAR_WEIGHT),
            weightedRow("阅读理解", readingPct, READING_WEIGHT),
            weightedRow("开放表达", expressionPct, EXPRESSION_WEIGHT),
            weightedRow("语用与沟通", pragmaticsPct, PRAGMATICS_WEIGHT),
        )

        val thin = profile.filter { it.pct == 0 }.map { it.name }
        val watchNote = if (thin.isEmpty()) {
            "各项都测到了几次，接下来两周的表现还会继续修正它。"
        } else {
            "${thin.joinToString("、")}样本还太少，前几天会多留意，别太当真。"
        }

        val gapNote = buildGapNote(readingPct, expressionPct)

        return AssessmentOutcome(
            levelLabel = labelForScore(state.score),
            plausibleRange = AssessmentEngine.plausibleRange(state),
            confidencePercent = AssessmentEngine.confidence(state),
            correctCount = state.answered.count { it.correct },
            totalCount = state.answered.size,
            vocabRangeText = AssessmentEngine.vocabRangeText(state.score),
            profile = profile,
            skills = SkillEstimator.estimate(state, deepReading, expression),
            watchNoteZh = watchNote,
            gapNoteZh = gapNote,
            expressionNeedsReview = expression?.needsReview ?: false,
        )
    }

    private fun weightedRow(name: String, pct: Int?, weight: Double): SkillProfileRow {
        if (pct == null) return SkillProfileRow(name, "样本不足", 0)
        val label = when {
            pct >= 75 -> "较强"
            pct >= 45 -> "中等"
            else -> "偏弱"
        }
        return SkillProfileRow("$name（权重 ${(weight * 100).roundToInt()}%）", label, pct)
    }

    /** 按平均得分率算 pct，不是简单对错计数——部分正确（纠错短答）要按 0.5 折算。 */
    private fun combinedAccuracy(answered: List<AnsweredItem>, vararg skills: String): Int? {
        val samples = answered.filter { it.skill in skills }
        if (samples.isEmpty()) return null
        val avgCredit = samples.sumOf { it.creditFraction() } / samples.size
        return (avgCredit * 100).roundToInt()
    }

    private fun AnsweredItem.creditFraction(): Double = when (outcome) {
        AnswerOutcome.Correct -> 1.0
        AnswerOutcome.Partial -> 0.5
        AnswerOutcome.Wrong -> 0.0
    }

    private fun skillAccuracyPct(answered: List<AnsweredItem>, skill: String): Int? =
        combinedAccuracy(answered, skill)

    private fun readingComponentPct(state: AssessmentState, deepReading: DeepReadingOutcome?): Int? {
        val ladderReadingPct = skillAccuracyPct(state.answered, AssessmentSkill.Reading)
        return when {
            deepReading != null && ladderReadingPct != null ->
                (deepReading.pct * 0.7 + ladderReadingPct * 0.3).roundToInt()
            deepReading != null -> deepReading.pct
            else -> ladderReadingPct
        }
    }

    /**
     * "理解能力约 B2，主动输出约 A2～B1，存在明显的输入输出差距"这类提示，
     * 只在两边都有真实样本、且差距足够大时才给，避免拿"样本不足"硬凑结论。
     */
    private fun buildGapNote(readingPct: Int?, expressionPct: Int?): String? {
        if (readingPct == null || expressionPct == null) return null
        val gap = readingPct - expressionPct
        return when {
            gap >= 25 -> "读得懂的明显比写得出的多：理解层面看着还行，主动表达明显跟不上，" +
                "接下来可以多练「解释原因—说明影响—提出方案」这类连续表达。"
            gap <= -25 -> "写起来比读理解题答得还好：可能是这次阅读题目偏难，或者你更习惯主动表达，" +
                "不用太纠结这个反差，继续学习会自然拉平。"
            else -> null
        }
    }
}
