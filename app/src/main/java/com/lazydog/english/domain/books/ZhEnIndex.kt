package com.lazydog.english.domain.books

import com.lazydog.english.domain.vocabulary.PartOfSpeech
import kotlin.math.ln

/** 一个中文词定下来的英文：原型、词性、在词典里是第几个义项、英文词频排名。 */
data class ZhMapping(
    val zh: String,
    val lemma: String,
    val pos: PartOfSpeech,
    val senseOrder: Int,
    val rank: Int,
    /** 第一名和第二名（不同英文词）分差够大才算确定；不确定的本地模式不用（§5.3）。 */
    val confident: Boolean,
)

/**
 * 中文词 → 英文词（`读书与网络阅读DESIGN.md` §5.3）。数据是内置词典的干净中文义项，见 `tools/build_zh_seg.py`。
 *
 * 多个候选时：词性对得上的优先，再按 `ln(英文词频排名) + 义项序号` 取最小。只看词频会把「记录」挑成 minute、
 * 「打算」挑成 go；义项序号代表"这个中文意思是不是那个英文词最主要的意思"，加上它就挑对了。
 */
class ZhEnIndex(private val entries: Map<String, List<Candidate>>) {

    data class Candidate(val lemma: String, val pos: PartOfSpeech, val senseOrder: Int, val rank: Int) {
        /** 没有排名的当成很生僻。 */
        val score: Double get() = ln((if (rank > 0) rank else UNRANKED).toDouble()) + senseOrder
    }

    fun candidates(zh: String): List<Candidate> = entries[zh].orEmpty()

    /** [jiebaPos] 是分词词典里这个中文词的词性标签。 */
    fun resolve(zh: String, jiebaPos: String): ZhMapping? {
        val all = entries[zh] ?: return null
        val wanted = posFor(jiebaPos)
        val matching = all.filter { it.pos in wanted }.ifEmpty { all }
        // 每个英文词只留它分最好的那个词性：regret 名词和 regret 动词不算两个竞争者。
        val perLemma = matching.groupBy { it.lemma }.map { (_, list) -> list.minBy { it.score } }.sortedBy { it.score }
        val best = perLemma.first()
        val margin = perLemma.getOrNull(1)?.let { it.score - best.score } ?: Double.MAX_VALUE
        return ZhMapping(zh, best.lemma, best.pos, best.senseOrder, best.rank, confident = margin >= CONFIDENT_MARGIN)
    }

    companion object {
        /** 第一名和第二名分差至少这么多才算确定。按实测样本定的起点，真机读几本书再调。 */
        const val CONFIDENT_MARGIN = 0.15

        private const val UNRANKED = 60_000

        /** jieba 词性 → 能对上的英文词性。动名词两边都行；认不出的不限制。 */
        fun posFor(jiebaPos: String): Set<PartOfSpeech> = when (jiebaPos) {
            "v", "vd", "vg", "vi" -> setOf(PartOfSpeech.Verb)
            "vn" -> setOf(PartOfSpeech.Verb, PartOfSpeech.Noun)
            "n", "ng", "nz" -> setOf(PartOfSpeech.Noun)
            "an" -> setOf(PartOfSpeech.Noun, PartOfSpeech.Adjective)
            "a", "ag", "z", "b" -> setOf(PartOfSpeech.Adjective)
            "ad" -> setOf(PartOfSpeech.Adverb, PartOfSpeech.Adjective)
            "d" -> setOf(PartOfSpeech.Adverb)
            else -> PartOfSpeech.entries.toSet()
        }

        /** 资源文件每行「中文\t原型\t词性\t义项序号\t排名」，`#` 开头是注释。 */
        fun parse(lines: Sequence<String>): ZhEnIndex = ZhEnIndex(
            lines.filter { it.isNotBlank() && !it.startsWith("#") }
                .mapNotNull { line ->
                    val p = line.split('\t')
                    if (p.size < 5) return@mapNotNull null
                    val pos = PartOfSpeech.parse(p[2]) ?: return@mapNotNull null
                    p[0] to Candidate(p[1], pos, p[3].toIntOrNull() ?: 0, p[4].toIntOrNull() ?: 0)
                }
                .groupBy({ it.first }, { it.second }),
        )
    }
}
