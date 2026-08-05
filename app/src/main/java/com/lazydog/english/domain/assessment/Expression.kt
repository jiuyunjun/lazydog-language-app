package com.lazydog.english.domain.assessment

import kotlinx.serialization.Serializable

/**
 * 开放表达（写一句话/写一段话）模块，对齐 EXT_TEST_DESIGN.md 五、六节：
 * 题面按能力值分档、本地模板出题；AI 只负责评分（两轮：先盲评再对照量表打分+举证），
 * 不反过来影响上面客观题梯度用的能力值——它是结果页里独立的一项，不是升降级的输入。
 */
object ExpressionDimension {
    const val TaskCompletion = "task_completion"
    const val Organization = "organization"
    const val GrammarControl = "grammar_control"
    const val Vocabulary = "vocabulary"
    const val Pragmatics = "pragmatics"

    val all = listOf(TaskCompletion, Organization, GrammarControl, Vocabulary, Pragmatics)

    fun label(dimension: String): String = when (dimension) {
        TaskCompletion -> "任务完成"
        Organization -> "组织连贯"
        GrammarControl -> "语法控制"
        Vocabulary -> "词汇能力"
        Pragmatics -> "语用得体"
        else -> dimension
    }
}

data class WritingTask(val promptZh: String, val minWords: Int, val maxWords: Int)

/** 按能力值分档出题，本地模板，不需要 AI（AI 只评分）。 */
object WritingTaskLibrary {

    fun taskFor(score: Double): WritingTask = when {
        score < 2.5 -> WritingTask(
            promptZh = "写一封给朋友的短消息，说说你上周末做了什么。提到：去了哪里、和谁一起、感觉怎么样。" +
                "用英文写，40～70 词。",
            minWords = 40,
            maxWords = 70,
        )
        score < 3.5 -> WritingTask(
            promptZh = "你网购的东西送错了。写一条给客服的留言，说清楚：你订的是什么、收到的是什么、" +
                "希望对方怎么处理。用英文写，60～100 词。",
            minWords = 60,
            maxWords = 100,
        )
        score < 4.5 -> WritingTask(
            promptZh = "你的团队在讨论是否允许员工每周有三天远程办公。写一段说明你的立场，包含：" +
                "你的态度、两条理由、一个可能的缺点、这个缺点可以怎么缓解。用英文写，120～180 词。",
            minWords = 120,
            maxWords = 180,
        )
        else -> WritingTask(
            promptZh = "有人认为公司应该完全取消坐班、全员远程；也有人反对。写一段权衡两种观点的分析，" +
                "说明你更倾向哪一边、为什么，以及反对意见里最有道理的部分是什么。用英文写，150～250 词。",
            minWords = 150,
            maxWords = 250,
        )
    }
}

@Serializable
data class DimensionScore(val dimension: String, val score: Int, val evidenceZh: List<String> = emptyList())

@Serializable
data class ExpressionRubric(val dimensions: List<DimensionScore>) {
    val total: Int get() = dimensions.sumOf { it.score }
}

/** 两轮评分结果：先盲评（不给参考等级）再对照量表打分。 */
data class ExpressionAssessment(
    val firstPass: ExpressionRubric,
    val secondPass: ExpressionRubric,
    /** 前后两轮总分差 > 3，或任一维度差 > 1，说明评分不太稳，值得在结果页提示一下，而不是直接采信。 */
    val needsReview: Boolean,
) {
    /** 展示用最终评分：两轮打分对照量表、又带举证的第二轮更可靠。 */
    val display: ExpressionRubric get() = secondPass
}

object ExpressionValidation {

    /** null 表示通过。 */
    fun validate(rubric: ExpressionRubric): String? {
        if (rubric.dimensions.map { it.dimension }.toSet() != ExpressionDimension.all.toSet()) {
            return "评分应该覆盖全部 5 个维度：${rubric.dimensions.map { it.dimension }}"
        }
        for (d in rubric.dimensions) {
            if (d.score !in 0..4) return "${ExpressionDimension.label(d.dimension)} 分数应该在 0~4，实际 ${d.score}"
        }
        return null
    }

    fun assess(firstPass: ExpressionRubric, secondPass: ExpressionRubric): ExpressionAssessment {
        val totalDiff = kotlin.math.abs(firstPass.total - secondPass.total)
        val perDimensionDiff = ExpressionDimension.all.any { dim ->
            val a = firstPass.dimensions.first { it.dimension == dim }.score
            val b = secondPass.dimensions.first { it.dimension == dim }.score
            kotlin.math.abs(a - b) > 1
        }
        return ExpressionAssessment(firstPass, secondPass, needsReview = totalDiff > 3 || perDimensionDiff)
    }
}
