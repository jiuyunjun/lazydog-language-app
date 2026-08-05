package com.lazydog.english.domain.assessment

import kotlin.math.roundToInt
import kotlinx.serialization.Serializable

/**
 * 客观题梯度问够之后的独立阅读模块（EXT_TEST_DESIGN.md §四）：
 * 一篇按等级定长的短文，配 4 道各测不同能力的题——主旨、细节、推断、词义/指代，
 * 按 3/2/3/2 加权算分，不影响上面梯度用的能力值。
 */
object ReadingTag {
    const val MainIdea = "main_idea"
    const val Detail = "detail"
    const val Inference = "inference"
    const val VocabReference = "vocab_reference"

    val all = listOf(MainIdea, Detail, Inference, VocabReference)

    fun label(tag: String): String = when (tag) {
        MainIdea -> "主旨"
        Detail -> "细节"
        Inference -> "推断"
        VocabReference -> "词义或指代"
        else -> tag
    }

    fun weight(tag: String): Int = when (tag) {
        MainIdea -> 3
        Detail -> 2
        Inference -> 3
        VocabReference -> 2
        else -> 0
    }

    const val TOTAL_WEIGHT = 10
}

@Serializable
data class DeepReadingQuestion(
    val tag: String,
    val prompt: String,
    val options: List<String>,
    val answerIndex: Int,
    val explanationZh: String,
)

@Serializable
data class DeepReadingTask(
    val passage: String,
    val questions: List<DeepReadingQuestion>,
)

/** 每题作答后的结果，用于算分和展示。 */
data class DeepReadingAnswer(val question: DeepReadingQuestion, val selected: Int)

data class DeepReadingOutcome(val pct: Int, val correctWeight: Int)

object DeepReadingValidation {

    /** EXT_TEST_DESIGN.md §四 的长度建议，允许上下浮动，避免 AI 差一两个词就被拒。 */
    fun lengthRange(level: String): IntRange = when {
        level.startsWith("A1") -> 60..150
        level.startsWith("A2") -> 100..220
        level.startsWith("B1") -> 160..350
        level.startsWith("B2") -> 280..600
        else -> 400..800
    }

    /** null 表示通过；非空是拒绝原因。 */
    fun validate(task: DeepReadingTask, level: String): String? {
        val wordCount = Regex("[A-Za-z'\\-]+").findAll(task.passage).count()
        val range = lengthRange(level)
        if (wordCount !in range) return "短文长度 $wordCount 词，不在 ${range.first}~${range.last} 的范围内"
        if (task.questions.size != 4) return "应该有 4 道题，实际 ${task.questions.size} 道"
        val tags = task.questions.map { it.tag }
        if (tags.toSet() != ReadingTag.all.toSet()) {
            return "四道题应该分别覆盖 主旨/细节/推断/词义或指代，实际是：${tags.joinToString()}"
        }
        for ((index, question) in task.questions.withIndex()) {
            val label = "第 ${index + 1} 题"
            if (question.prompt.isBlank()) return "$label 缺少题干"
            if (question.options.size !in 3..5) return "$label 选项数量不对"
            if (question.options.toSet().size != question.options.size) return "$label 选项重复"
            if (question.answerIndex !in question.options.indices) return "$label 答案索引越界"
        }
        return null
    }

    fun score(answers: List<DeepReadingAnswer>): DeepReadingOutcome {
        val correctWeight = answers.sumOf { if (it.selected == it.question.answerIndex) ReadingTag.weight(it.question.tag) else 0 }
        val pct = (correctWeight.toDouble() / ReadingTag.TOTAL_WEIGHT * 100).roundToInt()
        return DeepReadingOutcome(pct, correctWeight)
    }
}
