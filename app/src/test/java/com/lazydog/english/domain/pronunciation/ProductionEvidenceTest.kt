package com.lazydog.english.domain.pronunciation

import com.lazydog.english.domain.speaking.AssessmentResult
import com.lazydog.english.domain.speaking.PhonemeFeedback
import com.lazydog.english.domain.speaking.PronunciationFeedback
import com.lazydog.english.domain.speaking.WordErrorType
import com.lazydog.english.domain.speaking.WordFeedback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 把一次评估翻译成「哪个音、怎么改」（`发音与音标学习DESIGN.md` §13、§26.3）。
 *
 * 最要紧的是**归一化**那一条：长音符和重音标记在服务返回和手写音位表里写法不一致，
 * 硬字符串相等会大面积匹配不上——而失败的样子是「界面上没有音素证据」，不会有任何报错。
 */
class ProductionEvidenceTest {

    private fun feedback(
        recognized: String = "think",
        score: Int = 78,
        words: List<WordFeedback> = listOf(
            WordFeedback(
                word = "think",
                accuracyScore = score,
                errorType = WordErrorType.None,
                phonemes = listOf(
                    PhonemeFeedback("θ", 58),
                    PhonemeFeedback("ɪ", 92),
                    PhonemeFeedback("ŋ", 88),
                    PhonemeFeedback("k", 90),
                ),
            ),
        ),
    ) = PronunciationFeedback(
        recognizedText = recognized,
        accuracyScore = score,
        fluencyScore = score,
        completenessScore = 100,
        pronunciationScore = score,
        words = words,
    )

    private val thPhoneme = Phoneme(
        id = "th-voiceless",
        ipa = "θ",
        category = PhonemeCategory.Consonant,
        groupZh = "摩擦音",
        shortDescriptionZh = "舌尖伸到齿间的清摩擦音",
        articulation = ArticulationGuide(keyActionZh = "舌尖搭在齿缝上，气一直漏"),
        commonErrors = listOf(
            CommonError(
                substituteIpa = "s",
                exampleEn = "think",
                soundsLikeEn = "sink",
                fixZh = "舌尖伸出来一点点",
            ),
        ),
    )

    @Test
    fun `长音符和重音标记不参与匹配`() {
        assertEquals("i", ProductionEvidence.normalizeIpa("iː"))
        assertEquals("i", ProductionEvidence.normalizeIpa("i:"))
        assertEquals("θ", ProductionEvidence.normalizeIpa("ˈθ"))
        assertEquals("er", ProductionEvidence.normalizeIpa("ˌer"))
    }

    @Test
    fun `拿得到目标音自己的分`() {
        assertEquals(58, ProductionEvidence.targetScore(feedback(), "θ"))
    }

    @Test
    fun `写法不一致也能对上`() {
        val long = feedback(
            words = listOf(
                WordFeedback("seat", 80, WordErrorType.None, listOf(PhonemeFeedback("i", 71))),
            ),
        )

        assertEquals(
            71,
            ProductionEvidence.targetScore(long, "iː"),
            )
    }

    @Test
    fun `同一个音出现多次取平均`() {
        val repeated = feedback(
            words = listOf(
                WordFeedback(
                    "thirtieth", 70, WordErrorType.None,
                    listOf(PhonemeFeedback("θ", 60), PhonemeFeedback("θ", 80)),
                ),
            ),
        )

        assertEquals(70, ProductionEvidence.targetScore(repeated, "θ"))
    }

    @Test
    fun `对不上就退回整体分，不是错误`() {
        assertNull(
            "拿不到音素证据是正常路径，调用方退回整体分",
            ProductionEvidence.targetScore(feedback(), "ʒ"),
        )
        assertNull(ProductionEvidence.targetScore(feedback(words = emptyList()), "θ"))
    }

    @Test
    fun `没听到人声算录音不可用`() {
        assertEquals(
            RecordingQuality.NoSpeech,
            ProductionEvidence.qualityOf(AssessmentResult.NothingRecognized, expectedWordCount = 1),
        )
        assertEquals(
            RecordingQuality.NoSpeech,
            ProductionEvidence.qualityOf(
                AssessmentResult.Done(feedback(recognized = "", words = emptyList())),
                expectedWordCount = 1,
            ),
        )
    }

    @Test
    fun `整句只读出一小半算被切掉了`() {
        val partial = AssessmentResult.Done(
            feedback(
                recognized = "I think",
                words = listOf(
                    WordFeedback("i", 80, WordErrorType.None),
                    WordFeedback("think", 80, WordErrorType.None),
                    WordFeedback("there", 0, WordErrorType.Omission),
                    WordFeedback("are", 0, WordErrorType.Omission),
                    WordFeedback("three", 0, WordErrorType.Omission),
                ),
            ),
        )

        assertEquals(
            RecordingQuality.Truncated,
            ProductionEvidence.qualityOf(partial, expectedWordCount = 5),
        )
    }

    @Test
    fun `单词只有一个词时不按截断判`() {
        assertEquals(
            RecordingQuality.Ok,
            ProductionEvidence.qualityOf(AssessmentResult.Done(feedback()), expectedWordCount = 1),
        )
    }

    @Test
    fun `不可用的录音不算分`() {
        val attempt = ProductionAttempt(
            targetId = "ph:th-voiceless",
            level = ProductionLevel.Word,
            text = "think",
            providerScore = null,
            targetScore = null,
            quality = RecordingQuality.NoSpeech,
        )

        assertNull(attempt.usableScore)
        assertTrue(RecordingQuality.NoSpeech.messageZh!!.isNotBlank())
    }

    @Test
    fun `分数够高就不给修正建议`() {
        assertNull(
            "读得好还提示怎么改，用户只会开始怀疑这个分数",
            ProductionEvidence.problem(thPhoneme, targetScore = 88),
        )
    }

    @Test
    fun `分数低时给的是音位表里手写的那条，不是现编的`() {
        val problem = ProductionEvidence.problem(thPhoneme, targetScore = 58)

        assertEquals(thPhoneme.commonErrors.first(), problem)
    }

    @Test
    fun `音位表里没写常见误读就不硬凑一句`() {
        val bare = thPhoneme.copy(commonErrors = emptyList())

        assertNull(ProductionEvidence.problem(bare, targetScore = 40))
    }
}
