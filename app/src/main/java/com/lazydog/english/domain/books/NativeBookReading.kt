package com.lazydog.english.domain.books

import com.lazydog.english.domain.vocabulary.PartOfSpeech

/**
 * 中文书的母语阅读（`读书与网络阅读DESIGN.md` §5.5～§5.9）：挑生词、撤支架、渲染替换、偏移对照。纯规则，可单测。
 */

/** 英文量档位：每章新引入几个词（§5.6）。 */
enum class EnglishDose(val labelZh: String, val newWordsPerChapter: Int) {
    Light("少", 2),
    Medium("中", 4),
    Heavy("多", 6),
    ;

    companion object {
        fun fromOrdinal(value: Int): EnglishDose = entries.getOrElse(value) { Medium }
    }
}

/** 书级词表里一个词的状态（§5.7）。 */
data class GlossaryWord(
    val zh: String,
    val lemma: String,
    val pos: PartOfSpeech,
    val senseOrder: Int,
    val introducedChapter: Int,
    val stage: ScaffoldStage = ScaffoldStage.Introduced,
    val exposuresInStage: Int = 0,
    val totalExposures: Int = 0,
    val reveals: Int = 0,
    val disabled: Boolean = false,
    /** 「这里不该换」的位置：章节序号和原文偏移。 */
    val excluded: Set<Pair<Int, Int>> = emptySet(),
)

/** 支架阶段：引入（强调色加下划线）→ 提示（强调色）→ 独立（和正文一样）。任何阶段点一下都能看中文。 */
enum class ScaffoldStage(val labelZh: String) {
    Introduced("引入"),
    Hinted("提示"),
    Independent("独立"),
}

object Scaffold {
    /** 引入阶段再见这么多次进提示阶段。 */
    const val TO_HINTED = 2

    /** 提示阶段再见这么多次（期间没点开看中文）进独立阶段。 */
    const val TO_INDEPENDENT = 3

    fun onExposure(word: GlossaryWord): GlossaryWord {
        val seen = word.exposuresInStage + 1
        val next = when {
            word.stage == ScaffoldStage.Introduced && seen >= TO_HINTED -> ScaffoldStage.Hinted
            word.stage == ScaffoldStage.Hinted && seen >= TO_INDEPENDENT -> ScaffoldStage.Independent
            else -> word.stage
        }
        return word.copy(
            stage = next,
            exposuresInStage = if (next == word.stage) seen else 0,
            totalExposures = word.totalExposures + 1,
        )
    }

    /** 点开看中文 = 这次没认出来，退回上一阶段。 */
    fun onReveal(word: GlossaryWord): GlossaryWord = word.copy(
        stage = ScaffoldStage.entries[(word.stage.ordinal - 1).coerceAtLeast(0)],
        exposuresInStage = 0,
        reveals = word.reveals + 1,
    )
}

/** 学习者在这件事上的情况：能力窗口、生词本里各词的熟悉程度。 */
data class LearnerVocabulary(
    /** 英文词频排名窗口（`VocabularyCandidates.rankWindow`）。 */
    val window: IntRange,
    /** 生词本里已经熟悉或掌握的词（原型，小写）：它们不再当生词换。 */
    val familiar: Set<String>,
    /** 生词本里还在学的词（原型，小写）：不受窗口下界限制，常见词没学会也要换。 */
    val learning: Set<String>,
)

object NativeWordPicker {

    /** 能被换的中文词性：实词。人名地名机构名（nr/ns/nt/nz）、数量、代词、虚词、成语习语都不换。 */
    val REPLACEABLE_POS = setOf("n", "v", "a", "vn", "an", "ad", "d", "vd", "ag", "ng", "vg", "b", "z")

    /**
     * 虚化的高频词：情态（不会、可以、应该）、语气和连接（就是、还是、只是、其实）、趋向补语（起来、出来）。
     * 它们的意思跟着句子走，换成一个固定的英文词多半是错的——真机上「不会是脑溢血吧」被换成 unable、
     * 「就是」被换成 namely。词频分不开它们（「就是」比「眼睛」「突然」还少见），所以直接列出来。
     */
    val GENERIC_WORDS = setOf(
        "就是", "不是", "还是", "只是", "也是", "都是", "正是", "便是", "总是", "可是", "于是", "但是", "就要", "就会",
        "不会", "不能", "不要", "不用", "不该", "可以", "可能", "应该", "能够", "需要", "必须", "愿意", "肯定", "一定",
        "已经", "曾经", "正在", "马上", "立刻", "刚才", "刚刚", "现在", "以后", "之前", "之后", "然后", "后来",
        "因为", "所以", "如果", "虽然", "而且", "并且", "或者", "然而", "不过", "其实", "确实", "当然", "终于", "毕竟",
        "这样", "那样", "这么", "那么", "怎么", "什么", "为什么", "一样", "一些", "一下", "一点", "有点", "有些", "一直",
        "起来", "出来", "下来", "过来", "回来", "上来", "下去", "过去", "出去", "进来", "进去",
        "没有", "知道", "觉得", "看来", "好像", "似乎", "仿佛", "感觉", "认为", "以为", "表示", "时候", "东西", "事情",
    )

    /**
     * 这一章新引入哪些词（§5.5）。
     *
     * @param chapterTokens 本章分词结果。
     * @param bookSpread 每个中文词在全书多少章出现过（还没统计完就是空表，按本章次数排）。
     * @param known 书级词表里已有的中文词（含停用的）：不重复引入。
     */
    fun pick(
        chapterTokens: List<ZhToken>,
        index: ZhEnIndex,
        learner: LearnerVocabulary,
        bookSpread: Map<String, Int>,
        known: Set<String>,
        count: Int,
    ): List<ZhMapping> {
        if (count <= 0) return emptyList()
        val counts = chapterTokens.groupingBy { it.word }.eachCount()
        val posOf = chapterTokens.associate { it.word to it.pos }
        return counts.keys.asSequence()
            .filter { it.length >= 2 && it !in known && it !in GENERIC_WORDS && posOf[it] in REPLACEABLE_POS }
            .mapNotNull { zh -> index.resolve(zh, posOf[zh].orEmpty())?.takeIf { eligible(it, learner) } }
            // 同一个英文词只引入一次：「害怕」「恐惧」都对上 fear 时只留更常出现的那个。
            .sortedWith(
                compareByDescending<ZhMapping> { bookSpread[it.zh] ?: 0 }
                    .thenByDescending { counts[it.zh] ?: 0 }
                    .thenBy { it.rank },
            )
            .distinctBy { it.lemma }
            .take(count)
            .toList()
    }

    /** 不熟悉或没学过、难度在窗口里、映射确定。 */
    fun eligible(mapping: ZhMapping, learner: LearnerVocabulary): Boolean {
        if (!mapping.confident || mapping.rank <= 0) return false
        val lemma = mapping.lemma.lowercase()
        if (lemma in learner.familiar) return false
        if (lemma in learner.learning) return mapping.rank <= learner.window.last
        return mapping.rank in learner.window
    }
}

/** 渲染后的一处替换：替换后文本里的起止，以及英文部分的起止（点击、长按按它认）。 */
data class RenderedReplacement(
    val start: Int,
    val end: Int,
    val englishStart: Int,
    val englishEnd: Int,
    val zh: String,
    val lemma: String,
    val stage: ScaffoldStage,
    /** 在原文里的起点，"这里不该换"、曝光去重都按它认。 */
    val sourceStart: Int,
)

/**
 * 一章替换后的文本，以及替换后偏移和原文偏移的对照（§5.9）。
 * 阅读位置、书签都记原文偏移；分页、显示用替换后的。
 */
class RenderedChapter(
    val text: String,
    val replacements: List<RenderedReplacement>,
    /** 每处替换的（原文起点, 原文终点, 替换后起点, 替换后终点），按顺序。 */
    private val pieces: List<IntArray>,
) {
    fun toSource(rendered: Int): Int {
        var delta = 0
        for (p in pieces) {
            if (rendered < p[2]) break
            if (rendered < p[3]) return p[0]
            delta = p[1] - p[3]
        }
        return rendered + delta
    }

    fun toRendered(source: Int): Int {
        var delta = 0
        for (p in pieces) {
            if (source < p[0]) break
            if (source < p[1]) return p[2]
            delta = p[3] - p[1]
        }
        return source + delta
    }

    fun replacementAt(rendered: Int): RenderedReplacement? = replacements.firstOrNull { rendered in it.start until it.end }

    companion object {
        fun plain(text: String) = RenderedChapter(text, emptyList(), emptyList())
    }
}

object NativeChapterRenderer {

    /** 每这么多个字最多一处替换（§5.7 密度）。 */
    const val CHARS_PER_REPLACEMENT = 40

    /** 一句里最多几处。 */
    const val MAX_PER_SENTENCE = 2

    private val SENTENCE_END = setOf('。', '！', '？', '；', '\n')

    /**
     * 按书级词表把 [source] 里的中文词换成英文。只换分词结果里**整个词**对上的地方
     * （「后悔药」是另一个词，不会把里面的「后悔」换掉）。
     *
     * 密度超了时先保证刚引入的词，再按阶段由低到高补位。
     */
    fun render(
        source: String,
        tokens: List<ZhToken>,
        glossary: Map<String, GlossaryWord>,
        chapterIndex: Int,
    ): RenderedChapter {
        val occurrences = tokens.filter { token ->
            val word = glossary[token.word] ?: return@filter false
            !word.disabled && (chapterIndex to token.start) !in word.excluded && word.introducedChapter <= chapterIndex
        }
        if (occurrences.isEmpty()) return RenderedChapter.plain(source)

        val kept = sortedSetOfStarts()
        val keptTokens = HashMap<Int, ZhToken>()
        val priority = occurrences.sortedWith(
            compareBy<ZhToken> { glossary.getValue(it.word).stage.ordinal }.thenBy { it.start },
        )
        for (token in priority) {
            val lower = kept.floor(token.start)
            val upper = kept.ceiling(token.start)
            val farEnough = (lower == null || token.start - lower >= CHARS_PER_REPLACEMENT) &&
                (upper == null || upper - token.start >= CHARS_PER_REPLACEMENT)
            if (!farEnough) continue
            val (sentenceFrom, sentenceTo) = sentenceBounds(source, token.start)
            if (kept.subSet(sentenceFrom, sentenceTo).size >= MAX_PER_SENTENCE) continue
            kept += token.start
            keptTokens[token.start] = token
        }

        val out = StringBuilder(source.length + kept.size * 12)
        val replacements = ArrayList<RenderedReplacement>()
        val pieces = ArrayList<IntArray>()
        var cursor = 0
        for (start in kept) {
            val token = keptTokens.getValue(start)
            val word = glossary.getValue(token.word)
            out.append(source, cursor, token.start)
            val renderedStart = out.length
            // 英文夹在汉字中间时前后各留一个空格，挨着标点就不留。
            if (token.start > 0 && ChineseSegmenter.isHan(source[token.start - 1])) out.append(' ')
            val englishStart = out.length
            out.append(word.lemma)
            val englishEnd = out.length
            // 不加中文括注（用户 2026-11-24 要求）：引入阶段靠样式标出来，点一下看中文。
            if (token.end < source.length && ChineseSegmenter.isHan(source[token.end])) out.append(' ')
            pieces += intArrayOf(token.start, token.end, renderedStart, out.length)
            replacements += RenderedReplacement(
                renderedStart, out.length, englishStart, englishEnd, word.zh, word.lemma, word.stage, token.start,
            )
            cursor = token.end
        }
        out.append(source, cursor, source.length)
        return RenderedChapter(out.toString(), replacements, pieces)
    }

    private fun sortedSetOfStarts() = java.util.TreeSet<Int>()

    private fun sentenceBounds(text: String, at: Int): Pair<Int, Int> {
        var from = at
        while (from > 0 && text[from - 1] !in SENTENCE_END) from--
        var to = at
        while (to < text.length && text[to] !in SENTENCE_END) to++
        return from to to + 1
    }
}
