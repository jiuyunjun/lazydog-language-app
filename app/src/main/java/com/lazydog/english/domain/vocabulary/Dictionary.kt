package com.lazydog.english.domain.vocabulary

/**
 * 内置离线英汉词典的只读视图（D-095）。`domain` 不碰 SQLite 和 assets，实现见 `core/data/AssetDictionary`。
 *
 * 词典给的是一张单词卡的**身份**：词形、词性、释义、音标、不规则变形。例句、搭配、记忆提示、
 * 拼写材料仍由模型写——那是教学材料，不是词典事实。
 *
 * 义项颗粒度和词典对齐：一个 (原型, 词性) 下的每一项就是一个义项，[DictionarySense.order]
 * 就是卡片的 `senseOrder`。
 */
interface DictionaryLookup {

    /** 当前生效的词典包和版本。词典不可用时版本为 0，回填据此什么都不做。 */
    val pack: DictionaryPack

    /** [lemma] 的词条，不区分大小写（大小写都收录时取完全相同的那个）；查不到返回 null。 */
    fun entry(lemma: String): DictionaryEntry?

    /**
     * [form] 的**候选**原型。
     *
     * 只给候选不做决定：`saw` 既是 see 的过去式，也是名词"锯"。只有一个候选时调用方可以直接用，
     * 有多个时要交给用户或模型（D-095 和 `KnowledgeRepository.addVocabulary` 的口径）。
     * [form] 本身就是收录的原型时也算一个候选。
     */
    fun lemmaCandidates(form: String): List<String>
}

data class DictionaryPack(val id: String, val version: Int)

data class DictionaryEntry(
    val lemma: String,
    /** `/ˈrekərd/` 这种写法；可能为空。 */
    val ipa: String,
    /** `cmudict` 是美式；`ecdict` 是退回的原音标，偏英式。 */
    val ipaSource: String,
    /** 按词典顺序：先按词性首次出现的顺序，再按义项顺序。 */
    val senses: List<DictionarySense>,
    /** 推不出来的变形（went、gone、children），规则变形不在里面。 */
    val irregularForms: List<String>,
) {
    fun sensesOf(pos: PartOfSpeech): List<DictionarySense> = senses.filter { it.pos == pos }

    /**
     * 查词面板上的一行一个词性：「动词 跑；经营；运行」。每个词性最多 [perPos] 项——
     * `run` 的动词有十八项，全摊开就不是"马上看到意思"，是在读词典。
     */
    fun summaryLines(perPos: Int = 6): List<String> =
        senses.groupBy { it.pos }.map { (pos, items) ->
            val shown = items.take(perPos).joinToString("；") { it.meaningZh }
            "${pos.labelZh} $shown${if (items.size > perPos) "…" else ""}"
        }
}

data class DictionarySense(
    val pos: PartOfSpeech,
    /** 同一 (原型, 词性) 下的序号，从 0 起。 */
    val order: Int,
    val meaningZh: String,
) {
    /** 给模型和日志看的「动词 经营」。 */
    val labelZh: String get() = "${pos.labelZh} $meaningZh"
}

/** 词典资源缺失或读坏了：一切查询落空，调用方退回没有词典时的行为。 */
object EmptyDictionary : DictionaryLookup {
    override val pack = DictionaryPack(id = "", version = 0)
    override fun entry(lemma: String): DictionaryEntry? = null
    override fun lemmaCandidates(form: String): List<String> = emptyList()
}
