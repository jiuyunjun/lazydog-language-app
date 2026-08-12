package com.lazydog.english.domain.generation

import com.lazydog.english.core.model.ReviewGrade
import com.lazydog.english.domain.practice.GrammarErrorTag

/**
 * 语法练习题（挖空变形）。
 *
 * 存在的理由：只读讲解练不出形式控制——看懂"现在完成进行时"和写对 have been coming
 * 是两回事。语法点学完要当场用出来，到期复习出的也是题，不是再读一遍讲解。
 */
data class GrammarDrillRequest(
    val patternEn: String,
    val labelZh: String,
    val summaryZh: String,
    val learnerLevel: String,
    val count: Int = 4,
)

/** 一句挖了一个空的英文，选项是同一处的不同形式。 */
data class GrammarDrillItem(
    val sentenceEn: String,
    val options: List<String>,
    val answerIndex: Int,
    val explanationZh: String,
    /** 这道题考的形式类别（见 [GrammarErrorTag]）；答错时按它归类，决定后面讲什么。 */
    val errorTag: String = GrammarErrorTag.Other,
) {
    val answer: String get() = options.getOrElse(answerIndex) { "" }

    /** 把空替换成用户选的形式，用于答题后展示完整句子。 */
    fun filledWith(option: String): String =
        sentenceEn.replace(GrammarDrillValidation.BLANK, option)
}

object GrammarDrillValidation {

    const val BLANK = "___"
    const val MIN_ITEMS = 2
    private const val MAX_OPTION_LENGTH = 40
    private val blankPattern = Regex("_{2,}")

    /** 逐题校验，丢掉坏题；剩下的少于 [MIN_ITEMS] 时返回空，调用方按失败处理。 */
    fun validate(items: List<GrammarDrillItem>, maxCount: Int): List<GrammarDrillItem> {
        val valid = items.mapNotNull { normalize(it) }.filter { problem(it) == null }
        return if (valid.size < MIN_ITEMS) emptyList() else valid.take(maxCount)
    }

    /** 统一空格写法：AI 常写成 ____ 或 _____，一律归成 [BLANK]。 */
    fun normalize(item: GrammarDrillItem): GrammarDrillItem? {
        val sentence = item.sentenceEn.trim().replace(blankPattern, BLANK)
        return item.copy(
            sentenceEn = sentence,
            options = item.options.map { it.trim() },
            explanationZh = item.explanationZh.trim(),
            errorTag = GrammarErrorTag.normalize(item.errorTag),
        )
    }

    /** null 表示通过；否则是丢弃原因，进 droppedNotes 便于排错。 */
    fun problem(item: GrammarDrillItem): String? {
        val blanks = BLANK.toRegex().findAll(item.sentenceEn).count()
        return when {
            item.sentenceEn.isBlank() -> "题干是空的"
            blanks == 0 -> "题干没有挖空：${item.sentenceEn}"
            blanks > 1 -> "一题只能挖一个空：${item.sentenceEn}"
            item.options.size !in 3..4 -> "选项应该 3~4 个，实际 ${item.options.size}"
            item.options.any { it.isBlank() } -> "有空选项"
            item.options.any { it.length > MAX_OPTION_LENGTH } -> "选项太长，考的应该是形式不是整句"
            item.options.any { it.contains("_") } -> "选项里不该再出现下划线"
            item.options.map { it.lowercase() }.toSet().size != item.options.size -> "选项重复"
            item.answerIndex !in item.options.indices -> "answerIndex 越界：${item.answerIndex}"
            item.explanationZh.isBlank() -> "缺解析"
            else -> null
        }
    }
}

object GrammarDrillGrading {

    /**
     * 用客观正确率给复习评分，不再让用户自评"想起来了没"。
     * 语法的关键是写没写对形式，这件事程序判得比人准。
     */
    fun gradeFor(correctCount: Int, total: Int): ReviewGrade {
        if (total <= 0) return ReviewGrade.Forgot
        val ratio = correctCount.toDouble() / total
        return when {
            ratio >= 1.0 -> ReviewGrade.Easy
            ratio >= 0.75 -> ReviewGrade.Good
            ratio >= 0.5 -> ReviewGrade.Hard
            else -> ReviewGrade.Forgot
        }
    }
}
