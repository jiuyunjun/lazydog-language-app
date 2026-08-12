package com.lazydog.english.domain.practice

import com.lazydog.english.core.model.ReviewGrade
import kotlin.math.min

/**
 * 产出方向的本地判分：中文释义 → 自己写出英文词。
 *
 * 存在的理由：只考"看英文认中文"练的是再认，词汇量本来就够的人越练越舒服，
 * 却仍然写不出来。熟了的词改成写出来，判分交给程序。
 */
enum class ProductionResult { Correct, Close, Wrong }

object ProductionCheck {

    /** 归一化：大小写、首尾空白、包裹的标点都不算错。 */
    fun normalize(text: String): String =
        text.trim().lowercase().trim { it in " .,!?;:'\"()[]" }

    fun check(answer: String, expected: String): ProductionResult {
        val a = normalize(answer)
        val b = normalize(expected)
        if (a.isEmpty()) return ProductionResult.Wrong
        if (a == b) return ProductionResult.Correct
        // 长词允许一处拼写差错：想起来了但手滑，不该按"忘了"处理。
        val tolerance = if (b.length >= 6) 1 else 0
        return if (tolerance > 0 && editDistance(a, b) <= tolerance) {
            ProductionResult.Close
        } else {
            ProductionResult.Wrong
        }
    }

    /** 判分结果直接映射复习评分；用户只在"其实很熟"时手动升一档。 */
    fun gradeFor(result: ProductionResult): ReviewGrade = when (result) {
        ProductionResult.Correct -> ReviewGrade.Good
        ProductionResult.Close -> ReviewGrade.Hard
        ProductionResult.Wrong -> ReviewGrade.Forgot
    }

    /** 给产出卡的提示：首字母 + 长度，避免完全没头绪时干瞪眼。 */
    fun hint(term: String): String {
        val clean = term.trim()
        if (clean.isEmpty()) return ""
        return clean.first() + "·".repeat((clean.length - 1).coerceAtMost(20))
    }

    private fun editDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = min(min(current[j - 1] + 1, previous[j] + 1), substitution)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }
}
