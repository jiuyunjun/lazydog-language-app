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
                word.collocations.any { it.isBlank() || it.length > 60 } -> "搭配缺失或过长"
                else -> null
            }
            if (reason == null) valid.add(word.copy(term = term)) else dropped.add("${term.ifBlank { "(空)" }}：$reason")
            if (valid.size == maxCount) break
        }
        return ValidatedWords(valid, dropped)
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
        val known = knownGrammar.map { it.trim() }.toSet()
        return when {
            lesson.name.isBlank() || lesson.name.length > 60 -> "语法点名称缺失或过长"
            lesson.name.trim() in known -> "这个语法点已经学过"
            lesson.explanationZh.isBlank() || lesson.explanationZh.length > 500 -> "讲解缺失或过长"
            lesson.goodExampleEn.isBlank() || lesson.goodExampleEn.length > 200 -> "正确例句缺失或过长"
            lesson.goodExampleZh.isBlank() -> "正确例句缺少译文"
            else -> null
        }
    }
}
