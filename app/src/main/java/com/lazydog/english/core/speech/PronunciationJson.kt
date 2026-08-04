package com.lazydog.english.core.speech

import com.lazydog.english.domain.speaking.PronunciationFeedback
import com.lazydog.english.domain.speaking.WordErrorType
import com.lazydog.english.domain.speaking.WordFeedback
import kotlin.math.roundToInt
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json

/**
 * 解析 Azure Speech 识别结果里的发音评估 JSON
 * （PropertyId.SpeechServiceResponse_JsonResult）。
 * 独立成纯函数，方便不依赖 SDK 做单元测试。
 */
object PronunciationJson {

    private val json = Json { ignoreUnknownKeys = true }

    /** @return null 表示 JSON 里没有可用的评估结果。 */
    fun parse(raw: String): PronunciationFeedback? {
        val response = runCatching { json.decodeFromString<ResponsePayload>(raw) }.getOrNull() ?: return null
        val best = response.nBest.firstOrNull() ?: return null
        val assessment = best.pronunciationAssessment ?: return null
        return PronunciationFeedback(
            recognizedText = response.displayText.ifBlank { best.display },
            accuracyScore = assessment.accuracyScore.roundToInt(),
            fluencyScore = assessment.fluencyScore.roundToInt(),
            completenessScore = assessment.completenessScore.roundToInt(),
            pronunciationScore = assessment.pronScore.roundToInt(),
            words = best.words.map { word ->
                WordFeedback(
                    word = word.word,
                    accuracyScore = (word.pronunciationAssessment?.accuracyScore ?: 0.0).roundToInt(),
                    errorType = word.pronunciationAssessment?.errorType.toWordErrorType(),
                )
            },
        )
    }

    private fun String?.toWordErrorType(): WordErrorType = when (this) {
        null, "None" -> WordErrorType.None
        "Mispronunciation" -> WordErrorType.Mispronunciation
        "Omission" -> WordErrorType.Omission
        "Insertion" -> WordErrorType.Insertion
        else -> WordErrorType.Unknown
    }

    @Serializable
    private data class ResponsePayload(
        @SerialName("DisplayText") val displayText: String = "",
        @SerialName("NBest") val nBest: List<NBestPayload> = emptyList(),
    )

    @Serializable
    private data class NBestPayload(
        @SerialName("Display") val display: String = "",
        @SerialName("PronunciationAssessment") val pronunciationAssessment: AssessmentPayload? = null,
        @SerialName("Words") val words: List<WordPayload> = emptyList(),
    )

    @Serializable
    private data class AssessmentPayload(
        @SerialName("AccuracyScore") val accuracyScore: Double = 0.0,
        @SerialName("FluencyScore") val fluencyScore: Double = 0.0,
        @SerialName("CompletenessScore") val completenessScore: Double = 0.0,
        @SerialName("PronScore") val pronScore: Double = 0.0,
    )

    @Serializable
    private data class WordPayload(
        @SerialName("Word") val word: String = "",
        @SerialName("PronunciationAssessment") val pronunciationAssessment: WordAssessmentPayload? = null,
    )

    @Serializable
    private data class WordAssessmentPayload(
        @SerialName("AccuracyScore") val accuracyScore: Double = 0.0,
        @SerialName("ErrorType") val errorType: String = "None",
    )
}
