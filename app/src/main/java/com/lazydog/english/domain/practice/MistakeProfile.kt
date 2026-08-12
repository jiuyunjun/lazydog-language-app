package com.lazydog.english.domain.practice

/**
 * 错题画像：把做错的练习题按"错在哪一类形式"聚起来，用来决定接下来讲什么语法点。
 *
 * 存在的理由：以前 AI 挑语法点基本是随机的，学的和错的对不上。
 * 现在是"昨天错在第三人称单数，今天就讲这个"。
 */
object GrammarErrorTag {

    const val Tense = "tense"
    const val Agreement = "agreement"
    const val Plural = "plural"
    const val Article = "article"
    const val Preposition = "preposition"
    const val NonFinite = "non_finite"
    const val WordOrder = "word_order"
    const val Voice = "voice"
    const val Comparison = "comparison"
    const val Other = "other"

    /** 允许 AI 用的标签集合；不认识的一律归到 [Other]。 */
    val all = listOf(
        Tense, Agreement, Plural, Article, Preposition, NonFinite, WordOrder, Voice, Comparison, Other,
    )

    private val labels = mapOf(
        Tense to "时态与体",
        Agreement to "主谓一致 / 三单",
        Plural to "单复数",
        Article to "冠词",
        Preposition to "介词搭配",
        NonFinite to "非谓语形式",
        WordOrder to "语序",
        Voice to "被动语态",
        Comparison to "比较级与最高级",
        Other to "其它形式问题",
    )

    fun normalize(raw: String): String {
        val clean = raw.trim().lowercase().replace('-', '_').replace(' ', '_')
        return if (clean in all) clean else Other
    }

    fun labelZh(tag: String): String = labels[normalize(tag)] ?: labels.getValue(Other)

    /** 给提示词用的说明，让 AI 知道每个标签指什么。 */
    fun promptCatalog(): String = all.joinToString("、") { "$it（${labelZh(it)}）" }
}

/** 一次错题记录，领域层不关心它存在哪。 */
data class DrillMistake(
    val patternEn: String,
    val errorTag: String,
    val occurredAt: Long,
)

data class MistakeSummary(
    val errorTag: String,
    val count: Int,
    /** 错在这一类的语法点，最近的在前，用于给提示词举例。 */
    val patterns: List<String>,
) {
    val labelZh: String get() = GrammarErrorTag.labelZh(errorTag)
}

object MistakeProfile {

    /** 只看最近这些天：老毛病如果不再犯，就该让位给现在真正卡住的地方。 */
    const val WINDOW_DAYS = 21

    /** 保留上限，超过这个天数的错题可以清掉。 */
    const val KEEP_DAYS = 90

    fun windowStart(nowMillis: Long, days: Int = WINDOW_DAYS): Long =
        nowMillis - days * 24L * 60 * 60 * 1000

    /**
     * 按错误类型聚合，次数多的在前；次数相同时最近犯的在前。
     * [minCount] 用来滤掉只错过一次的偶然失手。
     */
    fun summarize(
        mistakes: List<DrillMistake>,
        nowMillis: Long = System.currentTimeMillis(),
        days: Int = WINDOW_DAYS,
        minCount: Int = 1,
        limit: Int = 3,
    ): List<MistakeSummary> {
        val since = windowStart(nowMillis, days)
        val recent = mistakes.filter { it.occurredAt >= since }
        if (recent.isEmpty()) return emptyList()
        return recent.groupBy { GrammarErrorTag.normalize(it.errorTag) }
            .map { (tag, items) ->
                MistakeSummary(
                    errorTag = tag,
                    count = items.size,
                    patterns = items.sortedByDescending { it.occurredAt }
                        .map { it.patternEn }
                        .filter { it.isNotBlank() }
                        .distinct()
                        .take(3),
                )
            }
            .filter { it.count >= minCount }
            .sortedWith(
                compareByDescending<MistakeSummary> { it.count }
                    .thenByDescending { summary ->
                        recent.filter { GrammarErrorTag.normalize(it.errorTag) == summary.errorTag }
                            .maxOf { it.occurredAt }
                    },
            )
            .take(limit)
    }

    /** "主谓一致 / 三单（错过 3 次）· 时态与体（错过 2 次）"，空列表返回 null。 */
    fun summaryText(summaries: List<MistakeSummary>): String? =
        summaries.takeIf { it.isNotEmpty() }
            ?.joinToString(" · ") { "${it.labelZh}（错过 ${it.count} 次）" }
}
