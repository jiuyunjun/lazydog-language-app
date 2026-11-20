package com.lazydog.english.domain.vocabulary

/**
 * 单词卡和词典义项的对齐规则（D-095「老卡回填」）。
 *
 * 只管"能不能对、对上了改什么"，不管怎么存、什么时候跑——那是 `core/data` 的事。
 */
object DictionaryAlignment {

    /** 一张卡在词典里的处境。 */
    sealed interface Plan {
        /** 词典里没这个词，或者有这个词但没有这个词性：卡保持原样，记未对齐。 */
        data object Unmatched : Plan

        /** 只有一个候选，本地直接对上，不问模型。 */
        data class Direct(val sense: DictionarySense) : Plan

        /** 多个候选，要模型结合卡上的释义和例句从里面选一个。 */
        data class NeedsChoice(val options: List<DictionarySense>) : Plan
    }

    /**
     * 按 (原型, 词性) 找候选；卡没标词性或认不出来时，取这个词的全部义项。
     * 卡标了词性而词典里没有这个词性：不跨词性去找——那等于改了这张卡的身份。
     */
    fun plan(pos: String, entry: DictionaryEntry?): Plan {
        if (entry == null) return Plan.Unmatched
        val known = PartOfSpeech.parse(pos)
        val options = if (known != null) entry.sensesOf(known) else entry.senses
        return when (options.size) {
            0 -> Plan.Unmatched
            1 -> Plan.Direct(options.single())
            else -> Plan.NeedsChoice(options)
        }
    }

    /** 对上之后要写回卡片的字段。例句、搭配、记忆提示、拼写材料一概不动。 */
    data class AlignedFields(
        val pos: String,
        val meaningZh: String,
        val senseOrder: Int,
        val ipa: String,
        val forms: List<String>,
    )

    /**
     * - 释义换成词典义项，旧的丢弃（用户 2026-11-20 选定）；
     * - 词性写成词典的规范值（卡原来没标词性时，这是补上而不是改）；
     * - 音标：词典的是 CMUdict 美式就换；词典只有退回来的 ECDICT 原音标时，卡上已有的不覆盖——
     *   那份偏英式、写法也不规整，不比模型写的更可信；
     * - 不规则变形原来为空才用词典的补上。
     */
    fun align(
        currentIpa: String,
        currentForms: List<String>,
        sense: DictionarySense,
        entry: DictionaryEntry,
    ): AlignedFields = AlignedFields(
        pos = sense.pos.wire,
        meaningZh = sense.meaningZh,
        senseOrder = sense.order,
        ipa = when {
            entry.ipa.isBlank() -> currentIpa
            entry.ipaSource == IPA_SOURCE_US -> entry.ipa
            else -> currentIpa.ifBlank { entry.ipa }
        },
        forms = currentForms.ifEmpty { entry.irregularForms },
    )

    const val IPA_SOURCE_US = "cmudict"
}
