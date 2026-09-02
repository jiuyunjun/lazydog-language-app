package com.lazydog.english.domain.vocabulary

/**
 * 词性，封闭集合（单词记忆DESIGN.md §2.2，Universal POS 风格）。
 *
 * 原来是自由文本：AI 给 `v.`，讲解那条路径给空字符串，情景表达给 `expression`。
 * 拿这样一个字段做词条身份的一半，`record/VERB` 和 `record/NOUN` 分不开，
 * 而 `v.` / `vi.` / `verb` 又会把同一个词分成三条——所以先把取值收干净，
 * 词性才能进身份键（§3「Lexeme = lemma + language + POS」）。
 *
 * [wire] 是存库和过 AI 的稳定值，[labelZh] 才是给人看的。语言这一维暂时不存：
 * 这个 app 只教英语，加一列恒为 "en" 的字段没有意义。
 */
enum class PartOfSpeech(val wire: String, val labelZh: String) {
    Noun("NOUN", "名词"),
    Verb("VERB", "动词"),
    Adjective("ADJ", "形容词"),
    Adverb("ADV", "副词"),
    Pronoun("PRON", "代词"),
    Determiner("DET", "限定词"),
    Adposition("ADP", "介词"),
    Numeral("NUM", "数词"),
    Conjunction("CONJ", "连词"),
    Particle("PART", "小品词"),
    Interjection("INTJ", "感叹词"),
    Auxiliary("AUX", "助动词"),
    ProperNoun("PROPN", "专有名词"),

    /**
     * 整句表达和固定短语。Universal POS 里没有这一档，是本地扩展：
     * 情景演练存下来的"回头我发你"这类条目走的是同一张表，但它们不是词，
     * 也不进字母级拼写训练。旧数据里写的是 `expression`，同样解析到这里。
     */
    Phrase("PHRASE", "表达"),
    ;

    companion object {
        /** AI 契约里列给模型的取值清单，词以外的那一档不给——表达不由生成新词产出。 */
        val wireList: String get() = entries.filter { it != Phrase }.joinToString(" / ") { it.wire }

        /**
         * 尽量把一个词性字符串认出来。
         *
         * 认不出来返回 null，调用方按"没标注"处理——不猜一个默认词性塞进去，
         * 那会让身份键指向错误的词条。
         */
        fun parse(value: String): PartOfSpeech? {
            val clean = value.trim().lowercase().trimEnd('.')
            if (clean.isEmpty()) return null
            entries.firstOrNull { it.wire.lowercase() == clean }?.let { return it }
            return when (clean) {
                "n", "noun", "名词" -> Noun
                "v", "vi", "vt", "verb", "动词" -> Verb
                "adj", "a", "adjective", "形容词" -> Adjective
                "adv", "ad", "adverb", "副词" -> Adverb
                "pron", "pronoun", "代词" -> Pronoun
                "det", "art", "article", "冠词", "限定词" -> Determiner
                "prep", "preposition", "介词" -> Adposition
                "num", "number", "numeral", "数词" -> Numeral
                "conj", "conjunction", "连词" -> Conjunction
                "part", "particle" -> Particle
                "int", "intj", "interjection", "感叹词" -> Interjection
                "aux", "auxiliary", "助动词" -> Auxiliary
                "propn", "proper noun", "专有名词" -> ProperNoun
                "expression", "phrase", "idiom", "表达", "短语" -> Phrase
                else -> null
            }
        }
    }
}

/** 存库用的规范值；认不出来的原样留着，界面照原样显示，但它进不了身份键。 */
fun normalizePos(value: String): String = PartOfSpeech.parse(value)?.wire ?: value.trim()

/** 界面上显示的词性。认不出来就显示原文，不编一个。 */
fun posLabelZh(value: String): String = PartOfSpeech.parse(value)?.labelZh ?: value.trim()
