package com.lazydog.english.domain.listening

/**
 * 听力训练的内容模型（英语听力训练模块DESIGN.md §17）。
 *
 * 这个模块训练的是"英语声音 → 语义理解"，所以一道题的主体是 [text] 的**音频**，
 * 英文原文在揭晓前不许出现在界面上。[meaningZh] 既是正确答案也是揭晓页的中文。
 *
 * MVP 只保留界面、评分和总结真正会用到的维度（§25）。场景/意图/语体/语气这几个维度
 * 主要在生成阶段起作用——它们决定 AI 写出什么样的句子；[audioFeatures] 则在总结里
 * 回答"今天最容易绊住你的是什么"。文档 §10、§20 里更细的能力画像要有长期数据才有意义，
 * 这一版不做，见 ROADMAP M9。
 */
data class ListeningItem(
    val textEn: String,
    val meaningZh: String,
    val sceneZh: String,
    val subSceneZh: String,
    val intentZh: String,
    val toneZh: String,
    val registerZh: String,
    val cefr: String,
    /** 1~5，只由 AI 标注，本地不重算。 */
    val listeningDifficulty: Int,
    /** 听觉难点标签（§11），如 linking / reduction / gonna。 */
    val audioFeatures: List<String>,
    val keyExpression: ListeningKeyExpression,
    /** 干扰项的中文意思，正好两条——§25 定的是"中文三选一"。 */
    val wrongMeaningsZh: List<String>,
    /** Hint 1：语义场景提示，不能点出关键词，也不能说出整句意思。 */
    val sceneHintZh: String,
    /** Hint 2：关键词提示，点名要听的那个词并说它为什么难听出来。 */
    val keywordHintZh: String,
)

data class ListeningKeyExpression(val en: String, val meaningZh: String)

/**
 * 提示层级（§5）。是"最高用到过的一级"，不是当前显示的一级——评分按最高级扣。
 *
 * [PartialText] 由本地把 [ListeningItem.keyExpression] 从原句里挖空得到，不问 AI：
 * 挖空位置必须和关键表达严格一致，交给 AI 反而会对不上。见 [maskKeyExpression]。
 * 完整英文（文档里的 Hint Level 4）不做成提示——那就是揭晓页本身。
 */
enum class ListeningHintLevel(val labelZh: String) {
    None("裸听"),
    Scene("场景提示"),
    Keyword("关键词提示"),
    PartialText("部分英文"),
    ;

    fun next(): ListeningHintLevel = entries.getOrElse(ordinal + 1) { this }

    val hasNext: Boolean get() = ordinal < entries.lastIndex
}

/** 一道题答完后的结果，评分和总结都从它算（§21、§23）。 */
data class ListeningAnswer(
    val item: ListeningItem,
    val correct: Boolean,
    /** 揭晓前一共点了几次播放。 */
    val playCount: Int,
    val hintLevel: ListeningHintLevel,
) {
    val score: Int get() = listeningScore(correct, playCount, hintLevel)

    /** 第一次听就答对，而且没用提示——真正意义上的"听懂了"。 */
    val firstListen: Boolean get() = correct && playCount <= 1 && hintLevel == ListeningHintLevel.None
}

data class ListeningSetRequest(
    val sceneZh: String,
    /** 这个场景下的二级分类，交给 AI 分配到各句，避免十句都在说同一件事。 */
    val subScenesZh: List<String>,
    val count: Int,
    val learnerLevel: String,
    val topics: List<String>,
)

object ListeningValidation {

    data class Validated(val valid: List<ListeningItem>, val droppedNotes: List<String>)

    private val allowedCefr = setOf("A1", "A2", "B1", "B2", "C1")

    /** 逐条校验，坏的丢掉并记原因；剩几条由调用方决定够不够开一局。 */
    fun validate(items: List<ListeningItem>, maxCount: Int): Validated {
        val valid = mutableListOf<ListeningItem>()
        val dropped = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        for (raw in items) {
            val item = raw.trimmed()
            val reason = reject(item, seen)
            if (reason == null) {
                seen.add(item.textEn.lowercase())
                valid.add(item)
            } else {
                dropped.add("${item.textEn.take(40).ifBlank { "(空)" }}：$reason")
            }
            if (valid.size == maxCount) break
        }
        return Validated(valid, dropped)
    }

    private fun reject(item: ListeningItem, seen: Set<String>): String? {
        val words = wordsOf(item.textEn)
        return when {
            item.textEn.isBlank() -> "句子为空"
            words.size !in 4..30 -> "句子长度应该在 4~30 词"
            Regex("[\u4E00-\u9FFF]").containsMatchIn(item.textEn) -> "英文句子里混了中文"
            item.textEn.lowercase() in seen -> "本批重复"
            item.meaningZh.isBlank() || item.meaningZh.length > 120 -> "中文意思缺失或过长"
            item.wrongMeaningsZh.size != 2 -> "干扰项必须正好两条"
            item.wrongMeaningsZh.any { it.isBlank() || it.length > 120 } -> "干扰项缺失或过长"
            (item.wrongMeaningsZh + item.meaningZh).map(::normalizeZh).toSet().size != 3 -> "选项之间重复"
            item.keyExpression.en.isBlank() || item.keyExpression.meaningZh.isBlank() -> "重点表达不完整"
            // 揭晓页和 Hint 3 都要拿这个表达去原句里定位，对不上就整题作废。
            !item.textEn.lowercase().contains(item.keyExpression.en.lowercase().trim()) ->
                "重点表达不在句子里"
            item.cefr.uppercase() !in allowedCefr -> "CEFR 等级不合法"
            item.listeningDifficulty !in 1..5 -> "听力难度应该是 1~5"
            item.audioFeatures.isEmpty() || item.audioFeatures.size > 5 -> "听觉难点应该有 1~5 个"
            item.audioFeatures.any { it.isBlank() || it.length > 30 } -> "听觉难点标签不合法"
            item.sceneHintZh.isBlank() || item.sceneHintZh.length > 60 -> "场景提示缺失或过长"
            // 提示是给没听懂的人用的，先把答案抖出来就没意义了。
            leaksAnswer(item.sceneHintZh, item) -> "场景提示泄露了答案"
            item.keywordHintZh.isBlank() || item.keywordHintZh.length > 80 -> "关键词提示缺失或过长"
            item.sceneZh.isBlank() || item.intentZh.isBlank() -> "场景或意图缺失"
            else -> null
        }
    }

    /** 提示里出现整句英文，或者把正确中文原样抄了一遍，都算泄露。 */
    private fun leaksAnswer(hint: String, item: ListeningItem): Boolean =
        hint.contains(item.textEn, ignoreCase = true) ||
            normalizeZh(hint).contains(normalizeZh(item.meaningZh))

    private fun normalizeZh(value: String): String =
        value.filterNot { it.isWhitespace() || it in "，。！？、；：“”‘’,.!?;:\"'" }

    private fun ListeningItem.trimmed() = copy(
        textEn = textEn.trim(),
        meaningZh = meaningZh.trim(),
        sceneZh = sceneZh.trim(),
        subSceneZh = subSceneZh.trim(),
        intentZh = intentZh.trim(),
        toneZh = toneZh.trim(),
        registerZh = registerZh.trim(),
        cefr = cefr.trim().uppercase(),
        audioFeatures = audioFeatures.map { it.trim().lowercase() }.filter { it.isNotBlank() }.distinct(),
        keyExpression = keyExpression.copy(
            en = keyExpression.en.trim(),
            meaningZh = keyExpression.meaningZh.trim(),
        ),
        wrongMeaningsZh = wrongMeaningsZh.map { it.trim() },
        sceneHintZh = sceneHintZh.trim(),
        keywordHintZh = keywordHintZh.trim(),
    )
}

internal fun wordsOf(text: String): List<String> =
    Regex("[A-Za-z][A-Za-z'\u2019-]*").findAll(text).map { it.value }.toList()
