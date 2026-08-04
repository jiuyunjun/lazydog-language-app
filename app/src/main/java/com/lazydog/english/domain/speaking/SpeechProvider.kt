package com.lazydog.english.domain.speaking

/**
 * 朗读服务的领域接口（ARCHITECTURE.md §8）：UI 与领域层只依赖它，不碰具体 SDK。
 */
interface SpeechProvider {

    /** 朗读一段英文示范音频，播放完成后返回。 */
    suspend fun speak(text: String): SpeakResult

    /**
     * 从麦克风录一句朗读并对照 [referenceText] 做发音评估。
     * 调用前必须已获得录音权限。
     */
    suspend fun assessReading(referenceText: String): AssessmentResult

    /** 释放底层资源。释放后实例不可再用。 */
    fun close()
}

sealed interface SpeakResult {
    data object Done : SpeakResult
    data class Failed(val reason: String) : SpeakResult
}

sealed interface AssessmentResult {
    data class Done(val feedback: PronunciationFeedback) : AssessmentResult
    /** 没听清 / 没说话。 */
    data object NothingRecognized : AssessmentResult
    data class Failed(val reason: String) : AssessmentResult
}

/** 发音反馈，分数为百分制。 */
data class PronunciationFeedback(
    val recognizedText: String,
    val accuracyScore: Int,
    val fluencyScore: Int,
    val completenessScore: Int,
    val pronunciationScore: Int,
    val words: List<WordFeedback>,
) {
    /** 明显有问题的词：读错、漏读或准确度低。 */
    val problemWords: List<WordFeedback>
        get() = words.filter { it.errorType != WordErrorType.None || it.accuracyScore < 60 }
}

data class WordFeedback(
    val word: String,
    val accuracyScore: Int,
    val errorType: WordErrorType,
)

enum class WordErrorType(val labelZh: String) {
    None("正常"),
    Mispronunciation("读音不准"),
    Omission("漏读了"),
    Insertion("多读了"),
    Unknown("待确认"),
}

/** 总分到中文短评的映射。语气轻松，不制造焦虑（AGENTS.md §5）。 */
fun overallComment(pronunciationScore: Int): String = when {
    pronunciationScore >= 85 -> "读得挺顺，这句过了。"
    pronunciationScore >= 70 -> "大体不错，个别词再顺一顺。"
    pronunciationScore >= 50 -> "能听懂，但有几个词值得再来一遍。"
    else -> "这句有点难，听一遍示范再试试。"
}
