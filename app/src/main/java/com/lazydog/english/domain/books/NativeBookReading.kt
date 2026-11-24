package com.lazydog.english.domain.books

import com.lazydog.english.domain.vocabulary.PartOfSpeech

/**
 * 中文书的母语阅读（`读书与网络阅读DESIGN.md` §5、§12）：新词资格、虚化词、渲染替换、偏移对照。纯规则，可单测。
 * 放哪些词由 [ProgressivePlanner] 决定。
 */

/** 学习者在这件事上的情况：能力窗口、生词本里各词的熟悉程度。 */
data class LearnerVocabulary(
    /** 英文词频排名窗口（`VocabularyCandidates.rankWindow`）。 */
    val window: IntRange,
    /** 生词本里已经熟悉或掌握的词（原型，小写）：它们不再当生词换。 */
    val familiar: Set<String>,
    /** 生词本里还在学的词（原型，小写）：不当新词引入。 */
    val learning: Set<String>,
)

object NativeWordPicker {

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
     * 能不能当新词引入（§12.3.1）：映射确定、不熟悉、难度在窗口里。
     * 生词本里正在学的词不当新词引入——它们有自己的复习节奏。
     */
    fun eligible(mapping: ZhMapping, learner: LearnerVocabulary): Boolean {
        if (!mapping.confident || mapping.rank <= 0) return false
        val lemma = mapping.lemma.lowercase()
        if (lemma in learner.familiar || lemma in learner.learning) return false
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
    val pos: PartOfSpeech,
    val senseOrder: Int,
    /** 在原文里的起止，"这里换得不对"、展示去重都按它认。 */
    val sourceStart: Int,
    val sourceEnd: Int,
    /** 这一处是第一次引入这个义项。 */
    val introduces: Boolean,
) {
    val senseId: String get() = ProgressiveRules.senseId(lemma, pos, senseOrder)
}

/**
 * 一章替换后的文本，以及替换后偏移和原文偏移的对照（§5.9、§12.2「兼容原文功能」）。
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

/** 按规划结果（[ProgressivePlanner]）把原文里的词换成英文。只是显示层，原文不动。 */
object NativeChapterRenderer {

    fun render(source: String, placements: List<Placement>): RenderedChapter {
        if (placements.isEmpty()) return RenderedChapter.plain(source)
        val out = StringBuilder(source.length + placements.size * 12)
        val replacements = ArrayList<RenderedReplacement>()
        val pieces = ArrayList<IntArray>()
        var cursor = 0
        for (placement in placements.sortedBy { it.occurrence.start }) {
            val occ = placement.occurrence
            if (occ.start < cursor) continue
            out.append(source, cursor, occ.start)
            val renderedStart = out.length
            // 英文夹在汉字中间时前后各留一个空格，挨着标点就不留。不加中文括注，点一下看中文。
            if (occ.start > 0 && ChineseSegmenter.isHan(source[occ.start - 1])) out.append(' ')
            val englishStart = out.length
            out.append(occ.mapping.lemma)
            val englishEnd = out.length
            if (occ.end < source.length && ChineseSegmenter.isHan(source[occ.end])) out.append(' ')
            pieces += intArrayOf(occ.start, occ.end, renderedStart, out.length)
            replacements += RenderedReplacement(
                start = renderedStart,
                end = out.length,
                englishStart = englishStart,
                englishEnd = englishEnd,
                zh = occ.mapping.zh,
                lemma = occ.mapping.lemma,
                pos = occ.mapping.pos,
                senseOrder = occ.mapping.senseOrder,
                sourceStart = occ.start,
                sourceEnd = occ.end,
                introduces = placement.introduces,
            )
            cursor = occ.end
        }
        out.append(source, cursor, source.length)
        return RenderedChapter(out.toString(), replacements, pieces)
    }
}
