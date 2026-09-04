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
    /** 这篇用哪种写法（`引人入胜的阅读材料DESIGN.md` §6）。 */
    val archetype: ReadingArchetype = ReadingArchetype.HiddenSystem,
    /** 最近几篇的标题，进提示词避免重复；越靠前越新（§20）。 */
    val recentTitles: List<String> = emptyList(),
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

/**
 * 理解题的口径。只考大意的题靠词汇量猜就能做对，练不到解析句子的能力，
 * 所以每篇必须至少有一道 form 或 reference 题，逼着回原文看形式。
 */
object ReadingQuestionKind {
    /** 大意、细节：读懂就能答。 */
    const val Gist = "gist"
    /** 形式：为什么用这个时态 / 这个结构，答案要落在原文某处形式上。 */
    const val Form = "form"
    /** 指代：这个 it / they / that 指的是什么。 */
    const val Reference = "reference"

    val all = listOf(Gist, Form, Reference)

    /** 必须至少出现一道的口径。 */
    val anchored = listOf(Form, Reference)

    fun normalize(raw: String): String {
        val clean = raw.trim().lowercase()
        return if (clean in all) clean else Gist
    }

    fun labelZh(kind: String): String = when (normalize(kind)) {
        Form -> "为什么这么写"
        Reference -> "指代"
        else -> "读懂了吗"
    }
}

@Serializable
data class ReadingQuestion(
    val promptZh: String,
    val options: List<String>,
    val answerIndex: Int,
    val explanationZh: String,
    /** 见 [ReadingQuestionKind]；旧材料没有这个字段，读出来按 gist 处理。 */
    val kind: String = ReadingQuestionKind.Gist,
    /** form / reference 题必须给出原文里的依据句，本地校验它确实是正文子串。 */
    val evidenceFromText: String = "",
)

data class GeneratedReading(
    val title: String,
    val body: String,
    /**
     * 这篇文章要留给读者的**那一个**收获，一句话（§4）。
     *
     * 一篇只承诺一件事：信息多不等于信息价值高。读完页面上显示的
     * 「值得记住的一件事」就是它，所以它不能是标题的复述，也不能是"原因有很多"这种废话。
     */
    val readerPayoff: String,
    val estimatedCefr: String,
    val targetVocabulary: List<ReadingTargetWord>,
    val targetGrammar: List<ReadingTargetGrammar>,
    val comprehensionQuestions: List<ReadingQuestion>,
    /** Feed 卡片上的一句引子；空时由本地从正文首句兜底。 */
    val teaser: String = "",
    /** 给人看的宽泛类别，如 Technology / Psychology；不是推荐系统的隐藏标签。 */
    val category: String = "",
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
    /**
     * 这个词的原型（词典形式）。用户点的是句子里的形态（`went`），要复习的是词条（`go`），
     * 这是两件事——[term] 保留他点到的那个形态，入库时用的是这里。
     *
     * 只做词形还原，不做短语归并（点 `gave` 不该变成 `give up`），也不做拼写纠正。
     * 还原本身必须结合句子判断（`saw` 可能是 see 的过去式，也可能是"锯子"），
     * 所以由 AI 给，本地不猜；判不出来就留空。
     */
    val lemma: String = "",
    val ipa: String,
    val meaningZh: String,
    /** 对这句话里用法的一句话说明，可空。 */
    val usageNoteZh: String,
    /** 换一个场景的例句，可空。 */
    val exampleEn: String = "",
    val exampleZh: String = "",
    /** 词性，封闭集合里的值（`PartOfSpeech.wire`）。判不出来时为空。 */
    val pos: String = "",
    /** 不规则变形（go → went/gone）。规则变形不给。 */
    val forms: List<String> = emptyList(),
    /** 记忆方法：词根词缀拆解或联想记忆，可空。 */
    val memoryHintZh: String = "",
) {
    /** 真正该进生词本的那个词形。AI 没给原型时就是用户点到的形态。 */
    val headword: String get() = lemma.trim().ifBlank { term.trim() }

    /** 用户点的形态和词条不是同一个词形，界面上要把这层关系说出来。 */
    val inflected: Boolean get() = !headword.equals(term.trim(), ignoreCase = true)
}
