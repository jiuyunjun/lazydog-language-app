package com.lazydog.english.domain.grammar

/**
 * 语法点的大类，封闭集合。
 *
 * 参照 Cambridge English Grammar Profile 的 SuperCategory 划分（那套把 1200 多条
 * 语法能力描述按 present / past / future / modality / passives / clauses… 归类）。
 * 这里只取 A1～B2 常用的那一层，不复制它的全部条目——那份数据我们手上没有，
 * 硬抄一份编号只会得到一堆对不上号的假 id。
 *
 * 分类的用处不是给用户看，是给身份键用：光靠 [grammarPatternKey] 归一化，
 * `have/has + past participle` 和 `has been + verb-ing` 都会落在"完成"附近，
 * 加一层大类才能把它们和别的时态分开。
 */
enum class GrammarCategory(val wire: String, val labelZh: String) {
    Present("PRESENT", "现在时"),
    Past("PAST", "过去时"),
    Future("FUTURE", "将来表达"),
    Modality("MODALITY", "情态"),
    Passive("PASSIVE", "被动"),
    Question("QUESTION", "疑问"),
    Negation("NEGATION", "否定"),
    Conditional("CONDITIONAL", "条件句"),
    Clause("CLAUSE", "从句"),
    ReportedSpeech("REPORTED_SPEECH", "间接引语"),
    Noun("NOUN", "名词"),
    Determiner("DETERMINER", "冠词与限定词"),
    Pronoun("PRONOUN", "代词"),
    Adjective("ADJECTIVE", "形容词"),
    Adverb("ADVERB", "副词"),
    Preposition("PREPOSITION", "介词"),
    Conjunction("CONJUNCTION", "连词"),
    Comparison("COMPARISON", "比较级与最高级"),
    VerbPattern("VERB_PATTERN", "动词搭配结构"),
    Discourse("DISCOURSE", "语篇与衔接"),
    ;

    companion object {
        val wireList: String get() = entries.joinToString(" / ") { it.wire }

        /** 认不出来返回 null。不猜一个大类——猜错会让两个不相干的语法点撞成一条。 */
        fun parse(value: String): GrammarCategory? {
            val clean = value.trim().uppercase().replace(' ', '_').replace('-', '_')
            if (clean.isEmpty()) return null
            entries.firstOrNull { it.wire == clean }?.let { return it }
            return when (clean) {
                "PRESENT_TENSE", "PRESENTS" -> Present
                "PAST_TENSE", "PASTS" -> Past
                "FUTURES" -> Future
                "MODAL", "MODALS", "MODAL_VERB", "MODAL_VERBS" -> Modality
                "PASSIVES", "PASSIVE_VOICE" -> Passive
                "QUESTIONS", "INTERROGATIVE" -> Question
                "NEGATIVE", "NEGATIVES" -> Negation
                "CONDITIONALS", "IF_CLAUSE", "IF_CLAUSES" -> Conditional
                "CLAUSES", "RELATIVE_CLAUSE", "RELATIVE_CLAUSES", "SUBORDINATION" -> Clause
                "REPORTED", "INDIRECT_SPEECH" -> ReportedSpeech
                "NOUNS" -> Noun
                "DETERMINERS", "ARTICLE", "ARTICLES" -> Determiner
                "PRONOUNS" -> Pronoun
                "ADJECTIVES" -> Adjective
                "ADVERBS", "ADVERBIAL", "ADVERBIALS" -> Adverb
                "PREPOSITIONS" -> Preposition
                "CONJUNCTIONS" -> Conjunction
                "COMPARATIVE", "COMPARATIVES", "SUPERLATIVE", "COMPARISONS" -> Comparison
                "VERB_PATTERNS", "VERB", "VERBS", "INFINITIVE", "GERUND" -> VerbPattern
                "DISCOURSE_MARKERS", "COHESION", "LINKING" -> Discourse
                else -> null
            }
        }
    }
}

/**
 * 语法点的身份键：大类 + 归一化后的结构公式。
 *
 * 原来的去重是 `patternEn` 精确串匹配，等于没有去重——同一个现在完成时，
 * 模型这次写 `have/has + past participle`、下次写 `has/have + p.p.`、
 * 再下次写 `present perfect`，三条都能进库，然后各自排一遍复习。
 *
 * 归一化做三件事：同义写法映射（p.p. → past participle）、去掉标点和虚词、
 * token 排序后拼接（`have/has + X` 和 `has/have + X` 因此相等）。
 * 最后**只做等值判断，不做子集判断**：`will + base verb`（一般将来）是
 * `if + present simple, will + base verb`（第一条件句）的子集，但它们是两个语法点。
 */
fun grammarPointKey(category: GrammarCategory?, patternEn: String): String {
    val pattern = grammarPatternKey(patternEn)
    if (pattern.isEmpty()) return ""
    return (category?.wire ?: "UNCLASSIFIED") + "/" + pattern
}

/** 结构公式本身的归一化结果，不含大类。 */
fun grammarPatternKey(patternEn: String): String {
    val lowered = patternEn.lowercase().trim()
    if (lowered.isEmpty()) return ""
    // 整条就是一个常见时态名的，直接映射到它的结构公式上：
    // "present perfect" 和 "have/has + past participle" 说的是同一件事。
    val named = lowered.replace(Regex("""\s+"""), " ")
        .removePrefix("the ")
        .removeSuffix(" tense")
        .removeSuffix(" form")
        .trim()
    NAMED_POINTS[named]?.let { return it }

    var text = " " + lowered.replace(Regex("""[+/,.()\[\]{}"']"""), " ").replace('-', ' ') + " "
    for ((from, to) in PHRASE_ALIASES) {
        text = text.replace(from, " $to ")
    }
    val tokens = text.split(Regex("""\s+"""))
        .map { TOKEN_ALIASES[it] ?: it }
        .filter { it.isNotBlank() && it !in STOP_TOKENS }
        .distinct()
        .sorted()
    return tokens.joinToString("+")
}

/** 整条即为公式的常见说法。写成结果 key，保证和对应的结构公式撞在一起。 */
private val NAMED_POINTS: Map<String, String> = mapOf(
    "present perfect" to "have+pastparticiple",
    "present perfect simple" to "have+pastparticiple",
    "现在完成时" to "have+pastparticiple",
    "present perfect continuous" to "been+have+ving",
    "present perfect progressive" to "been+have+ving",
    "现在完成进行时" to "been+have+ving",
    "past simple" to "pastsimple",
    "simple past" to "pastsimple",
    "一般过去时" to "pastsimple",
    // 大类已经把过去和现在分开了，所以这里和现在进行时用同一个公式 key：
    // "was/were + verb-ing" 归一化之后本来就等于 "be + ving"。
    "past continuous" to "be+ving",
    "过去进行时" to "be+ving",
    "past perfect" to "had+pastparticiple",
    "过去完成时" to "had+pastparticiple",
    "present simple" to "presentsimple",
    "simple present" to "presentsimple",
    "一般现在时" to "presentsimple",
    "present continuous" to "be+ving",
    "现在进行时" to "be+ving",
    "future simple" to "baseverb+will",
    "一般将来时" to "baseverb+will",
    "passive voice" to "be+pastparticiple",
    "被动语态" to "be+pastparticiple",
)

/**
 * 多词写法的统一。按长的先替换，否则 "past participle" 会被 "past" 先啃掉半截。
 */
private val PHRASE_ALIASES: List<Pair<String, String>> = listOf(
    " past participle " to " pastparticiple ",
    " pp " to " pastparticiple ",
    " p p " to " pastparticiple ",
    " infinitive without to " to " baseverb ",
    " bare infinitive " to " baseverb ",
    " base form " to " baseverb ",
    " base verb " to " baseverb ",
    " to infinitive " to " toinfinitive ",
    " ing form " to " ving ",
    " verb ing " to " ving ",
    " v ing " to " ving ",
    " gerund " to " ving ",
    " present simple " to " presentsimple ",
    " simple present " to " presentsimple ",
    " past simple " to " pastsimple ",
    " simple past " to " pastsimple ",
    " somebody " to " sb ",
    " someone " to " sb ",
    " something " to " sth ",
)

/** 单个 token 的同义写法。 */
private val TOKEN_ALIASES: Map<String, String> = mapOf(
    "has" to "have",
    "am" to "be",
    "is" to "be",
    "are" to "be",
    "was" to "be",
    "were" to "be",
    "does" to "do",
    "did" to "do",
    // 结构公式里孤零零的 "verb" 指的就是原形，和 "base verb" 是一回事。
    "verb" to "baseverb",
    "vb" to "baseverb",
    "v" to "baseverb",
    "adjective" to "adj",
    "adverb" to "adv",
    "noun" to "n",
    "participle" to "pastparticiple",
)

/** 归一化时丢掉的词：它们只是把公式读顺，不承载结构信息。 */
private val STOP_TOKENS: Set<String> = setOf(
    "the", "a", "an", "of", "to", "for", "with", "and", "or",
    "form", "forms", "tense", "tenses", "structure", "pattern", "english", "usage",
)
