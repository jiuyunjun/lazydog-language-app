package com.lazydog.english.domain.listening

/**
 * 单句 Listening Score（英语听力训练模块DESIGN.md §21、设计稿「Listening Score 规则」）。
 *
 * 分数只用来让用户看见自己的变化，不做排行、不宣称精确科学性（设计稿原话）。规则：
 * - 答错固定 20 分：他确实是看到完整英文才明白的，对应文档里"完整字幕以后才理解：20"。
 * - 答对按**揭晓前播放了几次**给基础分：1 次 100、2 次 85、3 次 70、更多 60。
 * - 再按**用到过的最高一级提示**扣：场景提示 -15、关键词提示 -30。
 * - 挖空英文封顶 50（§21"看部分字幕：最多 50"）；看了完整英文只剩 20，
 *   和答错同分——两种都是"落到文字才明白"，见设计稿"英文放在最后一级"。
 */
fun listeningScore(correct: Boolean, playCount: Int, hint: ListeningHintLevel): Int {
    if (!correct) return SCORE_AFTER_FULL_TEXT
    val base = baseScore(playCount)
    val scored = when (hint) {
        ListeningHintLevel.None -> base
        ListeningHintLevel.Scene -> base - 15
        ListeningHintLevel.Keyword -> base - 30
        ListeningHintLevel.PartialText -> minOf(base - 30, PARTIAL_TEXT_CAP)
        ListeningHintLevel.FullText -> SCORE_AFTER_FULL_TEXT
    }
    return scored.coerceIn(0, 100)
}

/** 只看播放次数能拿到的上限。答题页用它预告"再听一遍这题最高几分"（设计稿屏 52）。 */
fun baseScore(playCount: Int): Int = when {
    playCount <= 1 -> 100
    playCount == 2 -> 85
    playCount == 3 -> 70
    else -> 60
}

private const val SCORE_AFTER_FULL_TEXT = 20
private const val PARTIAL_TEXT_CAP = 50

/** 把关键表达从原句里挖空，用于"挖空英文"这一级提示（§5 Hint Level 3）。 */
fun maskKeyExpression(textEn: String, keyExpressionEn: String): String {
    val key = keyExpressionEn.trim()
    // 关键表达对不上时不能整句照抄——那等于直接给答案。退而求其次挖掉最长的一个词。
    val target = if (key.isNotEmpty() && textEn.contains(key, ignoreCase = true)) {
        key
    } else {
        wordsOf(textEn).maxByOrNull { it.length } ?: return textEn
    }
    val start = textEn.indexOf(target, ignoreCase = true)
    if (start < 0) return textEn
    return textEn.substring(0, start) + BLANK + textEn.substring(start + target.length)
}

private const val BLANK = "_____"

/**
 * 一轮训练结束后的总结（§22、§23、设计稿屏 56）。
 *
 * 只统计这一局：设计稿屏 57 的听力能力档案和周对比都需要跨局历史，这一版没有存储，
 * 所以这里只回答"这十句里你栽在哪儿"，不编造能力画像。
 */
data class ListeningSummary(
    val totalScore: Int,
    val answers: List<ListeningAnswer>,
    val firstListenCount: Int,
    /** 没用提示，但听了两遍以上才答对。 */
    val repeatListenCount: Int,
    val afterHintCount: Int,
    val missedCount: Int,
    val averagePlays: Double,
    /** 没能一次听懂的题里出现最多的听觉难点标签；全部一遍听懂时为空。 */
    val weakestFeature: String?,
    /** 答错时最常栽的那一类误听；全对时为空（设计稿"你栽在哪一类"）。 */
    val weakestMishear: MishearType?,
    /** 没能一遍听懂的题，总结页可以直接再过一遍（设计稿屏 56）。 */
    val worthReplaying: List<ListeningAnswer>,
) {
    val total: Int get() = answers.size
}

fun summarizeListening(answers: List<ListeningAnswer>): ListeningSummary {
    if (answers.isEmpty()) {
        return ListeningSummary(0, emptyList(), 0, 0, 0, 0, 0.0, null, null, emptyList())
    }
    // 四类互斥且穷尽（§23 的四行统计）：没答对 / 用了提示 / 多听几遍 / 一遍就懂。
    val missed = answers.count { !it.correct }
    val afterHint = answers.count { it.correct && it.hintLevel != ListeningHintLevel.None }
    val bare = answers.filter { it.correct && it.hintLevel == ListeningHintLevel.None }
    val firstListen = bare.count { it.playCount <= 1 }
    val repeatListen = bare.size - firstListen
    val weakest = answers.filterNot { it.firstListen }
        .flatMap { it.item.audioFeatures }
        .groupingBy { it }
        .eachCount()
        .maxByOrNull { it.value }
        ?.key
    val weakestMishear = answers.mapNotNull { it.mishear?.mishearType }
        .groupingBy { it }
        .eachCount()
        .maxByOrNull { it.value }
        ?.key
    return ListeningSummary(
        totalScore = answers.sumOf { it.score } / answers.size,
        answers = answers,
        firstListenCount = firstListen,
        repeatListenCount = repeatListen,
        afterHintCount = afterHint,
        missedCount = missed,
        averagePlays = answers.sumOf { it.playCount } / answers.size.toDouble(),
        weakestFeature = weakest,
        weakestMishear = weakestMishear,
        worthReplaying = answers.filterNot { it.firstListen },
    )
}

/**
 * 听觉难点标签的中文说法。AI 按 §11 的英文标签输出（标签集合稳定，好统计），
 * 展示时换成人话——用户不需要认识 "elision" 这个词。
 */
fun audioFeatureLabelZh(feature: String): String = when (feature.trim().lowercase()) {
    "linking" -> "连读"
    "reduction" -> "弱读"
    "contraction" -> "缩写"
    "elision" -> "吞音"
    "assimilation" -> "音变"
    "flap t", "flap_t", "flapt" -> "浊化的 t"
    "gonna", "wanna", "gotta" -> "gonna / wanna 这类口语缩合"
    "numbers" -> "数字"
    "dates" -> "日期"
    "time" -> "时间"
    "names" -> "人名"
    "places" -> "地名"
    "proper nouns" -> "专有名词"
    "stress" -> "重音"
    "emotion" -> "情绪语气"
    "fast speech" -> "语速快"
    "noise" -> "背景噪声"
    "accent" -> "口音"
    else -> feature.trim()
}
