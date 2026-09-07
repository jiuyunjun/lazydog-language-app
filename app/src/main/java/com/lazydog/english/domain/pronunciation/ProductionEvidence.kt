package com.lazydog.english.domain.pronunciation

import com.lazydog.english.domain.speaking.AssessmentResult
import com.lazydog.english.domain.speaking.PronunciationFeedback
import com.lazydog.english.domain.speaking.WordErrorType

/**
 * 把一次发音评估翻译成「哪个音、怎么改」（`发音与音标学习DESIGN.md` §13、§25.2、§26）。
 *
 * 服务返回的分数只是**证据**，不是结论。这里做三件事：判断这次录音算不算数、
 * 从音素级证据里挑出目标音自己的分、把低分对应到一句用户能照着做的修正动作。
 *
 * 那句修正动作**来自音位表里手写的常见误读，不是现编的**：模型或规则猜出来的
 * 「你可能把它读成了 X」如果猜错，用户会照着一个错误的动作去改，比不给建议更糟。
 */
object ProductionEvidence {

    /**
     * 归一化 IPA 符号，用于把服务返回的音素和音位表里的音位对上。
     *
     * 去掉长音符、重音标记和音节分隔：两边的写法不一定一致（`iː` / `i`、`ˈθ` / `θ`），
     * 硬字符串相等会大面积匹配不上，而那时候界面上只是「没有音素证据」，**不会有任何报错**。
     */
    fun normalizeIpa(symbol: String): String = symbol
        .filterNot { it in IGNORED_MARKS }
        .lowercase()
        .trim()

    /**
     * 这次录音算不算数（§26.3）。
     *
     * 只判断**能判断的**：没听到人声、以及明显只读了一部分。麦克风音量和录音截断需要拿到
     * 原始音频才谈得上判断，这一版不做——与其用一个猜的规则去否定用户的录音，
     * 不如少判一种。
     */
    fun qualityOf(result: AssessmentResult, expectedWordCount: Int): RecordingQuality = when (result) {
        is AssessmentResult.NothingRecognized -> RecordingQuality.NoSpeech
        is AssessmentResult.Failed -> RecordingQuality.NoSpeech
        is AssessmentResult.Done -> {
            val feedback = result.feedback
            val spoken = feedback.words.count { it.errorType != WordErrorType.Omission }
            when {
                feedback.recognizedText.isBlank() || spoken == 0 -> RecordingQuality.NoSpeech
                expectedWordCount > 1 && spoken * 2 < expectedWordCount -> RecordingQuality.Truncated
                else -> RecordingQuality.Ok
            }
        }
    }

    /**
     * 目标音自己的准确度。
     *
     * 拿不到音素级证据（没请求、服务没给、符号对不上）时返回 null，调用方退回整体分——
     * **退回是正常路径，不是错误**：整体分仍然是有意义的证据，只是说不出是哪个音的问题。
     */
    fun targetScore(feedback: PronunciationFeedback, targetIpa: String): Int? {
        val wanted = normalizeIpa(targetIpa)
        if (wanted.isEmpty()) return null
        val scores = feedback.words
            .flatMap { it.phonemes }
            .filter { normalizeIpa(it.symbol) == wanted }
            .map { it.accuracyScore }
        if (scores.isEmpty()) return null
        return scores.sum() / scores.size
    }

    /**
     * 这次主要问题是什么。
     *
     * 只在目标音确实落在低区时才给，而且给的是音位表里为这个音手写的常见误读；
     * 音位表里没写就返回 null，界面只显示分数和逐词证据，不硬凑一句听起来专业的空话。
     */
    fun problem(phoneme: Phoneme?, targetScore: Int?): CommonError? {
        if (phoneme == null) return null
        if (targetScore == null || targetScore >= ProductionScoring.WEAK_SCORE) return null
        return phoneme.commonErrors.firstOrNull()
    }

    /** 这次读的这个词里，目标音之外还有哪些词读得不好。句子跟读时用得上。 */
    fun weakWords(feedback: PronunciationFeedback): List<String> =
        feedback.problemWords.map { it.word }

    /**
     * 长音符、重音标记、次重音、音节点、连接符。
     *
     * 这些在 Azure 的 IPA 输出和手写音位表里出现得都不稳定，参与匹配只会制造假的「对不上」。
     */
    private val IGNORED_MARKS = setOf('ː', ':', 'ˈ', 'ˌ', '.', '‿', 'ˑ', '̩', '̯')
}
