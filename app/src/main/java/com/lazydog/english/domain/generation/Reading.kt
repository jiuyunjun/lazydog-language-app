package com.lazydog.english.domain.generation

import kotlinx.serialization.Serializable

/** 阅读生成请求（AI_CONTRACTS.md §3 的首版落地）。 */
data class ReadingGenerationRequest(
    val learnerLevel: String,
    val topic: String,
    /** 目标词数（英文单词数）。 */
    val targetLength: Int,
    /** 必须出现在正文里的到期复习词。 */
    val reviewVocabulary: List<String>,
    /** 已掌握词汇样本，用于控制难度，不要求全部出现。 */
    val knownVocabulary: List<String>,
    /** 希望在文中体现的复习语法点，可空。 */
    val reviewGrammar: List<String>,
    /** 允许引入的新词上限。 */
    val maxNewWords: Int,
)

@Serializable
data class ReadingTargetWord(
    val term: String,
    val meaningZh: String,
    val exampleFromText: String,
    /** review：来自复习清单；new：AI 引入的新词。 */
    val role: String,
)

@Serializable
data class ReadingTargetGrammar(
    val name: String,
    val exampleFromText: String,
    val explanationZh: String,
)

@Serializable
data class ReadingQuestion(
    val promptZh: String,
    val options: List<String>,
    val answerIndex: Int,
    val explanationZh: String,
)

data class GeneratedReading(
    val title: String,
    val body: String,
    val estimatedCefr: String,
    val targetVocabulary: List<ReadingTargetWord>,
    val targetGrammar: List<ReadingTargetGrammar>,
    val comprehensionQuestions: List<ReadingQuestion>,
)

/** 整句的翻译与讲解（点句操作用）。 */
data class SentenceExplanation(
    val translationZh: String,
    /** 句子结构 / 语法点的简短说明，可空。 */
    val explanationZh: String,
)

/** 单词在语境里的解释（点词查询用）。 */
data class WordExplanation(
    val term: String,
    val ipa: String,
    val meaningZh: String,
    /** 对这句话里用法的一句话说明，可空。 */
    val usageNoteZh: String,
    /** 换一个场景的例句，可空。 */
    val exampleEn: String = "",
    val exampleZh: String = "",
    /** 记忆方法：词根词缀拆解或联想记忆，可空。 */
    val memoryHintZh: String = "",
)
