package com.lazydog.english.domain.books

import com.lazydog.english.domain.vocabulary.PartOfSpeech
import java.util.TreeSet
import kotlinx.serialization.Serializable

/**
 * 渐进式学词（D-100，`读书与网络阅读DESIGN.md` §12）的纯规则：密度、额度、复现间隔、专名保护、一章怎么放。
 * 数据读写在 `NativeBookService`，这里不碰库，可单测。
 */

/** 正文替换密度（§12.3.2 第 4 条）：任意连续 [window] 个原文字符里最多几处、其中新词几处、同一段最多几处。 */
enum class ReplacementDensity(val labelZh: String, val perWindow: Int, val newPerWindow: Int, val perParagraph: Int) {
    Off("关闭", 0, 0, 0),
    Light("轻量", 2, 1, 1),
    Standard("标准", 4, 1, 2),
    ;

    companion object {
        fun fromOrdinal(value: Int): ReplacementDensity = entries.getOrElse(value) { Off }
    }
}

/** 渐进学词的设置，书和网页共用（§12.1、§12.2）。首次需要主动开启，所以默认关闭。 */
@Serializable
data class ProgressiveSettings(
    val density: Int = ReplacementDensity.Off.ordinal,
    val dailyNew: Int = ProgressiveRules.DEFAULT_DAILY_NEW,
    val poolSize: Int = ProgressiveRules.DEFAULT_POOL,
) {
    val replacementDensity: ReplacementDensity get() = ReplacementDensity.fromOrdinal(density)
    val enabled: Boolean get() = replacementDensity != ReplacementDensity.Off
    val dailyNewCapped: Int get() = dailyNew.coerceIn(1, ProgressiveRules.MAX_DAILY_NEW)
    val poolCapped: Int get() = poolSize.coerceIn(1, ProgressiveRules.MAX_POOL)
}

/** 一个义项在阅读里的状态（§12.4）。 */
enum class ReadingWordState(val labelZh: String) {
    /** 活跃学习池里，正文优先复现。 */
    Active("学习中"),
    /** 入池太久或太久没信号，释放出池；进度留着，满 7 天后可再进来。 */
    Paused("已暂停"),
    /** 「我认识这个意思」：自评，不再当新词引入。 */
    KnownSelf("自评认识"),
    /** 两次间隔满 24 小时的成功回想（P2）。 */
    Confirmed("已确认熟悉"),
    /** 转进生词本，复习日期归生词本管。 */
    Vocabulary("已转生词本"),
    ;

    companion object {
        fun fromOrdinal(value: Int): ReadingWordState = entries.getOrElse(value) { Paused }
    }
}

/** 规划用的义项快照，`reading_words` 一行里规划需要的部分。 */
data class ReadingWordSnapshot(
    val senseId: String,
    val state: ReadingWordState,
    /** 已推进到复现计划的第几步（[ProgressiveRules.intervalMillis]）。 */
    val step: Int,
    val nextDueAt: Long,
    val pausedAt: Long,
)

object ProgressiveRules {
    const val DEFAULT_DAILY_NEW = 4
    const val MAX_DAILY_NEW = 8
    const val DEFAULT_POOL = 15
    const val MAX_POOL = 30

    /** 密度窗口，原文字符。 */
    const val WINDOW = 500

    /** 每读这么多新正文字符给一次新词机会，最多积存一次。 */
    const val OPPORTUNITY_CHARS = 1500

    /** 挑词时往后看多远。 */
    const val LOOKAHEAD_CHARS = 6000

    /** 后文至少在这么多个不同段落再出现，才算"引入以后还能再见到"。 */
    const val MIN_LATER_PARAGRAPHS = 2

    /** 剩下的正文短于这个数时（章尾、短网页），不要求后文复现。 */
    const val SHORT_TAIL_CHARS = 2000

    /** 前台停留这么久才算有效展示（§12.3.2 末段的展示代理）。 */
    const val DWELL_MILLIS = 1000L

    /** 规划时按这个速度把"多久以后到期"折成"多少字以后到期"。只用来排位置，不记进度。 */
    const val CHARS_PER_MINUTE = 400

    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    const val DAY = 24 * HOUR

    /** 入池满这么久释放。 */
    const val RELEASE_AFTER_IN_POOL = 21 * DAY

    /** 连续这么久没有出现、查义或回想，释放。 */
    const val RELEASE_AFTER_IDLE = 14 * DAY

    /** 暂停满这么久才可能自动回池。 */
    const val REENTER_AFTER = 7 * DAY

    /** 第 [step] 次有效复现之后，到下一次最早允许复现的间隔：2 分钟、1 小时、1 天，之后最短 1 天。 */
    fun intervalMillis(step: Int): Long = when (step) {
        0 -> 2 * MINUTE
        1 -> HOUR
        else -> DAY
    }

    /** 稳定义项标识：英文原型、词性、词典义项序号。显示词形变了它不变。 */
    fun senseId(lemma: String, pos: PartOfSpeech, senseOrder: Int): String = "${lemma.lowercase()}#${pos.wire}#$senseOrder"

    fun canReenter(word: ReadingWordSnapshot, now: Long): Boolean =
        word.state == ReadingWordState.Paused && now - word.pausedAt >= REENTER_AFTER

    /** 正文字符：不算空白（§12.3.2 末段）。 */
    fun countTextChars(text: CharSequence, from: Int, to: Int): Int {
        var n = 0
        for (i in from.coerceAtLeast(0) until to.coerceAtMost(text.length)) if (!text[i].isWhitespace()) n++
        return n
    }
}

/** 正文里一处可能被换的词：已过保护规则、映射确定的普通名词。 */
data class WordOccurrence(
    val start: Int,
    val end: Int,
    val paragraph: Int,
    val mapping: ZhMapping,
) {
    val senseId: String get() = ProgressiveRules.senseId(mapping.lemma, mapping.pos, mapping.senseOrder)
}

/**
 * 找可替换的词（§12.3.1）。首版只要普通名词、映射确定、能可靠生成词形的位置。
 */
object OccurrenceFinder {

    /** 首版只换普通名词（结巴词性 n）。人名地名机构名（nr/ns/nt/nz）本来就不在里面。 */
    private val NOUN_POS = setOf("n")

    /** 前面挨着这些词，名词多半是复数或泛指多个，原型 torch 不对（「几支火把」），跳过。 */
    private val PLURAL_MARKERS = setOf(
        "们", "些", "几", "两", "俩", "多", "许多", "很多", "不少", "众多", "无数", "诸", "众", "各", "每", "一些", "这些", "那些", "哪些",
        "所有", "一切", "全部", "大量", "成群", "一群", "群", "数", "多少", "好几", "几个", "两个",
    )

    /** 挨在前面的单字方位、区域字：「北大陆」被切成「北 / 大陆」时，「大陆」是专名的一部分。 */
    private val PLACE_PREFIX = setOf("东", "南", "西", "北", "中", "内", "外", "上", "下", "前", "后", "新", "旧", "老", "大", "小")

    /**
     * @param tokens 分词结果（偏移相对 [text]），已去掉标题。
     * @param resolve 中文词 + 结巴词性 → 映射；只要确定的。
     */
    fun find(
        text: String,
        tokens: List<ZhToken>,
        resolve: (String, String) -> ZhMapping?,
    ): List<WordOccurrence> {
        val protected = protectedWords(text, tokens)
        val paragraphStarts = paragraphStarts(text)
        val cache = HashMap<String, ZhMapping?>()
        val result = ArrayList<WordOccurrence>()
        tokens.forEachIndexed { i, token ->
            if (token.pos !in NOUN_POS || token.word.length < 2) return@forEachIndexed
            if (token.word in NativeWordPicker.GENERIC_WORDS || token.word in protected) return@forEachIndexed
            if (looksPlural(text, tokens, i)) return@forEachIndexed
            if (glued(tokens, i)) return@forEachIndexed
            val mapping = cache.getOrPut(token.word) { resolve(token.word, token.pos)?.takeIf { it.confident && it.pos == PartOfSpeech.Noun } }
                ?: return@forEachIndexed
            result += WordOccurrence(token.start, token.end, paragraphOf(paragraphStarts, token.start), mapping)
        }
        return result
    }

    /**
     * 书里像专名一部分的词：出现过两次以上，而且多数时候前面（或后面）紧挨着同一个汉字——
     * 「北大陆」每次都是「北」+「大陆」，「火把」前面的字各不相同。
     */
    internal fun protectedWords(text: String, tokens: List<ZhToken>): Set<String> {
        val before = HashMap<String, HashMap<Char, Int>>()
        val after = HashMap<String, HashMap<Char, Int>>()
        val total = HashMap<String, Int>()
        for (t in tokens) {
            if (t.pos !in NOUN_POS) continue
            total[t.word] = (total[t.word] ?: 0) + 1
            text.getOrNull(t.start - 1)?.takeIf { ChineseSegmenter.isHan(it) }?.let { c ->
                before.getOrPut(t.word) { HashMap() }.merge(c, 1, Int::plus)
            }
            text.getOrNull(t.end)?.takeIf { ChineseSegmenter.isHan(it) }?.let { c ->
                after.getOrPut(t.word) { HashMap() }.merge(c, 1, Int::plus)
            }
        }
        return total.filter { (word, n) ->
            n >= 2 && listOf(before[word], after[word]).any { m -> (m?.values?.maxOrNull() ?: 0) * 10 >= n * 8 }
        }.keys
    }

    private fun looksPlural(text: String, tokens: List<ZhToken>, i: Int): Boolean {
        val token = tokens[i]
        if (text.getOrNull(token.end) == '们') return true
        // 往前看两个词（数词 + 量词，如「几 / 支」）。
        for (k in 1..2) {
            val prev = tokens.getOrNull(i - k) ?: break
            if (token.start - prev.end > 2) break
            if (prev.word in PLURAL_MARKERS) return true
            if (prev.pos == "m" && !prev.word.startsWith("一") && prev.word != "半") return true
        }
        return false
    }

    /** 和前面的单字方位、区域字直接粘在一起：多半是没进词典的专名（「北大陆」「南大陆」）。 */
    private fun glued(tokens: List<ZhToken>, i: Int): Boolean {
        val prev = tokens.getOrNull(i - 1) ?: return false
        return prev.end == tokens[i].start && prev.word.length == 1 && prev.word in PLACE_PREFIX
    }

    private fun paragraphStarts(text: String): IntArray {
        val starts = ArrayList<Int>()
        starts += 0
        text.forEachIndexed { i, c -> if (c == '\n') starts += i + 1 }
        return starts.toIntArray()
    }

    private fun paragraphOf(starts: IntArray, at: Int): Int {
        val idx = starts.binarySearch(at)
        return if (idx >= 0) idx else -idx - 2
    }
}

/** 一处决定显示的替换。 */
data class Placement(
    val occurrence: WordOccurrence,
    /** 这一处是这个义项第一次出现在学习者面前（展示了才算入池、扣额度）。 */
    val introduces: Boolean,
    /** 是从池外（暂停满 7 天）回来的：占机会和池位，但不算当天新义项。 */
    val reentry: Boolean = false,
)

/** 密度闸门（§12.3.2 第 4 条），全按原文位置算，重新分页不重置。 */
class DensityGate(private val density: ReplacementDensity) {
    private val all = TreeSet<Int>()
    private val fresh = TreeSet<Int>()
    private val bySense = HashMap<String, TreeSet<Int>>()
    private val perParagraph = HashMap<Int, Int>()

    fun fits(occ: WordOccurrence, isNew: Boolean): Boolean {
        if (density == ReplacementDensity.Off) return false
        if ((perParagraph[occ.paragraph] ?: 0) >= density.perParagraph) return false
        if (!windowOk(all, occ.start, density.perWindow)) return false
        if (isNew && !windowOk(fresh, occ.start, density.newPerWindow)) return false
        val same = bySense[occ.senseId]
        if (same != null) {
            val lo = same.floor(occ.start)
            val hi = same.ceiling(occ.start)
            if (lo != null && occ.start - lo < ProgressiveRules.WINDOW) return false
            if (hi != null && hi - occ.start < ProgressiveRules.WINDOW) return false
        }
        return true
    }

    fun add(occ: WordOccurrence, isNew: Boolean) {
        all += occ.start
        if (isNew) fresh += occ.start
        bySense.getOrPut(occ.senseId) { TreeSet() } += occ.start
        perParagraph.merge(occ.paragraph, 1, Int::plus)
    }

    /** 加上 [at] 以后，任何一个含它、长 [ProgressiveRules.WINDOW] 的窗口里都不超过 [limit] 处。 */
    private fun windowOk(set: TreeSet<Int>, at: Int, limit: Int): Boolean {
        if (limit <= 0) return false
        val w = ProgressiveRules.WINDOW
        val near = set.subSet(at - w + 1, true, at + w - 1, true).toMutableList()
        near += at
        near.sort()
        var lo = 0
        for (hi in near.indices) {
            while (near[hi] - near[lo] >= w) lo++
            if (near[hi] >= at && near[lo] <= at && hi - lo + 1 > limit) return false
        }
        return true
    }
}

/**
 * 一章（或一篇）里放哪些替换（§12.3.2）。顺序：
 * 1. 之前已经展示过的位置原样放回（已访问段落复用显示决定）；
 * 2. 活跃池里的词：从它最早允许复现的位置起放第一处，之后按复现间隔折成字数往后放；
 * 3. 新词：从阅读位置起，每攒够 1500 字一个机会，在机会之后的那段里挑后文还会分散出现的词。
 * 每一处都要过密度闸门，过不了就留原文。
 */
object ProgressivePlanner {

    data class Input(
        val text: String,
        /** 本章可替换的位置（已过保护规则），按位置排序。 */
        val occurrences: List<WordOccurrence>,
        /** 本章之后已取得的正文里的位置（偏移接在 [text] 后面），只用来估后文复现。 */
        val lookahead: List<WordOccurrence>,
        val words: Map<String, ReadingWordSnapshot>,
        /** 以前在这一章展示过的（原文起点, 义项）。 */
        val shownBefore: Set<Pair<Int, String>>,
        /** 本书停用的中文词、「这里不该换」的位置。 */
        val disabledZh: Set<String>,
        val excludedStarts: Set<Int>,
        /** 新词候选是否合格：不熟悉、没在生词本里学、难度在窗口里。 */
        val eligibleNew: (ZhMapping) -> Boolean,
        val readingStart: Int,
        /** 已攒的新词机会字数，可以是负的（刚用掉一次）。 */
        val credit: Int,
        /** 今天还能引入几个新义项。 */
        val dailyRemaining: Int,
        /** 学习池还空几个位置。 */
        val poolRemaining: Int,
        val density: ReplacementDensity,
        val now: Long,
    )

    fun plan(input: Input): List<Placement> {
        if (input.density == ReplacementDensity.Off || input.occurrences.isEmpty()) return emptyList()
        val gate = DensityGate(input.density)
        val placed = HashMap<Int, Placement>()
        val usable = input.occurrences.filter { it.mapping.zh !in input.disabledZh && it.start !in input.excludedStarts }
        val bySense = usable.groupBy { it.senseId }

        fun place(occ: WordOccurrence, introduces: Boolean, reentry: Boolean = false): Boolean {
            if (occ.start in placed || !gate.fits(occ, introduces)) return false
            gate.add(occ, introduces)
            placed[occ.start] = Placement(occ, introduces, reentry)
            return true
        }

        /** 第一处之后按复现间隔折成字数往后放。 */
        fun followUps(senseId: String, from: Int, startStep: Int) {
            var step = startStep
            var due = from + charsFor(ProgressiveRules.intervalMillis(step))
            for (occ in bySense[senseId].orEmpty()) {
                if (occ.start < due) continue
                if (place(occ, introduces = false)) {
                    step++
                    due = occ.start + charsFor(ProgressiveRules.intervalMillis(step))
                }
            }
        }

        // 1. 已经展示过的位置原样放回。词已经出池（认识、转生词本）的不放，立即恢复原文。
        for (occ in usable) {
            if (occ.start to occ.senseId !in input.shownBefore) continue
            val word = input.words[occ.senseId] ?: continue
            if (word.state == ReadingWordState.Active) place(occ, introduces = false)
        }

        // 2. 活跃池里的词，越早到期越先放。
        val active = input.words.values.filter { it.state == ReadingWordState.Active && it.senseId in bySense }
            .sortedBy { it.nextDueAt }
        for (word in active) {
            val dueAt = input.readingStart + charsFor((word.nextDueAt - input.now).coerceAtLeast(0))
            val first = bySense.getValue(word.senseId).firstOrNull { it.start >= dueAt && place(it, introduces = false) }
                ?: continue
            followUps(word.senseId, first.start, word.step + 1)
        }

        // 3. 新词。
        var slots = minOf(input.poolRemaining, input.dailyRemaining + reentryCount(input))
        if (slots <= 0) return placed.values.sortedBy { it.occurrence.start }
        var daily = input.dailyRemaining
        var slotAt = input.readingStart + (ProgressiveRules.OPPORTUNITY_CHARS - input.credit).coerceIn(0, ProgressiveRules.OPPORTUNITY_CHARS * 2)
        val introduced = HashSet<String>()
        val all = usable + input.lookahead
        while (slots > 0 && slotAt < input.text.length) {
            val windowEnd = slotAt + ProgressiveRules.OPPORTUNITY_CHARS
            val choice = usable.asSequence()
                .filter { it.start in slotAt until windowEnd && it.senseId !in introduced }
                .mapNotNull { occ ->
                    val existing = input.words[occ.senseId]
                    val reentry = existing != null && ProgressiveRules.canReenter(existing, input.now)
                    when {
                        existing != null && !reentry -> null
                        !reentry && daily <= 0 -> null
                        !input.eligibleNew(occ.mapping) -> null
                        else -> Triple(occ, reentry, laterParagraphs(all, occ))
                    }
                }
                .filter { (occ, _, later) ->
                    later >= ProgressiveRules.MIN_LATER_PARAGRAPHS || input.text.length - occ.end < ProgressiveRules.SHORT_TAIL_CHARS
                }
                // 后文分散出现的段落数（封顶 4，集中出现十次不占便宜），再按位置早的优先。
                .sortedWith(compareByDescending<Triple<WordOccurrence, Boolean, Int>> { it.third.coerceAtMost(4) }.thenBy { it.first.start })
                .firstOrNull { (occ, reentry, _) -> place(occ, introduces = true, reentry = reentry) }
            if (choice == null) {
                // 这一段没有合适候选：机会留着，挪到下一段再试（§12.3.2 第 2 条）。
                slotAt = windowEnd
                continue
            }
            val (occ, reentry, _) = choice
            introduced += occ.senseId
            slots--
            if (!reentry) daily--
            followUps(occ.senseId, occ.start, 0)
            slotAt = maxOf(windowEnd, occ.start + ProgressiveRules.OPPORTUNITY_CHARS)
        }
        return placed.values.sortedBy { it.occurrence.start }
    }

    private fun reentryCount(input: Input): Int = input.words.values.count { ProgressiveRules.canReenter(it, input.now) }

    private fun charsFor(millis: Long): Int =
        (millis / 60_000.0 * ProgressiveRules.CHARS_PER_MINUTE).toLong().coerceAtMost(Int.MAX_VALUE / 2L).toInt()

    /** [occ] 之后 6000 字内，同义项在几个不同段落再出现（同一段里重复只算一次）。 */
    private fun laterParagraphs(all: List<WordOccurrence>, occ: WordOccurrence): Int =
        all.asSequence()
            .filter { it.senseId == occ.senseId && it.start > occ.end && it.start - occ.start <= ProgressiveRules.LOOKAHEAD_CHARS && it.paragraph != occ.paragraph }
            .map { it.paragraph }
            .distinct()
            .count()
}
