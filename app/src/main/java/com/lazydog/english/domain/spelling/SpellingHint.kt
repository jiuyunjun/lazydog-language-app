package com.lazydog.english.domain.spelling

/**
 * 提示阶梯的一级（`拼写训练DESIGN.md` §12）。
 *
 * 这一版把提示从"弹窗里的一句旁白"改成了**题面上的骨架逐级显形**。旧版的问题是三件事：
 * 提示说的是"开头那块是 sep"这种第三人称描述，用户还得自己在脑子里拼回去；
 * 每要一次提示弹一次窗；而且还没交答案时它只能说"一共 9 个字母"，等于废话。
 *
 * 现在骨架常驻在题面上，每一级只是让它多显一点。用户看到的东西和他要写的东西
 * 长得一模一样，不需要翻译。
 */
enum class HintStage(val labelZh: String) {
    /** 只有字母位。字母数本身就是信息，不占一级。 */
    Blank("无提示"),

    /** 词块边界：这个词该怎么切，一个字母都不给。 */
    Chunks("词块结构"),

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
 * 题面上那行骨架。
 *
 * [skeleton] 是要显示的字符串，未显形的字母是 `_`，词块之间用 ` · ` 隔开；
 * [ipa] 只在 [HintStage.Sound] 及以后非空；[noteZh] 是这一级顺带说的一句话，可以为空。
 */
data class SpellingHint(
    val stage: HintStage,
    val skeleton: String,
    val ipa: String,
    val noteZh: String,
) {
    /** 下一级会给什么，用来写在按钮上——让用户在花掉代价之前就知道换来什么。 */
    val next: HintStage? get() = if (stage == HintStage.Answer) null else HintStage.of(stage.level + 1)
}

/** 词块之间的分隔，宽一点才看得出这是"块"而不是空格。 */
private const val CHUNK_SEPARATOR = " · "

/**
 * 按 [level] 生成题面骨架。
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
    if (word.isEmpty()) return SpellingHint(stage, "", "", "")

    val chunks = SpellingEngine.chunkWord(word, facts)
    val weak = SpellingEngine.weakSpotOf(word, lastWrongAnswer, weakSegments)
    val revealed = BooleanArray(word.length)

    when (stage) {
        HintStage.Blank, HintStage.Chunks -> Unit
        HintStage.Initials -> revealChunkInitials(chunks, revealed)
        HintStage.Sound -> revealChunkInitials(chunks, revealed)
        HintStage.WeakSpot -> {
            revealChunkInitials(chunks, revealed)
            // 不知道哪儿弱就多给一块：总得比上一级多点东西，不然这一级白扣分。
            val range = weak?.let { it.start until it.endExclusive } ?: chunkRange(chunks, 0)
            for (i in range) if (i in revealed.indices) revealed[i] = true
        }
        HintStage.Answer -> revealed.fill(true)
    }

    // 第 0 级不分块：还没给结构之前就把块画出来，等于白送一级。
    val grouped = stage >= HintStage.Chunks
    return SpellingHint(
        stage = stage,
        skeleton = renderSkeleton(word, chunks, revealed, grouped),
        ipa = if (stage >= HintStage.Sound) ipa.trim() else "",
        noteZh = when (stage) {
            HintStage.Chunks -> "先按这几块想，别一个字母一个字母地凑。"
            HintStage.WeakSpot -> weak?.let { "这一段是你最常写错的地方。" }.orEmpty()
            else -> ""
        },
    )
}

private fun revealChunkInitials(chunks: List<String>, revealed: BooleanArray) {
    var cursor = 0
    for (chunk in chunks) {
        if (cursor in revealed.indices) revealed[cursor] = true
        cursor += chunk.length
    }
}

private fun chunkRange(chunks: List<String>, index: Int): IntRange {
    var start = 0
    for ((i, chunk) in chunks.withIndex()) {
        if (i == index) return start until (start + chunk.length)
        start += chunk.length
    }
    return IntRange.EMPTY
}

private fun renderSkeleton(
    word: String,
    chunks: List<String>,
    revealed: BooleanArray,
    grouped: Boolean,
): String {
    val letters = word.mapIndexed { i, c -> if (revealed[i]) c.toString() else "_" }
    if (!grouped || chunks.size < 2) return letters.joinToString(" ")
    val parts = mutableListOf<String>()
    var cursor = 0
    for (chunk in chunks) {
        val end = (cursor + chunk.length).coerceAtMost(letters.size)
        if (cursor < end) parts += letters.subList(cursor, end).joinToString(" ")
        cursor = end
    }
    return parts.joinToString(CHUNK_SEPARATOR)
}
