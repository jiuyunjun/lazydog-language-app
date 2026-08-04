package com.lazydog.english.domain.assessment

import kotlinx.serialization.Serializable

/**
 * 能力测试的自适应内核（AI_CONTRACTS.md §6：升降级由本地程序控制，AI 只出题）。
 * 阶梯规则：同级连对 2 题升一级，答错立刻降一级；从 A2 开始，共 12 题。
 * 纯函数 + 可序列化状态，支持中断恢复和单测。
 */
@Serializable
enum class CefrLevel(val label: String) {
    A1("A1"), A2("A2"), B1("B1"), B2("B2"), C1("C1");

    fun up(): CefrLevel = entries.getOrElse(ordinal + 1) { this }
    fun down(): CefrLevel = entries.getOrElse(ordinal - 1) { this }
}

@Serializable
data class AnsweredQuestion(val level: CefrLevel, val correct: Boolean)

@Serializable
data class AssessmentState(
    val currentLevel: CefrLevel,
    val answered: List<AnsweredQuestion>,
    /** 当前等级上的连对数。 */
    val streak: Int,
)

data class AssessmentOutcome(
    val level: CefrLevel,
    /** 0..100，最近作答与最终等级的一致程度。 */
    val confidencePercent: Int,
    val correctCount: Int,
    val totalCount: Int,
) {
    val confidenceLabel: String
        get() = when {
            confidencePercent >= 75 -> "较有把握"
            confidencePercent >= 50 -> "大致靠谱"
            else -> "只是初步估计"
        }
}

/** 一道测试题。AI 生成，展示前必须过 [validateAssessmentQuestions]。 */
@Serializable
data class AssessmentQuestion(
    /** vocab / grammar */
    val skill: String,
    val prompt: String,
    val options: List<String>,
    val answerIndex: Int,
    val explanationZh: String,
)

/** 逐题校验，返回通过的题目（结构坏的丢弃）。 */
fun validateAssessmentQuestions(questions: List<AssessmentQuestion>): List<AssessmentQuestion> =
    questions.filter { question ->
        question.prompt.isNotBlank() &&
            question.options.size in 3..5 &&
            question.options.toSet().size == question.options.size &&
            question.options.none { it.isBlank() } &&
            question.answerIndex in question.options.indices
    }

/** 中断恢复用的持久化快照。 */
@Serializable
data class SavedAssessment(
    val state: AssessmentState,
    val queue: List<AssessmentQuestion>,
)

object AssessmentEngine {

    const val TOTAL_QUESTIONS = 12

    fun initial(): AssessmentState = AssessmentState(CefrLevel.A2, emptyList(), 0)

    fun record(state: AssessmentState, correct: Boolean): AssessmentState {
        val answered = state.answered + AnsweredQuestion(state.currentLevel, correct)
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

    fun isComplete(state: AssessmentState): Boolean = state.answered.size >= TOTAL_QUESTIONS

    /**
     * 结果：最终等级取收尾等级；置信度看最近 6 题里有多少题“符合”该等级
     * （低于等级的题答对、高于等级的题答错都算符合）。
     */
    fun result(state: AssessmentState): AssessmentOutcome {
        val level = state.currentLevel
        val recent = state.answered.takeLast(6)
        val consistent = recent.count { answer ->
            when {
                answer.level.ordinal < level.ordinal -> answer.correct
                answer.level.ordinal > level.ordinal -> !answer.correct
                else -> answer.correct
            }
        }
        val confidence = if (recent.isEmpty()) 0 else consistent * 100 / recent.size
        return AssessmentOutcome(
            level = level,
            confidencePercent = confidence,
            correctCount = state.answered.count { it.correct },
            totalCount = state.answered.size,
        )
    }
}
