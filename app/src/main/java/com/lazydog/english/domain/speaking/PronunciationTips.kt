package com.lazydog.english.domain.speaking

/**
 * 朗读反馈的"少量可理解提示"（DESIGN.md 屏 19、PRODUCT.md §6.5：
 * 避免伪精确的综合分数，只给 2～3 条听得懂的提示）。
 * 分数/错误类型来自 Azure（客观数据），AI 只负责把它们讲成人话——
 * 结论本身（good/attention）仍由本地规则决定，不是 AI 随便定的。
 */
enum class TipKind { Good, Attention }

data class PronunciationTip(val kind: TipKind, val titleZh: String, val bodyZh: String)

/** 逐条校验：结构不对的丢弃；最多保留 3 条。 */
fun validatePronunciationTips(tips: List<PronunciationTip>): List<PronunciationTip> =
    tips.filter { it.titleZh.isNotBlank() && it.bodyZh.isNotBlank() }.take(3)

/**
 * AI 不可用时的本地兜底：不编造语音学细节，只根据客观数据给最朴素的提示。
 * 依然不出现任何数字分数。
 */
fun localPronunciationTips(feedback: PronunciationFeedback): List<PronunciationTip> {
    val problems = feedback.problemWords
    if (problems.isEmpty()) {
        return listOf(PronunciationTip(TipKind.Good, "整句听得懂", "识别结果和原句基本一致，节奏也挺稳。"))
    }
    return problems.take(3).map { word ->
        val bodyZh = when (word.errorType) {
            WordErrorType.Omission -> "好像没读出来，试着把这个词读完整。"
            WordErrorType.Insertion -> "这里好像多读了点什么，对照原句慢一点。"
            else -> "这个词听着不太准，可以听一遍标准音对一下。"
        }
        PronunciationTip(TipKind.Attention, "${word.word} 值得再听一遍", bodyZh)
    }
}
