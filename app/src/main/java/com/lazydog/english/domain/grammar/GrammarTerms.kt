package com.lazydog.english.domain.grammar

/**
 * 结构公式里的英语语法术语 → 中文。
 *
 * 公式本身必须是英文（`patternEn` 是可套用的模板，翻成中文就不能往句子里填了），
 * 但 `will + base verb`、`have/has + past participle` 这些词对着自学的人是天书：
 * 卡片主标题是他第一眼看到的东西，看不懂就等于这一屏什么都没讲。
 *
 * 所以公式照旧摆英文，下面补一行中文对照，再把用到的术语逐条解释一次
 * ——解释里带例子，"过去分词"这四个字本身也是术语。
 */
data class GrammarTerm(
    val en: String,
    val zh: String,
    /** 一句话说清它长什么样，尽量给形式而不是定义。 */
    val noteZh: String,
)

/**
 * 术语表。长的排在前面：替换和匹配都按这个顺序走，
 * 否则 `past participle` 会被 `past simple` 里的 `past` 先啃掉半截。
 */
private val GRAMMAR_TERMS: List<GrammarTerm> = listOf(
    GrammarTerm("present perfect continuous", "现在完成进行时", "have/has been + doing，强调一直做到现在"),
    GrammarTerm("present perfect", "现在完成时", "have/has + 过去分词，如 have gone"),
    GrammarTerm("past perfect", "过去完成时", "had + 过去分词，表示比过去还早的事"),
    GrammarTerm("present continuous", "现在进行时", "am/is/are + doing"),
    GrammarTerm("present progressive", "现在进行时", "am/is/are + doing"),
    GrammarTerm("past continuous", "过去进行时", "was/were + doing"),
    GrammarTerm("past progressive", "过去进行时", "was/were + doing"),
    GrammarTerm("present simple", "一般现在时", "动词原形，第三人称单数加 -s：go / goes"),
    GrammarTerm("simple present", "一般现在时", "动词原形，第三人称单数加 -s：go / goes"),
    GrammarTerm("past simple", "一般过去时", "动词的过去式：went、ate、worked"),
    GrammarTerm("simple past", "一般过去时", "动词的过去式：went、ate、worked"),
    GrammarTerm("future simple", "一般将来时", "will + 动词原形"),
    GrammarTerm("passive voice", "被动语态", "be + 过去分词，如 was written"),
    GrammarTerm("past participle", "过去分词", "动词的第三形态：gone、eaten、written、worked"),
    GrammarTerm("present participle", "现在分词", "动词加 -ing：going、eating"),
    GrammarTerm("bare infinitive", "动词原形", "不带 to 的原形：go、eat、be"),
    GrammarTerm("infinitive without to", "动词原形", "不带 to 的原形：go、eat、be"),
    GrammarTerm("to infinitive", "带 to 的不定式", "to + 动词原形：to go、to eat"),
    GrammarTerm("to-infinitive", "带 to 的不定式", "to + 动词原形：to go、to eat"),
    GrammarTerm("base verb", "动词原形", "字典里那个形态：go、eat、be"),
    GrammarTerm("base form", "动词原形", "字典里那个形态：go、eat、be"),
    GrammarTerm("infinitive", "动词不定式", "to + 动词原形，有时省掉 to"),
    GrammarTerm("gerund", "动名词", "动词加 -ing 当名词用：swimming is fun"),
    GrammarTerm("ing form", "动词的 -ing 形式", "going、eating"),
    GrammarTerm("verb-ing", "动词的 -ing 形式", "going、eating"),
    GrammarTerm("modal verb", "情态动词", "can、could、must、should、may、might、will、would"),
    GrammarTerm("auxiliary verb", "助动词", "do/does/did、be、have，本身不表意思，帮忙搭结构"),
    GrammarTerm("relative clause", "定语从句", "跟在名词后面说明它：the man who called me"),
    GrammarTerm("main clause", "主句", "能单独成句的那半句"),
    GrammarTerm("subordinate clause", "从句", "不能单独成句，要挂在主句上"),
    GrammarTerm("comparative", "比较级", "-er 或 more：faster、more useful"),
    GrammarTerm("superlative", "最高级", "-est 或 most：fastest、most useful"),
    GrammarTerm("uncountable noun", "不可数名词", "不能加 s、不能说 a：water、advice"),
    GrammarTerm("countable noun", "可数名词", "能加 s、能说 a：a book / two books"),
    GrammarTerm("determiner", "限定词", "放在名词前面的 the、a、this、some、my"),
    GrammarTerm("preposition", "介词", "in、on、at、for、with"),
    GrammarTerm("conjunction", "连词", "and、but、because、although"),
    GrammarTerm("adjective", "形容词", "描述名词：big、useful"),
    GrammarTerm("adverb", "副词", "描述动作：quickly、always"),
    GrammarTerm("article", "冠词", "a、an、the"),
    GrammarTerm("pronoun", "代词", "I、you、it、them"),
    GrammarTerm("clause", "分句", "有主语和动词的一段，如 if it rains"),
    GrammarTerm("subject", "主语", "动作的发出者"),
    GrammarTerm("object", "宾语", "动作的承受者"),
    GrammarTerm("noun", "名词", "人、事、物"),
    GrammarTerm("verb", "动词", "表示动作或状态"),
    GrammarTerm("plural", "复数", "两个以上，通常加 -s"),
    GrammarTerm("singular", "单数", "只有一个"),
    GrammarTerm("sth", "某物", "填一个具体的东西进去"),
    GrammarTerm("sb", "某人", "填一个人进去"),
)

/** 匹配整词的正则，`-` 和空格都当分隔符，这样 `past-participle` 也认得出来。 */
private val TERM_PATTERNS: List<Pair<Regex, GrammarTerm>> = GRAMMAR_TERMS.map { term ->
    val loose = term.en.split(' ', '-').joinToString("[ \\-]") { Regex.escape(it) }
    Regex("""(?<![A-Za-z])$loose(?![A-Za-z])""", RegexOption.IGNORE_CASE) to term
}

/**
 * 公式的中文对照，如 `have/has + past participle` → `have/has + 过去分词`。
 *
 * 一个术语都没认出来时返回空串：那说明公式本来就是 `be going to + X` 这种大白话，
 * 再摆一行一模一样的东西只是噪音。
 */
fun grammarPatternZh(patternEn: String): String {
    var text = patternEn
    var hit = false
    for ((pattern, term) in TERM_PATTERNS) {
        if (pattern.containsMatchIn(text)) {
            hit = true
            text = pattern.replace(text, term.zh)
        }
    }
    return if (hit && text != patternEn.trim()) text.trim() else ""
}

/**
 * 一段文字里出现的术语，按术语表顺序去重。
 *
 * 传公式就得到"这条公式要看懂得先知道哪几个词"；讲解正文也能传进来——
 * 模型偶尔会在中文讲解里夹一句 past participle，那一句同样需要注解。
 */
fun grammarTermsIn(vararg texts: String): List<GrammarTerm> {
    val found = LinkedHashMap<String, GrammarTerm>()
    for ((pattern, term) in TERM_PATTERNS) {
        if (texts.any { pattern.containsMatchIn(it) }) found.putIfAbsent(term.zh, term)
    }
    return found.values.toList()
}
