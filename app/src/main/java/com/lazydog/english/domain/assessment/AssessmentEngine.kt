package com.lazydog.english.domain.assessment

import kotlin.math.roundToInt
import kotlinx.serialization.Serializable

/**
 * 能力测试的自适应内核（AI_CONTRACTS.md §6：升降级由本地程序控制，AI 只出题）。
 * 阶梯规则：同级连对 2 题升一级，答错立刻降一级；从 A2 开始。
 * 长度不固定（DESIGN.md）：最少 [MIN_QUESTIONS] 题，最近几题若已经很稳定可提前结束，
 * 最多 [MAX_QUESTIONS] 题封顶。纯函数 + 可序列化状态，支持中断恢复和单测。
 */
@Serializable
enum class CefrLevel(val label: String) {
    A1("A1"), A2("A2"), B1("B1"), B2("B2"), C1("C1");

    fun up(): CefrLevel = entries.getOrElse(ordinal + 1) { this }
    fun down(): CefrLevel = entries.getOrElse(ordinal - 1) { this }
}

/** 三种客观题技能；"分级阅读" 附带一段短文（[AssessmentQuestion.passage]）。 */
object AssessmentSkill {
    const val Vocab = "vocab"
    const val Grammar = "grammar"
    const val Reading = "reading"
}

@Serializable
data class AnsweredQuestion(val level: CefrLevel, val skill: String, val correct: Boolean)

@Serializable
data class AssessmentState(
    val currentLevel: CefrLevel,
    val answered: List<AnsweredQuestion>,
    /** 当前等级上的连对数。 */
    val streak: Int,
)

/** 单项技能在结果页的展示行：词汇广度 / 语法 / 阅读理解 / 表达。 */
data class SkillProfileRow(val name: String, val label: String, val pct: Int)

data class AssessmentOutcome(
    val level: CefrLevel,
    /** 0..100，最近作答与最终等级的一致程度。 */
    val confidencePercent: Int,
    val correctCount: Int,
    val totalCount: Int,
    /** 按 CEFR 等级估算的词汇量区间，纯粹是经验区间，不是精确测量。 */
    val vocabRangeText: String,
    val profile: List<SkillProfileRow>,
    /** “还看不太准的”：哪些技能样本太少，测完的第一版画像里格外不确定。 */
    val watchNoteZh: String,
) {
    val confidenceLabel: String
        get() = when {
            confidencePercent >= 75 -> "较有把握"
            confidencePercent >= 50 -> "大致靠谱"
            else -> "只是初步估计"
        }
}

/** 一道客观题。AI 生成，展示前必须过 [validateAssessmentQuestions]。 */
@Serializable
data class AssessmentQuestion(
    /** vocab / grammar / reading，见 [AssessmentSkill]。 */
    val skill: String,
    val prompt: String,
    val options: List<String>,
    val answerIndex: Int,
    val explanationZh: String,
    /** 仅 reading 技能非空：题目所依附的一小段短文。 */
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

/** 开放表达任务：本地模板出题，AI 只负责评估（不参与等级升降）。 */
enum class ExpressionRating { Good, NeedsWork }

data class ExpressionFeedback(
    val suggestionEn: String,
    val issueZh: String,
    val explanationZh: String,
    val rating: ExpressionRating,
)

/** 中断恢复用的持久化快照。 */
@Serializable
data class SavedAssessment(
    val state: AssessmentState,
    val queue: List<AssessmentQuestion>,
    /** 客观题梯度测完后，写一句话的题面；为空表示还没生成过。 */
    val expressionTaskZh: String? = null,
    /** 写一句话这一步是否已经结束（提交成功或用户跳过）。 */
    val expressionDone: Boolean = false,
)

object AssessmentEngine {

    const val MIN_QUESTIONS = 8
    const val MAX_QUESTIONS = 16
    private const val EARLY_STOP_WINDOW = 6
    private const val EARLY_STOP_CONFIDENCE = 80

    /** 写一句话的题面，出题不依赖 AI（评估才用 AI），本地固定模板即可。 */
    val expressionPrompts = listOf(
        "用两三句英文写一写你今天做了什么，或者最近在忙什么。",
        "用两三句英文说说你对一个感兴趣话题的看法，随便哪个都行。",
        "用两三句英文描述一次让你印象深的旅行或经历。",
        "用两三句英文说说你周末一般怎么过。",
    )

    fun initial(): AssessmentState = AssessmentState(CefrLevel.A2, emptyList(), 0)

    fun record(state: AssessmentState, skill: String, correct: Boolean): AssessmentState {
        val answered = state.answered + AnsweredQuestion(state.currentLevel, skill, correct)
        return if (correct) {
            val streak = state.streak + 1
            if (streak >= 2) {
                AssessmentState(state.currentLevel.up(), answered, 0)
            } else {
                state.copy(answered = answered, streak = streak)
            }
        } else {
            AssessmentState(state.currentLevel.down(), answered, 0)
        }
    }

    /**
     * 客观题梯度是否已经问够：至少 [MIN_QUESTIONS] 题，且满足其一才停——
     * 已经封顶 [MAX_QUESTIONS]，或最近几题的表现已经和当前等级很一致（提前结束，长度不固定）。
     */
    fun isComplete(state: AssessmentState): Boolean {
        val n = state.answered.size
        if (n >= MAX_QUESTIONS) return true
        if (n < MIN_QUESTIONS) return false
        return levelConfidence(state.answered, state.currentLevel) >= EARLY_STOP_CONFIDENCE
    }

    private fun levelConsistent(answer: AnsweredQuestion, level: CefrLevel): Boolean = when {
        answer.level.ordinal < level.ordinal -> answer.correct
        answer.level.ordinal > level.ordinal -> !answer.correct
        else -> answer.correct
    }

    private fun levelConfidence(answered: List<AnsweredQuestion>, level: CefrLevel): Int {
        val recent = answered.takeLast(EARLY_STOP_WINDOW)
        if (recent.isEmpty()) return 0
        val consistent = recent.count { levelConsistent(it, level) }
        return consistent * 100 / recent.size
    }

    /**
     * 结果：最终等级取收尾等级；置信度看最近几题里有多少题“符合”该等级。
     * [expression] 为 null 表示写一句话被跳过，表达那一行记为“样本不足”。
     */
    fun result(state: AssessmentState, expression: ExpressionFeedback?): AssessmentOutcome {
        val level = state.currentLevel
        val confidence = levelConfidence(state.answered, level)

        val profile = listOf(
            skillRow("词汇广度", AssessmentSkill.Vocab, state.answered, level),
            skillRow("语法（时态 / 从句）", AssessmentSkill.Grammar, state.answered, level),
            skillRow("阅读理解", AssessmentSkill.Reading, state.answered, level),
            expressionRow(expression),
        )

        val thinSkills = profile.filter { it.pct == 0 }.map { it.name }
        val watchNote = if (thinSkills.isEmpty()) {
            "各项都测到了几次，接下来两周的表现还会继续修正它。"
        } else {
            "${thinSkills.joinToString("、")}样本还太少，前几天会多留意，别太当真。"
        }

        return AssessmentOutcome(
            level = level,
            confidencePercent = confidence,
            correctCount = state.answered.count { it.correct },
            totalCount = state.answered.size,
            vocabRangeText = vocabRangeText(level),
            profile = profile,
            watchNoteZh = watchNote,
        )
    }

    private fun skillRow(
        name: String,
        skill: String,
        answered: List<AnsweredQuestion>,
        level: CefrLevel,
    ): SkillProfileRow {
        val samples = answered.filter { it.skill == skill }
        if (samples.isEmpty()) return SkillProfileRow(name, "样本不足", 0)
        val accuracy = samples.count { it.correct }.toDouble() / samples.size
        val label = when {
            accuracy >= 0.75 -> "${level.label} 偏上"
            accuracy >= 0.45 -> level.label
            else -> "${level.label} 偏下"
        }
        return SkillProfileRow(name, label, (accuracy * 100).roundToInt())
    }

    private fun expressionRow(expression: ExpressionFeedback?): SkillProfileRow = when {
        expression == null -> SkillProfileRow("表达", "样本不足", 0)
        expression.rating == ExpressionRating.Good -> SkillProfileRow("表达", "基本达标", 78)
        else -> SkillProfileRow("表达", "还需要多练", 42)
    }

    /** CEFR 等级对应的经验词汇量区间，只是估计，不是精确测量。 */
    fun vocabRangeText(level: CefrLevel): String = when (level) {
        CefrLevel.A1 -> "500～1000"
        CefrLevel.A2 -> "1000～2000"
        CefrLevel.B1 -> "2000～3250"
        CefrLevel.B2 -> "3250～5000"
        CefrLevel.C1 -> "5000～8000"
    }
}
