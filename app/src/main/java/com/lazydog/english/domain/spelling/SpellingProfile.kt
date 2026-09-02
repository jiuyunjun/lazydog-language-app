package com.lazydog.english.domain.spelling

/**
 * 用户级拼写画像（设计稿 64 屏 / 拼写训练DESIGN.md §10）。
 *
 * 和单词级的 [SpellingProgress] 分开：一个词写不出来是这个词的问题，
 * 十个词都栽在双写上就是这个人的问题，后者才值得安排专项训练。
 * 全部由 spelling_attempts 重算，不单独维护一份可能失真的累计值。
 */
data class SpellingProfile(
    /** 六个维度的平均掌握度，0..1；没有任何记录时为 0。 */
    val masteryVector: Map<SpellingDimension, Double> = emptyMap(),
    /** 各错误类型占「错误次数」的比例，0..1。 */
    val errorRates: Map<SpellingErrorType, Double> = emptyMap(),
    val attemptCount: Int = 0,
    val wrongCount: Int = 0,
    /** 完整默写答对时的平均用时；没有样本为 null。 */
    val avgFreeRecallMillis: Long? = null,
) {
    val isEmpty: Boolean get() = attemptCount == 0

    /** 错得最集中的几类，用来在画像页给出「建议专项训练」。 */
    fun topErrorTypes(limit: Int = 2): List<SpellingErrorType> = errorRates.entries
        .filter { it.value > 0.0 }
        .sortedByDescending { it.value }
        .take(limit)
        .map { it.key }
}

enum class SpellingDimension(val labelZh: String) {
    Recognition("识别"),
    PartialRecall("局部回忆"),
    ChunkRecall("词块回忆"),
    PhonemeGrapheme("音形对应"),
    FreeRecall("完整默写"),
    Retention("长期保持"),
}

/** 画像页需要的一次拼写提交，字段是 spelling_attempts 的子集。 */
data class SpellingAttemptSummary(
    val correct: Boolean,
    val questionType: SpellingQuestionType,
    val errorTypes: Set<SpellingErrorType>,
    val responseTimeMillis: Long,
    val hintLevel: Int,
)

object SpellingProfiles {

    /**
     * 由全部拼写进度和提交记录算出画像。
     *
     * 错误率的分母是「错过的次数」而不是「答过的次数」：用户想知道的是
     * "我一错就错在哪儿"，而不是"我在所有题里漏字的概率"——后者会随着
     * 正确率上升一起变小，看不出弱点。
     */
    fun build(
        progress: List<SpellingProgress>,
        attempts: List<SpellingAttemptSummary>,
    ): SpellingProfile {
        if (attempts.isEmpty() && progress.isEmpty()) return SpellingProfile()

        val vector = if (progress.isEmpty()) {
            emptyMap()
        } else {
            mapOf(
                SpellingDimension.Recognition to progress.map { it.recognitionScore }.average(),
                SpellingDimension.PartialRecall to progress.map { it.partialRecallScore }.average(),
                SpellingDimension.ChunkRecall to progress.map { it.chunkRecallScore }.average(),
                SpellingDimension.PhonemeGrapheme to progress.map { it.phonemeGraphemeScore }.average(),
                SpellingDimension.FreeRecall to progress.map { it.freeRecallScore }.average(),
                SpellingDimension.Retention to progress.map { it.retentionScore }.average(),
            )
        }

        val wrong = attempts.filterNot { it.correct }
        val rates = if (wrong.isEmpty()) {
            emptyMap()
        } else {
            SpellingErrorType.entries.associateWith { type ->
                wrong.count { type in it.errorTypes }.toDouble() / wrong.size
            }.filterValues { it > 0.0 }
        }

        val freeRecallTimes = attempts
            .filter {
                it.correct &&
                    it.hintLevel == 0 &&
                    it.responseTimeMillis > 0 &&
                    (
                        it.questionType == SpellingQuestionType.FreeRecall ||
                            it.questionType == SpellingQuestionType.DelayedFreeRecall
                        )
            }
            .map { it.responseTimeMillis }

        return SpellingProfile(
            masteryVector = vector,
            errorRates = rates,
            attemptCount = attempts.size,
            wrongCount = wrong.size,
            avgFreeRecallMillis = freeRecallTimes.takeIf { it.isNotEmpty() }?.average()?.toLong(),
        )
    }

    /**
     * 「建议专项训练」那段文案。没有足够样本时返回 null——
     * 错两次就断言"这是你的弱点"，比不说更误导。
     */
    fun trainingAdvice(profile: SpellingProfile): String? {
        if (profile.wrongCount < MIN_WRONG_FOR_ADVICE) return null
        val top = profile.topErrorTypes()
        if (top.isEmpty()) return null
        val names = top.joinToString("和") { it.labelZh }
        return "$names 是你最集中的错误类型。挑几个同类词放在一组对比着练，比把同一个词再抄十遍管用。"
    }

    /** 少于这么多次错误就不下结论。 */
    const val MIN_WRONG_FOR_ADVICE = 5
}
