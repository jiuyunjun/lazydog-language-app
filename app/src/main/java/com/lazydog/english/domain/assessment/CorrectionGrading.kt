package com.lazydog.english.domain.assessment

/**
 * 纠错短答的本地判分（§4.1"允许部分分，避免只判完全对错"）。
 * 不用 AI 判分——按和参考修改版的归一化编辑距离相似度分档，确定性、可测试。
 */
object CorrectionGrading {

    private const val CORRECT_THRESHOLD = 0.92
    private const val PARTIAL_THRESHOLD = 0.6

    fun grade(item: CorrectionItem, userAnswer: String): AnswerOutcome {
        val normalizedAnswer = normalize(userAnswer)
        if (normalizedAnswer.isBlank()) return AnswerOutcome.Wrong

        val normalizedOriginal = normalize(item.incorrectSentence)
        val normalizedReference = normalize(item.referenceCorrection)

        // 原句和参考答案往往只差一两个词，绝对相似度分不清"没改"和"改对了"，
        // 所以先处理两种确定情形，再看谁更接近谁。
        if (normalizedAnswer == normalizedReference) return AnswerOutcome.Correct
        if (normalizedAnswer == normalizedOriginal) return AnswerOutcome.Wrong

        val similarityToReference = similarity(normalizedAnswer, normalizedReference)
        val similarityToOriginal = similarity(normalizedAnswer, normalizedOriginal)

        // 没往参考答案的方向改（跟原句一样近甚至更近），不算数，哪怕绝对数值不低。
        if (similarityToReference <= similarityToOriginal) return AnswerOutcome.Wrong

        return when {
            similarityToReference >= CORRECT_THRESHOLD -> AnswerOutcome.Correct
            similarityToReference >= PARTIAL_THRESHOLD -> AnswerOutcome.Partial
            else -> AnswerOutcome.Wrong
        }
    }

    private fun normalize(text: String): String =
        text.trim().lowercase().replace(Regex("[.,!?;:]"), "").replace(Regex("\\s+"), " ")

    /** 1 - 归一化编辑距离，越接近 1 越像。 */
    private fun similarity(a: String, b: String): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        val maxLen = maxOf(a.length, b.length)
        if (maxLen == 0) return 1.0
        return 1.0 - levenshtein(a, b).toDouble() / maxLen
    }

    private fun levenshtein(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                dp[i][j] = if (a[i - 1] == b[j - 1]) {
                    dp[i - 1][j - 1]
                } else {
                    1 + minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1])
                }
            }
        }
        return dp[a.length][b.length]
    }
}
