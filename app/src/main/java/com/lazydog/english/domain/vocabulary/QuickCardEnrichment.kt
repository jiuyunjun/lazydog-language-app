package com.lazydog.english.domain.vocabulary

import com.lazydog.english.domain.generation.Collocation
import com.lazydog.english.domain.generation.GeneratedWord
import com.lazydog.english.domain.generation.validateWordCard

/**
 * 查词面板存下的"速记卡"：只有释义、音标、原句和一句记忆提示。
 *
 * 面板要快，讲解只给最必要的几项；但生词本里的词要拿去复习、拼写训练、语境默写，
 * 少了搭配、词块拆分、易错段和常见拼错，后面那几样练习就没有材料。
 */
data class QuickCard(
    val term: String,
    val pos: String,
    val meaningZh: String,
    val ipa: String,
    /** 有原句时是用户遇到这个词的那句话。 */
    val exampleEn: String,
    val exampleZh: String,
    val memoryHintZh: String,
    val forms: List<String>,
    val collocations: List<Collocation>,
)

/** 补全后要写回去的全部字段。词形、词性、释义不在里面——那三样是这张卡的身份，补全不许动。 */
data class EnrichedWord(
    val ipa: String,
    val exampleEn: String,
    val exampleZh: String,
    val memoryHintZh: String,
    val forms: List<String>,
    val collocations: List<Collocation>,
    val chunks: List<String>,
    val trickyPart: String,
    val misspellings: List<String>,
)

sealed interface EnrichmentResult {
    data class Merged(val word: EnrichedWord) : EnrichmentResult
    data class Rejected(val reason: String) : EnrichmentResult
}

object QuickCardEnrichment {
    /**
     * 把完整学习卡并进速记卡。
     *
     * 原则是**只补缺的，不改用户看过的**：释义是用户在面板上看过、点了"放进生词本"的那个，
     * 原句是他真正读到的语境（语境默写挖空靠它和 seenAs），这两样都留着。
     * 模型换了词、换了词性，说明它补的是另一个意思，整张作废，不拿来拼。
     */
    fun merge(quick: QuickCard, full: GeneratedWord): EnrichmentResult {
        validateWordCard(full)?.let { return EnrichmentResult.Rejected(it) }
        if (!full.term.trim().equals(quick.term.trim(), ignoreCase = true)) {
            return EnrichmentResult.Rejected("模型换了词：${full.term}")
        }
        val quickPos = PartOfSpeech.parse(quick.pos)
        if (quickPos != null && PartOfSpeech.parse(full.pos) != quickPos) {
            return EnrichmentResult.Rejected("词性对不上：${full.pos}")
        }
        val keepExample = quick.exampleEn.isNotBlank()
        val term = quick.term.trim()
        return EnrichmentResult.Merged(
            EnrichedWord(
                ipa = quick.ipa.ifBlank { full.ipa },
                exampleEn = if (keepExample) quick.exampleEn else full.exampleEn,
                exampleZh = if (keepExample) quick.exampleZh else full.exampleZh,
                memoryHintZh = quick.memoryHintZh.ifBlank { full.memoryHintZh },
                forms = (quick.forms + full.forms)
                    .map { it.trim() }
                    .filter { it.isNotBlank() && !it.equals(term, ignoreCase = true) }
                    .distinctBy { it.lowercase() },
                collocations = quick.collocations.ifEmpty { full.collocations },
                // 词块拼起来必须正好是这个词，易错段必须是它的子串；对不上的宁可不要，
                // 拆错的词块拿去出拼写题等于教错东西。
                chunks = full.chunks.takeIf { it.joinToString("").equals(term, ignoreCase = true) }.orEmpty(),
                trickyPart = full.trickyPart.takeIf { it.isNotBlank() && term.contains(it, ignoreCase = true) }.orEmpty(),
                misspellings = full.misspellings
                    .map { it.trim() }
                    .filter { it.isNotBlank() && !it.equals(term, ignoreCase = true) }
                    .distinctBy { it.lowercase() },
            ),
        )
    }
}
