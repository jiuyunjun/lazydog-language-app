package com.lazydog.english.domain.listening

/**
 * 听力训练的内容模型（设计稿屏 50～58「句子内容模型」、英语听力训练模块DESIGN.md §17）。
 *
 * 这个模块训练的是"英语声音 → 语义理解"，所以一道题的主体是 [textEn] 的**音频**，
 * 英文原文在揭晓前不许出现在界面上。[meaningZh] 既是正确答案也是揭晓页的中文。
 *
 * MVP 只保留界面、评分和总结真正会用到的维度（§25）。场景/意图/语体/语气这几个维度
 * 主要在生成阶段起作用——它们决定 AI 写出什么样的句子；[audioFeatures] 和
 * [ListeningDistractor.mishearType] 则在总结里回答"今天栽在哪儿"。
 * 设计稿屏 57 的听力能力档案要有跨轮次历史才有意义，这一版不做，见 D-020。
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
    /**
     * 干扰项，正好三条——设计稿屏 52 是四选一（一个正确 + 三个错误）。
     * 每条都要说清它是哪一类误听、为什么会听成这样：答错时揭晓页要指出
     * "你听成了什么"，总结要统计"你栽在哪一类"（设计稿屏 55、屏 56）。
     */
    val distractors: List<ListeningDistractor>,
    /** Hint 1：语义场景提示，不能点出关键词，也不能说出整句意思。 */
    val sceneHintZh: String,
    /** Hint 2：关键词提示，点名要听的那个词并说它为什么难听出来。 */
    val keywordHintZh: String,
) {
    /** 四个选项的原始集合。展示顺序由界面按题目下标定死，不在这里洗牌。 */
    val allOptionsZh: List<String> get() = listOf(meaningZh) + distractors.map { it.meaningZh }
}

data class ListeningKeyExpression(val en: String, val meaningZh: String)

data class ListeningDistractor(
    val meaningZh: String,
    val mishearType: MishearType,
    /** 为什么会听成这个。设计稿屏 55 要求讲到音：哪个词弱读/连读成了什么，意思因此怎么变。 */
    val whyZh: String,
)

/**
 * 干扰项的来源类型（设计稿「干扰项从哪儿来」）。
 *
 * 取值是封闭集合而不是自由文本：答错后要统计"你栽在哪一类"，类型能自由写的话
 * 这个统计就没法聚合了。AI 按 [wire] 返回稳定的英文 key，展示时才换成中文。
 */
enum class MishearType(val wire: String, val labelZh: String) {
    Negation("negation", "否定词漏听"),
    Tense("tense", "时态误判"),
    Linking("linking", "连读粘成了别的词"),
    Reduction("reduction", "弱读没听出来"),
    KeyWord("keyword", "关键词理解反了"),
    SimilarAction("similar_action", "听成了相似的动作"),
    SimilarScene("similar_scene", "听成了相似的场景"),
    ;

    companion object {
        fun fromWire(value: String): MishearType? {
            val clean = value.trim().lowercase().replace(' ', '_').replace('-', '_')
            return entries.firstOrNull { it.wire == clean }
        }

        /** 给提示词用：把允许的取值一次列全，免得两处各写一份慢慢跑偏。 */
        val wireList: String get() = entries.joinToString("、") { it.wire }
    }
}

/**
 * 提示层级（§5、设计稿屏 53「英文放在最后一级」）。
 * 是"最高用到过的一级"，不是当前显示的一级——评分按最高级扣。
 *
 * [PartialText] 和 [FullText] 由本地从原句生成，不问 AI：挖空位置必须和关键表达严格一致，
 * 交给 AI 反而会对不上。见 [maskKeyExpression]。
 *
 * 设计稿：「前两级不给拼写，用户仍在听音辨义；只有 Level 3 起才落到文字，
 * 分数上限也从这里开始压。」
 */
enum class ListeningHintLevel(val labelZh: String) {
    None("裸听"),
    Scene("场景提示"),
    Keyword("关键词提示"),
    PartialText("挖空英文"),
    FullText("完整英文"),
    ;

    fun next(): ListeningHintLevel = entries.getOrElse(ordinal + 1) { this }

    val hasNext: Boolean get() = ordinal < entries.lastIndex

    /** 从这一级起用户看到的是文字而不是声音，分数要压。 */
    val showsText: Boolean get() = this >= PartialText
}

/** 一道题答完后的结果，评分和总结都从它算（§21、§23、设计稿屏 56）。 */
data class ListeningAnswer(
    val item: ListeningItem,
    /** 用户选中的那条中文。 */
    val chosenZh: String,
    /** 揭晓前一共点了几次播放。 */
    val playCount: Int,
    val hintLevel: ListeningHintLevel,
) {
    val correct: Boolean get() = chosenZh == item.meaningZh

    /** 选错时命中的那条干扰项，用来告诉用户"你听成了什么"。选对时为 null。 */
    val mishear: ListeningDistractor?
        get() = item.distractors.firstOrNull { it.meaningZh == chosenZh }

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

    /** 逐条校验，坏的丢掉并记原因；剩几条由调用方决定够不够开一局。 */
    fun validate(items: List<ListeningItem>, maxCount: Int): Validated {
        val session = Session(maxCount)
        items.forEach { session.offer(it) }
        return session.result
    }

    /**
     * 一批句子的校验过程，可以一条一条喂。
     *
     * 流式生成时第一句刚闭合就要能开练，等不到整批收完（英语听力训练模块DESIGN.md §18），
     * 所以"本批重复""收够了没有"这些跨条目的状态得留在这里，而不是每次重头算一遍。
     */
    class Session(private val maxCount: Int) {

        private val seen = mutableSetOf<String>()
        private val valid = mutableListOf<ListeningItem>()
        private val dropped = mutableListOf<String>()

        val full: Boolean get() = valid.size >= maxCount

        val result: Validated get() = Validated(valid.toList(), dropped.toList())

        /** 收下一条，返回校验通过后的条目；被丢掉时返回 null（原因记在 [result] 里）。 */
        fun offer(raw: ListeningItem): ListeningItem? {
            if (full) return null
            val item = raw.trimmed()
            val reason = reject(item, seen)
            if (reason != null) {
                dropped.add("${item.textEn.take(40).ifBlank { "(空)" }}：$reason")
                return null
            }
            seen.add(item.textEn.lowercase())
            valid.add(item)
            return item
        }
    }
}

private val allowedCefr = setOf("A1", "A2", "B1", "B2", "C1")

private fun reject(item: ListeningItem, seen: Set<String>): String? {
    val words = wordsOf(item.textEn)
    return when {
        item.textEn.isBlank() -> "句子为空"
        words.size !in 4..30 -> "句子长度应该在 4~30 词"
        Regex("[\\u4E00-\\u9FFF]").containsMatchIn(item.textEn) -> "英文句子里混了中文"
        item.textEn.lowercase() in seen -> "本批重复"
        item.meaningZh.isBlank() || item.meaningZh.length > 120 -> "中文意思缺失或过长"
        item.distractors.size != 3 -> "干扰项必须正好三条"
        item.distractors.any { it.meaningZh.isBlank() || it.meaningZh.length > 120 } ->
            "干扰项缺失或过长"
        // 选项撞车的话四选一会退化成三选一甚至二选一，题就白出了。
        item.allOptionsZh.map(::normalizeZh).toSet().size != 4 -> "选项之间重复"
        // 答错后要统计"你栽在哪一类"，所以每条都得说清自己是哪一类、为什么。
        item.distractors.any { it.whyZh.isBlank() || it.whyZh.length > 160 } ->
            "干扰项没说清为什么会听错"
        item.distractors.map { it.mishearType }.distinct().size != 3 -> "三条干扰项的误听类型重复"
        item.keyExpression.en.isBlank() || item.keyExpression.meaningZh.isBlank() -> "重点表达不完整"
        // 揭晓页和挖空提示都要拿这个表达去原句里定位，对不上就整题作废。
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
    distractors = distractors.map { it.copy(meaningZh = it.meaningZh.trim(), whyZh = it.whyZh.trim()) },
    sceneHintZh = sceneHintZh.trim(),
    keywordHintZh = keywordHintZh.trim(),
)

internal fun wordsOf(text: String): List<String> =
    Regex("[A-Za-z][A-Za-z'’-]*").findAll(text).map { it.value }.toList()
