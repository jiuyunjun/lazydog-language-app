package com.lazydog.english.domain.generation

import com.lazydog.english.domain.vocabulary.DictionarySense

/**
 * 「从词典义项里选一个」的两种请求（D-095）。
 *
 * 共同点是**模型只回序号**：释义、词性、音标都是词典的，模型不写一个字。
 * 这样它答错了也只是选错一项，本地一眼能校验（序号在不在范围里），
 * 不会出现一个词典里没有、谁也没审过的新释义。
 */

/** 一个词和它在词典里的候选义项。[options] 的下标就是模型要回的序号。 */
data class SenseChoiceItem(
    /** 调用方自己的键（老卡回填用 itemId），原样带回，用来对回结果。 */
    val key: String,
    val term: String,
    val options: List<DictionarySense>,
    /** 老卡上原来的释义：判断它说的是哪一项的主要依据。 */
    val meaningHintZh: String = "",
    /** 老卡的例句或用户遇到它的原句。 */
    val exampleEn: String = "",
)

/** 老卡回填：每个词选一项，或者说"都不对"。 */
data class SenseChoiceRequest(val items: List<SenseChoiceItem>)

/** 一次请求里最多放这么多张卡。再多一次失败要重来的就太多了，提示词也会长到模型开始漏项。 */
const val MAX_SENSE_CHOICES_PER_REQUEST = 12

/**
 * 模型的原始回答 → 每个键的选择。下标越界、键不认识的一律丢掉；
 * 同一个键回了两次的取第一次。null 表示模型明确说都不对。
 * 没回答的键不在结果里——调用方把它们当"这次没定下来"，不当"都不对"。
 */
fun validateSenseChoices(
    request: SenseChoiceRequest,
    raw: List<Pair<String, Int?>>,
): Map<String, Int?> {
    val sizes = request.items.associate { it.key to it.options.size }
    val out = LinkedHashMap<String, Int?>()
    for ((key, index) in raw) {
        val size = sizes[key] ?: continue
        if (key in out) continue
        if (index == null || index in 0 until size) out[key] = index
    }
    return out
}

/** 每日新词：从候选里按学习者情况挑 [count] 个词，每个词选定要教的那一项。 */
data class NewWordPickRequest(
    val count: Int,
    val learnerLevel: String,
    val topics: List<String>,
    /** 从高频到低频；[SenseChoiceItem.key] 用词本身。 */
    val candidates: List<SenseChoiceItem>,
)

data class NewWordPick(val term: String, val sense: DictionarySense)

/**
 * 只认候选里有的词、范围内的序号；同一个词只要一次；最多 [NewWordPickRequest.count] 个。
 * 一个都不剩由调用方当失败处理。
 */
fun validateNewWordPicks(
    request: NewWordPickRequest,
    raw: List<Pair<String, Int>>,
): List<NewWordPick> {
    val byTerm = request.candidates.associateBy { it.term.lowercase() }
    val seen = HashSet<String>()
    return raw.mapNotNull { (term, index) ->
        val candidate = byTerm[term.trim().lowercase()] ?: return@mapNotNull null
        // 先校验序号再占位：一条越界的回答不该把同一个词后面那条合法回答也挡掉。
        val sense = candidate.options.getOrNull(index) ?: return@mapNotNull null
        if (!seen.add(candidate.term.lowercase())) return@mapNotNull null
        NewWordPick(candidate.term, sense)
    }.take(request.count)
}
