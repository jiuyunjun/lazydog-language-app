package com.lazydog.english.domain.generation

/**
 * AI 输出的本地业务校验（AI_CONTRACTS.md §5 精神）：
 * 字段完整、长度受控、例句真的包含目标词、避开已知词。
 * 纯函数，便于单测。
 */
object ContentValidation {

    private val termPattern = Regex("^[A-Za-z][A-Za-z '\\-]{0,39}$")

    data class ValidatedWords(
        val valid: List<GeneratedWord>,
        val droppedNotes: List<String>,
    )

    /** 逐条校验：无效的丢弃并记录原因；一个都不剩才算整体失败（由调用方判断）。 */
    fun validateNewWords(
        words: List<GeneratedWord>,
        maxCount: Int,
        knownTerms: Collection<String>,
    ): ValidatedWords {
        val knownLower = knownTerms.map { it.trim().lowercase() }.toSet()
        val valid = mutableListOf<GeneratedWord>()
        val dropped = mutableListOf<String>()
        val seen = mutableSetOf<String>()

        for (word in words) {
            val term = word.term.trim()
            val reason = when {
                !termPattern.matches(term) -> "词形不合法"
                term.lowercase() in knownLower -> "已在知识库里"
                !seen.add(term.lowercase()) -> "本批重复"
                word.meaningZh.isBlank() || word.meaningZh.length > 120 -> "释义缺失或过长"
                word.exampleEn.isBlank() || word.exampleEn.length > 200 -> "例句缺失或过长"
                word.exampleZh.isBlank() || word.exampleZh.length > 200 -> "例句译文缺失或过长"
                !exampleContainsTerm(word.exampleEn, term) -> "例句里没有这个词"
                word.pos.isBlank() || word.pos.length > 20 -> "词性缺失或过长"
                word.collocations.isEmpty() || word.collocations.size > 2 -> "搭配数量应该是 1~2 个"
                // 翻译允许空：模型偶尔只给英文，界面上那条还能点开现翻，不值得把整个词丢掉。
                word.collocations.any { it.en.isBlank() || it.en.length > 60 || it.zh.length > 60 } ->
                    "搭配缺失或过长"
                word.memoryHintZh.isBlank() || word.memoryHintZh.length > 120 -> "记忆方法缺失或过长"
                // 词块必须能原样拼回这个词，否则挖空题会挖出一个不存在的位置。
                word.chunks.size < 2 -> "词块至少要拆成 2 块"
                word.chunks.joinToString("").lowercase() != term.lowercase() -> "词块拼起来和原词对不上"
                // 易错段要能在词里定位，不然"这里最容易错"指不到地方。
                word.trickyPart.isBlank() -> "缺少易错部分"
                !term.lowercase().contains(word.trickyPart.trim().lowercase()) -> "易错部分不是这个词的一段"
                word.misspellings.size < 3 -> "常见错拼至少 3 个（四选一要 3 个干扰项）"
                word.misspellings.any { it.isBlank() || it.trim().lowercase() == term.lowercase() } ->
                    "常见错拼里混进了正确拼写或空值"
                word.misspellings.map { it.trim().lowercase() }.distinct().size < word.misspellings.size ->
                    "常见错拼有重复"
                else -> null
            }
            if (reason == null) {
                // 空洞的记忆方法就地清掉，但不因此丢掉整个词：词本身还是能学的，
                // 提示留空后学习卡上出现的是「生成记忆提示」，那条按这个词单独生成，质量高得多。
                val hollow = isHollowMemoryHint(word.memoryHintZh)
                if (hollow) dropped.add("$term：记忆方法是句空话，已清掉")
                valid.add(word.copy(term = term, memoryHintZh = if (hollow) "" else word.memoryHintZh.trim()))
            } else {
                dropped.add("${term.ifBlank { "(空)" }}：$reason")
            }
            if (valid.size == maxCount) break
        }
        return ValidatedWords(valid, dropped)
    }

    /** 放到哪个词上都成立的话，等于没给提示。 */
    private val hollowHintPhrases = listOf(
        "多读几遍", "多念几遍", "反复朗读", "反复记忆", "结合例句多记", "多加练习",
    )

    /**
     * 这条记忆方法是不是等于什么都没说（词汇记忆提示DESIGN.md §10：宁缺毋滥）。
     *
     * 判得很窄——只挑明显的空话和短到装不下一个记忆钩子的句子。
     * 「这条联想到底有没有用」本地判不了，那一层由提示词里的反例和自检要求管；
     * 这里宁可漏掉几条平庸的，也不能误杀真正拆对了词根的那种。
     */
    fun isHollowMemoryHint(hint: String): Boolean {
        val text = hint.trim()
        return text.length < 12 || hollowHintPhrases.any { it in text }
    }

    /**
     * 例句是否包含目标词：忽略大小写，允许常见词尾变化
     * （复数 s/es、过去式 d/ed、进行时 ing 含去 e 加 ing）。
     */
    fun exampleContainsTerm(example: String, term: String): Boolean {
        val words = Regex("[A-Za-z'\\-]+").findAll(example.lowercase()).map { it.value }.toSet()
        val t = term.lowercase()
        val stems = buildSet {
            add(t)
            add(t + "s"); add(t + "es"); add(t + "d"); add(t + "ed"); add(t + "ing")
            if (t.endsWith("e")) add(t.dropLast(1) + "ing")
            if (t.endsWith("y")) add(t.dropLast(1) + "ies")
        }
        return words.any { it in stems }
    }

    /** 语法讲解整体校验：关键字段缺一不可。@return 失败原因，null 表示通过。 */
    fun validateGrammarLesson(lesson: GeneratedGrammarLesson, knownGrammar: Collection<String>): String? {
        val known = knownGrammar.map { normalizeGrammarPattern(it) }.toSet()
        return when {
            lesson.patternEn.isBlank() || lesson.patternEn.length > 80 -> "语法结构缺失或过长"
            !lesson.patternEn.any { it in 'A'..'Z' || it in 'a'..'z' } -> "语法结构必须包含英文形式"
            Regex("[\\u4E00-\\u9FFF]").containsMatchIn(lesson.patternEn) -> "语法结构不能混入中文说明"
            normalizeGrammarPattern(lesson.patternEn) in known -> "这个语法点已经学过"
            lesson.labelZh.isBlank() || lesson.labelZh.length > 40 -> "中文语法标签缺失或过长"
            lesson.summaryZh.isBlank() || lesson.summaryZh.length > 36 -> "一句话用途缺失或过长"
            lesson.explanationZh.isBlank() || lesson.explanationZh.length > 500 -> "讲解缺失或过长"
            lesson.goodExampleEn.isBlank() || lesson.goodExampleEn.length > 200 -> "正确例句缺失或过长"
            lesson.goodExampleZh.isBlank() -> "正确例句缺少译文"
            else -> null
        }
    }

    private fun normalizeGrammarPattern(value: String): String =
        value.lowercase().replace(Regex("\\s+"), " ").trim()
}
