package com.lazydog.english.domain.vocabulary

import kotlin.math.roundToInt

/**
 * 词频分档。
 *
 * 对外只给"档"不给排名数字：排名换一份语料就全变，而且第 2870 名和第 3050 名
 * 之间的差别对"这个词现在值不值得学"根本没有意义，报出去只是假精确。
 * 档位边界是习惯上的整数关口，不是从语料里算出来的阈值。
 */
enum class FrequencyBand(val labelZh: String, val maxRank: Int) {
    Top1000("最常用 1000 词", 1_000),
    Top2000("最常用 2000 词", 2_000),
    Top3000("最常用 3000 词", 3_000),
    Top5000("最常用 5000 词", 5_000),
    Top8000("最常用 8000 词", 8_000),
    Top12000("最常用 12000 词", 12_000),

    /** 表里没有。可能是专业词、生僻词，也可能只是这份语料没覆盖到。 */
    Rare("常用词表之外", Int.MAX_VALUE),
    ;

    companion object {
        fun forRank(rank: Int): FrequencyBand = entries.first { rank <= it.maxRank }
    }
}

/**
 * 英语词频表的只读视图（`domain` 不碰文件和 Android，实现见 `core/data`）。
 *
 * 排名从 1 起。查不到返回 null 而不是一个很大的数——"没收录"和"很生僻"不是一回事：
 * photosynthesis 不在表里不代表它比 reluctant 更难，只代表字幕语料里没人这么说话。
 */
interface WordFrequencyIndex {

    /** 收录词数；索引不可用（资源缺失、解析失败）时为 0。 */
    val size: Int

    /** [word] 的词频排名，不区分大小写；没收录返回 null。 */
    fun rankOf(word: String): Int?

    /** 排名落在 [fromRank]..[toRank]（闭区间，1 基）的词，按排名升序。 */
    fun wordsInRange(fromRank: Int, toRank: Int): List<String>
}

/** 查不到的词一律算 [FrequencyBand.Rare]。 */
fun WordFrequencyIndex.bandOf(word: String): FrequencyBand =
    rankOf(word)?.let(FrequencyBand::forRank) ?: FrequencyBand.Rare

/** 索引不可用时的兜底：所有查询落空，调用方自然退回"不按词频挑词"的老行为。 */
object EmptyWordFrequencyIndex : WordFrequencyIndex {
    override val size: Int = 0
    override fun rankOf(word: String): Int? = null
    override fun wordsInRange(fromRank: Int, toRank: Int): List<String> = emptyList()
}

/**
 * 按词频给"下一批该学什么"挑候选词。
 *
 * 这是"高频词优先"真正落地的地方。只把要求写进提示词是不够的——模型手里没有词频表，
 * 让它"挑常用词"它只会挑*它觉得*常用的词，那又回到了没有数据的状态。所以候选词在本地
 * 按真实词频算好再交给模型，模型负责的是"这批里哪些适合他、怎么写例句"。
 */
object VocabularyCandidates {

    /**
     * CEFR 连续能力值（0.0 Pre-A1 ～ 5.0 C1，见 `domain/assessment`）到取词排名窗口的锚点。
     *
     * 窗口不是"这个等级掌握了多少词"，而是"现在给他上新词，从哪一段挑最划算"：
     * 下界避开他早该会的，上界避开还轮不到的。相邻等级之间刻意留大量重叠——
     * 等级估计本身就有误差，卡太死会让刚跨过一档的人突然看不到一批本该学的词。
     */
    private val windows = listOf(
        0.0 to (1 to 1_200),
        1.0 to (1 to 2_000),
        2.0 to (800 to 3_500),
        3.0 to (2_000 to 6_000),
        4.0 to (3_500 to 9_000),
        5.0 to (5_000 to 12_000),
    )

    /** 等级之间线性插值，避免 B1 到 B1+ 这半档造成窗口跳变。 */
    fun rankWindow(cefrScore: Double): IntRange {
        val score = cefrScore.coerceIn(0.0, 5.0)
        val upperIndex = windows.indexOfFirst { it.first >= score }.coerceAtLeast(1)
        val (lowScore, lowWindow) = windows[upperIndex - 1]
        val (highScore, highWindow) = windows[upperIndex]
        val span = highScore - lowScore
        val t = if (span <= 0.0) 0.0 else (score - lowScore) / span
        val from = lowWindow.first + (highWindow.first - lowWindow.first) * t
        val to = lowWindow.second + (highWindow.second - lowWindow.second) * t
        return from.roundToInt()..to.roundToInt()
    }

    /**
     * 挑 [count] 个候选词：窗口内、按词频从高到低、跳过已经学过的。
     *
     * 刻意是确定性的：不打乱、不随机。同一个人连着两天打开，第二天看到的就是
     * 第一天没学掉的那批里最常用的几个——这正是"高频优先"该有的样子。词学掉一个
     * 就从队头掉一个，队列自己往前走，不需要额外的进度状态。
     *
     * [knownTerms] 要传**全部**已学词，不是给提示词的那 200 个截断——本地过滤不花钱，
     * 漏过滤的代价却是把他早就会的词又推一遍。
     */
    fun select(
        index: WordFrequencyIndex,
        cefrScore: Double,
        knownTerms: Collection<String>,
        count: Int,
    ): List<String> {
        if (count <= 0 || index.size == 0) return emptyList()
        val known = knownTerms.mapTo(HashSet()) { it.trim().lowercase() }
        val window = rankWindow(cefrScore)
        return index.wordsInRange(window.first, window.last)
            .asSequence()
            .filter { it !in known }
            .take(count)
            .toList()
    }
}
