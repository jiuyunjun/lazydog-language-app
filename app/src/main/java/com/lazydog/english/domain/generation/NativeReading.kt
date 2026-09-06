package com.lazydog.english.domain.generation

import kotlinx.serialization.Serializable

/**
 * 母语阅读（`母语阅读DESIGN.md`）的领域模型。
 *
 * 这个功能和 [GeneratedReading] 的关系是**同一条阅读线上的两种材料**，不是两套系统：
 * 渐进式阅读写的是英文短文，母语阅读写的是中文母版加上局部英语替换。因此它复用
 * `reading_materials` 这张表、复用 `ReadingRepository`，只多存一份 [NativeReadingDocument]。
 *
 * 拆成"先写中文母版、再规划替换"两步（设计文档 §7、§23）不是为了好看：
 * 一次提示词里既要写出值得读的文章、又要凑够英语比例，模型一定会牺牲前者。
 * 分开之后中文母版的质量可以单独评价，换档位时也只重跑替换，不重写文章（§38）。
 */

/** 英语表层比例三档（设计文档 §3.1、§31）。存 [wire]，界面显示 [labelZh]。 */
enum class EnglishAmount(val wire: String, val labelZh: String, val surfaceRatio: Double) {
    Light("light", "轻松", 0.12),
    Balanced("balanced", "平衡", 0.24),
    Strong("strong", "强化", 0.38),
    ;

    /** 显示用的百分比整数，如 24。 */
    val percent: Int get() = Math.round(surfaceRatio * 100).toInt()

    companion object {
        val DEFAULT = Balanced
        fun fromWire(wire: String?): EnglishAmount =
            entries.firstOrNull { it.wire == wire } ?: DEFAULT
    }
}

/**
 * 生词量（设计文档 §31）。**和英语量是两个独立旋钮**（§43 原则 4）：
 * 英语量说的是"多少内容显示成英语"，生词量说的是"这些英语里有多少是没学过的"。
 * 合成一个旋钮的话，想多看英语的人会被迫同时多啃生词。
 */
enum class NewWordAmount(val wire: String, val labelZh: String, val shareWithinEnglish: Double) {
    Few("few", "少", 0.13),
    Normal("normal", "适中", 0.22),
    Many("many", "多", 0.30),
    ;

    companion object {
        val DEFAULT = Normal
        fun fromWire(wire: String?): NewWordAmount =
            entries.firstOrNull { it.wire == wire } ?: DEFAULT
    }
}

/** 一个 span 属于哪类知识（设计文档 §11）。点击后进哪个页面由它决定。 */
object SpanKind {
    const val Vocabulary = "vocabulary"
    const val Phrase = "phrase"
    const val Grammar = "grammar"
    const val Sentence = "sentence"
    const val TechnicalTerm = "technical_term"

    val all = listOf(Vocabulary, Phrase, Grammar, Sentence, TechnicalTerm)

    fun normalize(raw: String): String {
        val clean = raw.trim().lowercase().replace(' ', '_')
        return if (clean in all) clean else Vocabulary
    }

    fun labelZh(kind: String): String = when (normalize(kind)) {
        Phrase -> "短语"
        Grammar -> "语法"
        Sentence -> "整句"
        TechnicalTerm -> "术语"
        else -> "单词"
    }
}

/**
 * 这个 span 对**这个人**来说是什么（设计文档 §11）。
 * 它决定正文里的视觉：mastered 不加任何标记，target 才给轻提示（§20.1）。
 */
object MasteryClass {
    /** 已经会了，出现在这里是复习机会，不是学习目标。 */
    const val Mastered = "mastered"

    /** 到期该复习的。 */
    const val Review = "review"

    /** 这一篇要教的新东西。 */
    const val Target = "target"

    /** 技术词、专名这类：算英语表层比例，不算学习目标。 */
    const val Incidental = "incidental"

    val all = listOf(Mastered, Review, Target, Incidental)

    fun normalize(raw: String): String {
        val clean = raw.trim().lowercase()
        return if (clean in all) clean else Target
    }

    fun labelZh(value: String): String = when (normalize(value)) {
        Mastered -> "已掌握"
        Review -> "该复习了"
        Incidental -> "术语"
        else -> "新表达"
    }
}

/** 中文母版的一段。[id] 由生成侧给（p1、p2……），替换方案按它定位。 */
@Serializable
data class CanonicalParagraph(
    val id: String,
    val textZh: String,
)

/** 读完之后的那一道理解题——问文章本身，不问单词（设计文档 §21.1）。 */
@Serializable
data class NativeComprehensionQuestion(
    val promptZh: String,
    val options: List<String>,
    val answerIndex: Int,
    val explanationZh: String,
)

/** 纯中文母版（设计文档 §7）。它必须自己就站得住，英语替换是后一步的事。 */
data class NativeCanonicalArticle(
    val title: String,
    val teaser: String,
    val category: String,
    val readerPayoff: String,
    val paragraphs: List<CanonicalParagraph>,
    val comprehension: NativeComprehensionQuestion?,
) {
    /** 拼回一整篇中文正文。存进 `reading_materials.body`，摇一摇提问和列表预览共用。 */
    val bodyZh: String get() = paragraphs.joinToString("\n\n") { it.textZh }
}

/**
 * 一个可以自然切换语言的语义片段（设计文档 §4、§11）。
 *
 * [sourceZh] 必须是所在段落里**逐字存在**的一段中文——本地要靠它定位，
 * 定位不到的整条丢掉（`NativeReadingValidation`）。这是"不要替换单词、要替换语义片段"
 * 这条原则在数据结构上的落点：替换的单位是这段中文，不是词典里的某个词。
 */
@Serializable
data class LearningSpan(
    val id: String,
    val paragraphId: String,
    val sourceZh: String,
    val renderedEn: String,
    /** 见 [SpanKind]。 */
    val kind: String,
    /** 见 [MasteryClass]。 */
    val masteryClass: String,
    /** 这里是什么意思。点开面板第一眼看的就是它。 */
    val meaningZh: String,
    /** 音标，可空。整句 span 不给。 */
    val pronunciation: String = "",
    /** 一句话说明"这里为什么这么说"，可空。 */
    val noteZh: String = "",
    /** 语法 span 的结构公式，如 `as long as + 从句`。只有 grammar 给。 */
    val patternEn: String = "",
) {
    val isTarget: Boolean get() = MasteryClass.normalize(masteryClass) == MasteryClass.Target
    val isGrammar: Boolean get() = SpanKind.normalize(kind) == SpanKind.Grammar
}

/** 渲染后的一小段：要么是中文原文，要么是一个可点的英语 span。 */
sealed interface ReadingSegment {
    data class Zh(val text: String) : ReadingSegment
    data class Learning(val text: String, val spanId: String) : ReadingSegment
}

/** 渲染后的一段。UI 按 segment 铺，不在 Markdown 里做 range（设计文档 §28）。 */
data class RenderedParagraph(
    val id: String,
    val segments: List<ReadingSegment>,
)

/**
 * 一篇母语阅读的完整可存内容。整条存 JSON 的理由和 `reading_materials` 其余几列一样：
 * 这些字段只随"重新规划替换"整条生成、整条替换，拆成十几列没有任何好处。
 */
@Serializable
data class NativeReadingDocument(
    val schemaVersion: Int = SCHEMA_VERSION,
    val englishMode: String = EnglishAmount.DEFAULT.wire,
    val newWordMode: String = NewWordAmount.DEFAULT.wire,
    val paragraphs: List<CanonicalParagraph> = emptyList(),
    val spans: List<LearningSpan> = emptyList(),
    val comprehension: NativeComprehensionQuestion? = null,
    /** 实际达成的英语表层比例，0..1。用户看到的"英语约 24%"是它，不是设置里那个目标值。 */
    val englishSurfaceActual: Double = 0.0,
    /** 校验过程中丢掉了什么，排错用。 */
    val notes: List<String> = emptyList(),
) {
    companion object {
        const val SCHEMA_VERSION = 1
    }
}

/** 写中文母版这一步的入参（设计文档 §23 第 7 步）。 */
data class NativeCanonicalRequest(
    val topic: String,
    val learnerLevel: String,
    /**
     * 检索来的事实。**热点必须先有可信 Fact Pack 再写文章**（§43 原则 9）：
     * 让模型凭记忆写新闻是设计文档明令禁止的（§36.7）。空表示这次不涉及时效内容。
     */
    val factPack: List<String> = emptyList(),
    /** 最近读过的标题，避免雷同。 */
    val recentTitles: List<String> = emptyList(),
)

/** 规划英语替换这一步的入参（设计文档 §9.1）。换档位时只重跑这一步。 */
data class NativeSpanPlanRequest(
    val learnerLevel: String,
    val paragraphs: List<CanonicalParagraph>,
    val englishAmount: EnglishAmount,
    val newWordAmount: NewWordAmount,
    val allowGrammar: Boolean,
    /** 到期该复习的词，能自然用上就用，用不上不强塞（§15）。 */
    val reviewVocabulary: List<String> = emptyList(),
    /** 已掌握词汇样本：这些可以直接显示成英语而不算学习负担。 */
    val knownVocabulary: List<String> = emptyList(),
)
