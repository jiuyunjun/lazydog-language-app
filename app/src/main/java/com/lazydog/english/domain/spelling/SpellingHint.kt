package com.lazydog.english.domain.spelling

/**
 * 提示阶梯的一级（`拼写训练DESIGN.md` §7）。
 *
 * 提示不是弹窗里的一句旁白，而是**让题面上那排字母格多亮几个**。用户看到的东西
 * 和他要写的东西长得一模一样，不需要在脑子里翻译回去。
 */
enum class HintStage(val labelZh: String) {
    /** 只有字母位。字母数本身就是信息，不占一级。 */
    Blank("无提示"),

    /** 词块边界：这个词该怎么切，一个字母都不给。 */
    Chunks("结构"),

    /** 每个词块的首字母。 */
    Initials("首字母"),

    /** 读音。给的是另一个维度的线索，不是更多字母。 */
    Sound("读音"),

    /** 最容易写错的那一段显形。 */
    WeakSpot("薄弱段"),

    /** 整词。 */
    Answer("答案"),
    ;

    val level: Int get() = ordinal

    companion object {
        fun of(level: Int): HintStage = entries[level.coerceIn(0, entries.lastIndex)]
    }
}

/**
 * 这一级提示给出的东西。
 *
 * **它不自己画一行字**：题面上本来就有一排字母格，提示要做的是让那排格子多亮几个，
 * 而不是在旁边再画一份。同一个词在一屏上出现两种挖法，比没有提示更让人糊涂。
 *
 * [revealed] 是这一级额外显形的下标，和题型自己的挖空**取并集**（见 [mask]）：
 * 局部补全本来就露着大半个词，提示只需要在它基础上再点亮几处。
 */
data class SpellingHint(
    val stage: HintStage,
    /** 正确拼写，归一化成小写。 */
    val word: String,
    val revealed: Set<Int>,
    /** 词块起点，界面据此把格子分组。 */
    val chunkStarts: Set<Int>,
    /** 只在 [HintStage.Sound] 及以后非空。 */
    val ipa: String,
    val noteZh: String,
) {

    /**
     * 把这一级的显形叠到题型自己的挖空 [base] 上。
     *
     * [base] 用 `_` 表示要填的位置，其余字符原样保留——两边取并集，
     * 所以提示只会让题面露得更多，不会把题型本来给的东西盖掉。
     */
    fun mask(base: String): String = base.mapIndexed { index, char ->
        if (char == '_' && index in revealed) word.getOrElse(index) { '_' } else char
    }.joinToString("")

    /** 词块分组给过结构之后才显示，不然等于白送一级。 */
    val groupsVisible: Boolean get() = stage >= HintStage.Chunks

    /** 下一级会给什么，用来写在按钮上——代价是掌握度，总得让人先知道买的是什么。 */
    val next: HintStage? get() = if (stage == HintStage.Answer) null else HintStage.of(stage.level + 1)
}

/**
 * 按 [level] 算出这一级该显形哪些位置。
 *
 * [weakSegments] 是这个词累计的薄弱片段，[lastWrongAnswer] 是刚交上来的错答案——
 * 有它的时候薄弱段按"这次错在哪"来定，比按历史统计准。
 */
fun spellingHint(
    expected: String,
    level: Int,
    facts: SpellingFacts = SpellingFacts.None,
    ipa: String = "",
    weakSegments: List<WeakSegment> = emptyList(),
    lastWrongAnswer: String = "",
): SpellingHint {
    val stage = HintStage.of(level)
    val word = expected.trim().lowercase()
    if (word.isEmpty()) return SpellingHint(stage, "", emptySet(), emptySet(), "", "")

    val chunks = SpellingEngine.chunkWord(word, facts)
    val starts = mutableSetOf<Int>()
    var cursor = 0
    for (chunk in chunks) {
        starts += cursor
        cursor += chunk.length
    }

    val revealed = mutableSetOf<Int>()
    when (stage) {
        HintStage.Blank, HintStage.Chunks -> Unit
        HintStage.Initials, HintStage.Sound -> revealed += starts
        HintStage.WeakSpot -> {
            revealed += starts
            // 不知道哪儿弱就把第一块整块给出来：总得比上一级多点东西，不然这一级白扣分。
            val weak = SpellingEngine.weakSpotOf(word, lastWrongAnswer, weakSegments)
            val range = weak?.let { it.start until it.endExclusive }
                ?: (0 until (chunks.firstOrNull()?.length ?: 0))
            revealed += range.filter { it in word.indices }
        }
        HintStage.Answer -> revealed += word.indices
    }

    return SpellingHint(
        stage = stage,
        word = word,
        revealed = revealed,
        chunkStarts = starts,
        ipa = if (stage >= HintStage.Sound) ipa.trim() else "",
        noteZh = when (stage) {
            HintStage.Chunks -> "先按这几块想，别一个字母一个字母地凑。"
            HintStage.WeakSpot -> "这一段是你最常写错的地方。"
            else -> ""
        },
    )
}
