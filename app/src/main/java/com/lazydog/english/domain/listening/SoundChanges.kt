package com.lazydog.english.domain.listening

/**
 * 揭晓页的"为什么听起来不是这样"：从原句里找出连读、弱读、浊化这些音变，逐条讲清楚。
 *
 * 为什么本地算而不问 AI（同 [maskKeyExpression] 的理由）：
 * 用户答完题正等着看讲解，这时候再发一次网络请求就是干等；而连读规则本身是死的，
 * 按拼写就能判个八九不离十。AI 那边只负责标 [ListeningItem.audioFeatures]（这句难在哪一类），
 * 具体"哪两个词粘在了一起"由这里指出来。
 *
 * 这是**按拼写做的近似**，不是音标级别的判定：英语拼写和读音本来就对不齐，
 * 少数词会判错或漏判。所以它只用于讲解，绝不参与评分，也不写进任何持久化状态。
 */
data class SoundChange(
    /** 句子里发生音变的那一小段，原样取自 [ListeningItem.textEn]。 */
    val spanEn: String,
    val rule: SoundRule,
    /** 针对这一处的说明，会连着 [SoundRule.ruleZh] 一起显示。 */
    val noteZh: String,
)

/** 音变的类型。[ruleZh] 是这一类的通则，[labelZh] 是标签。 */
enum class SoundRule(val labelZh: String, val ruleZh: String, internal val priority: Int) {
    Contraction("缩合", "口语里两个词合成一个，拼写上根本不存在这个词，只能靠听熟", 1),
    WeakForm("弱读", "介词、连词、助动词这类功能词在句子里不重读，元音塌成 ə，短到几乎听不见", 2),
    Assimilation("音变", "t / d 碰上 you 会变成 ch / j 的音", 2),
    Flap("浊化的 t", "t 夹在两个元音中间时读得接近 d", 3),
    Linking("连读", "前一个词以辅音收尾、后一个词以元音开头时，辅音滑到后一个词上，两个词粘成一个", 3),
    HDrop("h 脱落", "him / her / his 这些词在句中常常不发 h，前一个词直接连过去", 4),
    Gemination("同音相碰", "前后两个词碰上同一个辅音，只发一次，中间不断开", 5),
    StopRelease("失去爆破", "p b t d k g 后面紧跟辅音时只做口型不出声，听上去像被吞掉了", 6),
}

/**
 * 找出 [textEn] 里最值得讲的几处音变。
 *
 * [focusEn] 传这句的重点表达：落在重点表达上的音变排在前面——用户刚刚没听出来的
 * 多半就是那一段。同一类规则只举一个例子，免得三条都在讲连读。
 */
fun analyzeSoundChanges(textEn: String, focusEn: String = "", limit: Int = 3): List<SoundChange> {
    val tokens = tokenize(textEn)
    if (tokens.isEmpty()) return emptyList()
    val focus = focusEn.trim().lowercase()

    val found = mutableListOf<SoundChange>()
    tokens.forEachIndexed { index, token ->
        if (index > 0) contractionOf(token)?.let(found::add)
        if (index > 0) weakFormOf(token)?.let(found::add)
        if (index > 0) hDropOf(tokens[index - 1], token)?.let(found::add)
        flapInsideOf(token)?.let(found::add)
        val next = tokens.getOrNull(index + 1) ?: return@forEachIndexed
        betweenWords(token, next)?.let(found::add)
    }

    return found
        .distinctBy { it.spanEn.lowercase() }
        .sortedWith(
            compareByDescending<SoundChange> { focus.isNotEmpty() && focus.contains(it.spanEn.lowercase()) }
                .thenBy { it.rule.priority },
        )
        .distinctBy { it.rule }
        .take(limit)
}

/** 两个相邻词之间的音变。一处只报一条，按"最能解释听错"的顺序挑。 */
private fun betweenWords(prev: String, next: String): SoundChange? {
    val a = clean(prev)
    val b = clean(next)
    if (a.isEmpty() || b.isEmpty()) return null
    val span = "$prev $next"
    val joined = a + b
    val tail = endingConsonant(a) ?: return null

    if (tail in "td" && (b == "you" || b == "your" || b == "yours")) {
        val sound = if (tail == 't') "ch" else "j"
        return SoundChange(span, SoundRule.Assimilation, "「$a」的 $tail 撞上 $b，读成 $sound 的音，整段听着像「${joined.replace("${tail}y", sound)}」")
    }
    if (startsWithVowelSound(b)) {
        return if (tail == 't') {
            SoundChange(span, SoundRule.Flap, "「$a」的 t 连到「$b」上，还会浊化成 d，两个词听着像「$joined」，t 那一下几乎听不出来")
        } else {
            SoundChange(span, SoundRule.Linking, "「$a」的 $tail 滑到「$b」上，两个词粘成「$joined」，中间没有停顿")
        }
    }
    val head = b.first()
    if (head == tail) {
        return SoundChange(span, SoundRule.Gemination, "两个 $tail 碰在一起只发一次，听着像「${a.dropLast(1)}$b」，不是两个词各念各的")
    }
    if (tail in STOPS && head !in VOWEL_LETTERS) {
        return SoundChange(span, SoundRule.StopRelease, "「$a」结尾的 $tail 后面紧跟辅音，只做口型不出声，听起来像被吞掉了")
    }
    return null
}

private fun contractionOf(word: String): SoundChange? {
    val w = clean(word)
    CONTRACTIONS[w]?.let { return SoundChange(word, SoundRule.Contraction, it) }
    val suffix = CONTRACTION_SUFFIXES.keys.firstOrNull { w.endsWith(it) && w.length > it.length } ?: return null
    return SoundChange(word, SoundRule.Contraction, CONTRACTION_SUFFIXES.getValue(suffix))
}

private fun weakFormOf(word: String): SoundChange? =
    WEAK_FORMS[clean(word)]?.let { SoundChange(word, SoundRule.WeakForm, it) }

private fun hDropOf(prev: String, word: String): SoundChange? {
    val w = clean(word)
    if (w !in H_DROPPED) return null
    val a = clean(prev)
    if (a.isEmpty()) return null
    return SoundChange(
        "$prev $word",
        SoundRule.HDrop,
        "「$w」在句中常常不发 h，「$a」直接连过去，听着像「$a${w.drop(1)}」",
    )
}

/** 词内部的浊化 t：water / better / city 这种夹在两个元音之间的 t。 */
private fun flapInsideOf(word: String): SoundChange? {
    val w = clean(word)
    if (w.length < 4) return null
    if (!FLAP_INSIDE.containsMatchIn(w)) return null
    return SoundChange(word, SoundRule.Flap, "「$w」中间的 t 夹在元音之间，读得接近 d，别按拼写去等一个清脆的 t")
}

private val FLAP_INSIDE = Regex("[aeiou]tt?[aeiouy]")

private const val VOWEL_LETTERS = "aeiou"
private const val STOPS = "pbtdkg"

private fun tokenize(text: String): List<String> =
    text.split(Regex("\\s+")).map { it.trim() }.filter { clean(it).isNotEmpty() }

/** 去掉词两头的标点，保留词内的撇号（don't、it's 要整个看）。 */
private fun clean(word: String): String =
    word.lowercase().trim('.', ',', '!', '?', ';', ':', '"', '\'', '’', '“', '”', '(', ')', '—', '-')

/** 这个词开头是不是元音音素。按拼写近似，列外的少数词单独记着。 */
private fun startsWithVowelSound(word: String): Boolean {
    if (word in SILENT_H) return true
    val first = word.firstOrNull() ?: return false
    return first in VOWEL_LETTERS && word !in CONSONANTAL_START
}

/** 这个词结尾的辅音字母；元音收尾时返回 null（那就不构成连读的前半段）。 */
private fun endingConsonant(word: String): Char? {
    if (word in VOWEL_ENDING) return null
    // 词尾不发音的 e：take 收在 k 上，不是 e。
    val base = if (word.length > 2 && word.endsWith("e")) word.dropLast(1) else word
    val last = base.lastOrNull() ?: return null
    if (last in VOWEL_LETTERS) return null
    // now / day / new 里的 w、y 跟前面的元音合成双元音，不是辅音收尾。
    if (last in "wy" && base.dropLast(1).lastOrNull() in VOWEL_LETTERS.toList()) return null
    return last
}

private val SILENT_H = setOf("hour", "hours", "honest", "honestly", "honor", "honour", "heir")

/** 拼写以元音开头、读音却是辅音起头的词。 */
private val CONSONANTAL_START = setOf(
    "one", "once", "use", "used", "using", "useful", "user", "unique", "university", "uniform",
    "union", "unit", "united", "universal", "usually", "euro", "european", "eu",
)

/** 拼写以辅音字母收尾、读音却落在元音上的词。 */
private val VOWEL_ENDING = setOf("the", "she", "he", "we", "be", "me", "you")

private val H_DROPPED = setOf("him", "her", "his", "hers")

/**
 * 常见功能词的弱读。只收真正会让人听漏的那些——每条都要说清"听起来变成了什么"，
 * 光说"这个词会弱读"帮不上忙。
 */
private val WEAK_FORMS = mapOf(
    "to" to "「to」在句中从不重读，元音塌成 ə，快起来只剩一个 t 的动作",
    "of" to "「of」弱读成 ə(v)，a lot of 听着像「a lotta」",
    "and" to "「and」常只剩「ən」，甚至只有一个 n，两边的词像被一条线串起来",
    "for" to "「for」弱读成「fə」，短得容易和 four 的强读分不清",
    "at" to "「at」弱读成「ət」，t 还常常失去爆破",
    "as" to "「as」弱读成「əz」",
    "than" to "「than」弱读成「ðən」，和 then 几乎听不出区别",
    "from" to "「from」弱读成「frəm」",
    "can" to "「can」肯定句里弱读成「kən」，很短；能听清「kæn」的多半是 can't 少了 t",
    "was" to "「was」弱读成「wəz」",
    "were" to "「were」弱读成「wə」",
    "are" to "「are」弱读成「ə」，常常直接粘在主语后面",
    "have" to "「have」作助动词时弱读成「həv」或「əv」",
    "has" to "「has」作助动词时弱读成「həz」或「əz」",
    "had" to "「had」作助动词时弱读成「həd」或「əd」",
    "them" to "「them」口语里常读成「əm」，甚至写成 'em",
    "some" to "「some」表示「一些」时弱读成「səm」",
    "a" to "「a」永远是 ə，一带而过",
    "an" to "「an」永远是「ən」，连到后面的词上",
    "that" to "「that」当连词时弱读成「ðət」，当「那个」讲时才重读",
)

private val CONTRACTIONS = mapOf(
    "gonna" to "「gonna」是 going to 合出来的，写法里根本没有这个词",
    "wanna" to "「wanna」是 want to 合出来的",
    "gotta" to "「gotta」是 got to 合出来的",
    "kinda" to "「kinda」是 kind of 合出来的",
    "sorta" to "「sorta」是 sort of 合出来的",
    "lemme" to "「lemme」是 let me 合出来的",
    "gimme" to "「gimme」是 give me 合出来的",
    "dunno" to "「dunno」是 don't know 合出来的",
    "cause" to "口语里的「'cause」就是 because，只剩后半截",
)

private val CONTRACTION_SUFFIXES = mapOf(
    "n't" to "「n't」里的 t 常常不出声，只剩一个鼻音——否定就是这么被听漏的",
    "'ll" to "「'll」只是一个很轻的 l，will 的 w 完全不在了",
    "'ve" to "「've」只剩一个 v 的摩擦音，紧贴在前一个词后面",
    "'re" to "「're」只剩一个很轻的 ə，容易被当成没有 are",
    "'d" to "「'd」只剩一个 d，would 还是 had 得靠后面的动词判断",
    "'m" to "「'm」只剩一个 m，粘在 I 后面",
)
