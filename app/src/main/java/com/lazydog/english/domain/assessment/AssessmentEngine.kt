package com.lazydog.english.domain.assessment

import kotlin.math.roundToInt
import kotlinx.serialization.Serializable

/**
 * 能力测试的客观题梯度内核，对齐 EXT_TEST_DESIGN.md 一、二节：
 * - 连续能力值（1.0 A1 ～ 5.0 C1）代替离散等级台阶，支持 "B1+" 这样的过渡标签。
 * - 前 3 题是固定难度的定位题，答完一次性确定起点（见 §2 表格）。
 * - 之后每题按当前能力值出题，答对/答错微调分值；覆盖约束避免连续同技能；
 *   停止前必须确认过一次"当前等级 +1"的探顶题，且置信度达到约 75%。
 * 纯函数 + 可序列化状态，支持中断恢复和单测。
 */
object AssessmentSkill {
    const val Vocab = "vocab"
    const val Grammar = "grammar"
    const val Reading = "reading"
    const val Pragmatics = "pragmatics"

    /** 客观题梯度覆盖的四类技能（开放表达和深度阅读是独立模块，不参与这个覆盖约束）。 */
    val ladderSkills = listOf(Vocab, Grammar, Reading, Pragmatics)
}

/** CEFR 锚点，score 是该等级在 1.0(A1)～5.0(C1) 连续能力值上的锚定值。 */
enum class CefrLevel(val label: String, val score: Double) {
    A1("A1", 1.0), A2("A2", 2.0), B1("B1", 3.0), B2("B2", 4.0), C1("C1", 5.0),
}

/** 把连续能力值换算成"B1"/"B1+"这样的展示标签。 */
fun labelForScore(score: Double): String {
    val clamped = score.coerceIn(1.0, 5.0)
    val levels = CefrLevel.entries
    val lower = levels.last { it.score <= clamped }
    val idx = levels.indexOf(lower)
    val frac = clamped - lower.score
    return when {
        frac < 0.25 -> lower.label
        frac < 0.75 -> "${lower.label}+"
        else -> levels.getOrElse(idx + 1) { lower }.label
    }
}

/**
 * [labelForScore] 的近似逆运算：把请求给 AI 的等级字符串（"A2".."C1"，可能带 "+"）
 * 换回一个数值，用于记录"这道题的难度大概在哪"，供一致度/置信度计算。
 */
fun scoreForLabel(label: String): Double {
    val plus = label.endsWith("+")
    val base = CefrLevel.entries.firstOrNull { it.label == label.removeSuffix("+") } ?: CefrLevel.B1
    return if (plus) (base.score + 0.5).coerceAtMost(5.0) else base.score
}

@Serializable
data class AnsweredItem(val skill: String, val itemLevelScore: Double, val correct: Boolean)

@Serializable
data class AssessmentState(
    val score: Double,
    val answered: List<AnsweredItem>,
    val probedHigher: Boolean = false,
    /** 定位题 3/3 全对时，下一题强制探 C1；消费一次即清空。 */
    val pendingProbeLevel: String? = null,
)

/** 一道客观题。AI 生成，展示前必须过 [validateAssessmentQuestions]。 */
@Serializable
data class AssessmentQuestion(
    /** vocab / grammar / reading / pragmatics，见 [AssessmentSkill]。 */
    val skill: String,
    val prompt: String,
    val options: List<String>,
    val answerIndex: Int,
    val explanationZh: String,
    /** 仅 reading 技能非空：题目所依附的一小段短文（完形微文本 / 短阅读）。 */
    val passage: String? = null,
)

/** 逐题校验，返回通过的题目（结构坏的丢弃）。 */
fun validateAssessmentQuestions(questions: List<AssessmentQuestion>): List<AssessmentQuestion> =
    questions.filter { question ->
        question.prompt.isNotBlank() &&
            question.options.size in 3..5 &&
            question.options.toSet().size == question.options.size &&
            question.options.none { it.isBlank() } &&
            question.answerIndex in question.options.indices &&
            (question.skill != AssessmentSkill.Reading || !question.passage.isNullOrBlank())
    }

/** 下一步该做什么：出一道指定技能/难度的客观题，还是进入深度阅读模块（客观题梯度已经问够）。 */
sealed interface NextLadderStep {
    data class Question(val skill: String, val level: String) : NextLadderStep
    data object MoveToDeepReading : NextLadderStep
}

/** 单项技能在结果页的展示行。 */
data class SkillProfileRow(val name: String, val label: String, val pct: Int)

object AssessmentEngine {

    const val MIN_QUESTIONS = 8
    const val MAX_QUESTIONS = 16
    private const val EARLY_STOP_WINDOW = 6
    private const val EARLY_STOP_CONFIDENCE = 75
    private const val CORRECT_DELTA = 0.4
    private const val WRONG_DELTA = -0.4
    /** 探顶：题目难度比当前能力值高出这么多，才算真正"够着上限"的探测。 */
    private const val PROBE_MARGIN = 0.8

    fun initial(): AssessmentState = AssessmentState(score = 3.0, answered = emptyList())

    /**
     * 记录一次作答。前 3 题是定位题：只累积，不动态调分；答完第 3 题按
     * EXT_TEST_DESIGN.md 的表格一次性确定起点（0/1/2/3 对 → 1.5/2.0/3.0/4.0，
     * 3/3 全对额外标记"下一题强制探 C1"）。第 4 题起按连续能力值微调。
     */
    fun record(state: AssessmentState, skill: String, itemLevelScore: Double, correct: Boolean): AssessmentState {
        val answered = state.answered + AnsweredItem(skill, itemLevelScore, correct)

        if (answered.size <= 3) {
            if (answered.size < 3) return state.copy(answered = answered)
            val correctCount = answered.count { it.correct }
            val startScore = when (correctCount) {
                0 -> 1.5
                1 -> 2.0
                2 -> 3.0
                else -> 4.0
            }
            return AssessmentState(
                score = startScore,
                answered = answered,
                probedHigher = false,
                pendingProbeLevel = if (correctCount == 3) CefrLevel.C1.label else null,
            )
        }

        val delta = if (correct) CORRECT_DELTA else WRONG_DELTA
        val newScore = (state.score + delta).coerceIn(1.0, 5.0)
        val wasProbe = state.pendingProbeLevel != null || itemLevelScore >= state.score + PROBE_MARGIN
        return state.copy(
            score = newScore,
            answered = answered,
            probedHigher = state.probedHigher || wasProbe,
            pendingProbeLevel = null,
        )
    }

    /** 客观题梯度是否已经问够，见 EXT_TEST_DESIGN.md §2"建议停止条件"。 */
    fun isComplete(state: AssessmentState): Boolean {
        val n = state.answered.size
        if (n >= MAX_QUESTIONS) return true
        if (n < MIN_QUESTIONS) return false
        val coveredSkills = state.answered.map { it.skill }.distinct().size
        return coveredSkills >= AssessmentSkill.ladderSkills.size &&
            confidence(state) >= EARLY_STOP_CONFIDENCE &&
            state.probedHigher
    }

    /** 下一步：定位题固定技能/难度；之后按覆盖约束选技能，按能力值（或强制探顶）选难度。 */
    fun nextStep(state: AssessmentState): NextLadderStep {
        return when (state.answered.size) {
            0 -> NextLadderStep.Question(AssessmentSkill.Vocab, "A2")
            1 -> NextLadderStep.Question(AssessmentSkill.Grammar, "B1")
            2 -> NextLadderStep.Question(AssessmentSkill.Reading, "B1")
            else -> {
                if (isComplete(state)) return NextLadderStep.MoveToDeepReading
                val readyToStopExceptProbe = state.answered.size >= MIN_QUESTIONS &&
                    state.answered.map { it.skill }.distinct().size >= AssessmentSkill.ladderSkills.size &&
                    confidence(state) >= EARLY_STOP_CONFIDENCE &&
                    !state.probedHigher
                val level = when {
                    state.pendingProbeLevel != null -> state.pendingProbeLevel
                    readyToStopExceptProbe -> labelForScore((state.score + 1.0).coerceAtMost(5.0))
                    else -> labelForScore(state.score)
                }
                NextLadderStep.Question(forcedOrNextSkill(state.answered), level)
            }
        }
    }

    /** 连续 4 题同技能就强制换一个；否则优先补还没覆盖过的技能。都不成立时交给调用方自选。 */
    private fun forcedOrNextSkill(answered: List<AnsweredItem>): String {
        val recent = answered.takeLast(4).map { it.skill }
        if (recent.size == 4 && recent.distinct().size == 1) {
            return AssessmentSkill.ladderSkills.first { it != recent.first() }
        }
        val covered = answered.map { it.skill }.toSet()
        return AssessmentSkill.ladderSkills.firstOrNull { it !in covered }
            ?: AssessmentSkill.ladderSkills.random()
    }

    /** 一致度：最近几题里，"题目难度明显高于/低于当前能力值"的预期结果，和实际作答是否一致。 */
    fun confidence(state: AssessmentState): Int {
        val recent = state.answered.takeLast(EARLY_STOP_WINDOW)
        if (recent.isEmpty()) return 0
        val consistent = recent.count { isConsistent(it, state.score) }
        return consistent * 100 / recent.size
    }

    private fun isConsistent(answer: AnsweredItem, score: Double): Boolean {
        val diff = answer.itemLevelScore - score
        return when {
            diff > 0.4 -> !answer.correct
            diff < -0.4 -> answer.correct
            else -> true
        }
    }

    /** CEFR 等级对应的经验词汇量区间，只是估计，不是精确测量。 */
    fun vocabRangeText(score: Double): String = when {
        score < 1.5 -> "500～1000"
        score < 2.5 -> "1000～2000"
        score < 3.5 -> "2000～3250"
        score < 4.5 -> "3250～5000"
        else -> "5000～8000"
    }

    /** 合理区间（如"B1–B2"）：置信度越低，区间越宽。 */
    fun plausibleRange(state: AssessmentState): String {
        val width = when {
            confidence(state) >= 75 -> 0.4
            confidence(state) >= 50 -> 0.7
            else -> 1.0
        }
        val low = labelForScore(state.score - width)
        val high = labelForScore(state.score + width)
        return if (low == high) low else "$low–$high"
    }

    fun skillProfileRow(name: String, skill: String, answered: List<AnsweredItem>, score: Double): SkillProfileRow {
        val samples = answered.filter { it.skill == skill }
        if (samples.isEmpty()) return SkillProfileRow(name, "样本不足", 0)
        val accuracy = samples.count { it.correct }.toDouble() / samples.size
        val label = when {
            accuracy >= 0.75 -> "${labelForScore(score)} 偏上"
            accuracy >= 0.45 -> labelForScore(score)
            else -> "${labelForScore(score)} 偏下"
        }
        return SkillProfileRow(name, label, (accuracy * 100).roundToInt())
    }
}
