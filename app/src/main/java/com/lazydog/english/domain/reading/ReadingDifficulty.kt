package com.lazydog.english.domain.reading

/**
 * 一篇英文材料对这个人难不难（`读书与网络阅读DESIGN.md` §2.2）。纯本地算，不问模型。
 *
 * 口径是**生词覆盖率**：正文的实词词次里，有多少既不在他的词库里、词频也超出他舒适区。
 * 阅读研究的常用经验是认识 98% 左右的词次读起来轻松，95% 是能读懂的下限；
 * 这里按这个量级分三档，阈值是起点，真机读过几篇再调。
 */
object ReadingDifficulty {

    enum class Level(val labelZh: String) {
        Easy("轻松"),
        Fit("合适"),
        Hard("偏难"),
    }

    data class Estimate(
        val level: Level,
        /** 生词词次占实词词次的比例，0..1。 */
        val unknownRatio: Double,
        /** 参与统计的词次数。太少（标题、短讯）时估计不可靠，界面可以不显示。 */
        val countedTokens: Int,
        /** 出现最多的几个生词，给列表或阅读页一句"这篇会遇到 xxx"。 */
        val sampleUnknown: List<String>,
    )

    /** 生词词次比例不超过这个算轻松。 */
    const val EASY_MAX = 0.02

    /** 不超过这个算合适；再高就是偏难。 */
    const val FIT_MAX = 0.05

    /** 少于这么多词次就不算了：几十个词里错一个就跳一档，没有意义。 */
    const val MIN_TOKENS = 60

    private val WORD = Regex("[A-Za-z]+(?:'[A-Za-z]+)?")

    /**
     * @param lemmaOf 词形 → 原型（went → go）；认不出返回 null，按原样算。
     * @param rankOf 原型 → 词频排名；没有排名返回 null。
     * @param knownTerms 他词库里的词（原型，小写）。
     * @param comfortableRank 排名在这以内的词默认他认识。
     */
    fun estimate(
        text: String,
        lemmaOf: (String) -> String?,
        rankOf: (String) -> Int?,
        knownTerms: Set<String>,
        comfortableRank: Int,
    ): Estimate? {
        val tokens = tokens(text)
        if (tokens.size < MIN_TOKENS) return null
        // 同一个词形只查一次：一篇里 the、of 会出现几十次。
        val unknownByForm = HashMap<String, Boolean>()
        val unknownCounts = LinkedHashMap<String, Int>()
        var unknown = 0
        for (form in tokens) {
            val isUnknown = unknownByForm.getOrPut(form) {
                val lemma = lemmaOf(form)?.lowercase() ?: form
                val known = lemma in knownTerms || form in knownTerms ||
                    (rankOf(lemma) ?: Int.MAX_VALUE) <= comfortableRank
                !known
            }
            if (isUnknown) {
                unknown += 1
                val lemma = lemmaOf(form)?.lowercase() ?: form
                unknownCounts[lemma] = (unknownCounts[lemma] ?: 0) + 1
            }
        }
        val ratio = unknown.toDouble() / tokens.size
        val level = when {
            ratio <= EASY_MAX -> Level.Easy
            ratio <= FIT_MAX -> Level.Fit
            else -> Level.Hard
        }
        val sample = unknownCounts.entries.sortedByDescending { it.value }.take(5).map { it.key }
        return Estimate(level, ratio, tokens.size, sample)
    }

    /**
     * 参与统计的词：小写化；句中大写开头的当专有名词跳过（人名地名不算生词，
     * 他也不需要"认识" Crane 才读得懂）。句首的大写词照常算。
     */
    internal fun tokens(text: String): List<String> {
        val out = ArrayList<String>()
        var sentenceStart = true
        var cursor = 0
        for (match in WORD.findAll(text)) {
            // 两个词之间出现过句末标点，下一个词就是句首。
            val gap = text.substring(cursor, match.range.first)
            if (gap.any { it == '.' || it == '!' || it == '?' || it == '\n' || it == '"' || it == '“' }) sentenceStart = true
            cursor = match.range.last + 1
            val word = match.value
            val capitalized = word.first().isUpperCase()
            val isProperNoun = capitalized && !sentenceStart && word != "I"
            sentenceStart = false
            if (isProperNoun || word.length < 2 && word.lowercase() != "a") continue
            out += word.lowercase().substringBefore('\'')
        }
        return out
    }
}
