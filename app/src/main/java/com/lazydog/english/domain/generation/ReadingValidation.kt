package com.lazydog.english.domain.generation

/**
 * 阅读生成的本地校验（AI_CONTRACTS.md §5）：
 * 指定复习词必须真的出现在正文（允许词形变化），新词不超上限，
 * 语法例句必须是正文子串，题目结构完整。不合格直接拒绝，不入库。
 */
object ReadingValidation {

    data class Outcome(
        /** null 表示通过。 */
        val failure: String?,
        /** 非致命问题，随材料记录（validationNotes）。 */
        val warnings: List<String>,
    )

    fun validate(reading: GeneratedReading, request: ReadingGenerationRequest): Outcome {
        val warnings = mutableListOf<String>()

        if (reading.title.isBlank() || reading.title.length > 80) {
            return fail("标题缺失或过长")
        }
        val wordCount = Regex("[A-Za-z'\\-]+").findAll(reading.body).count()
        if (wordCount < MIN_BODY_WORDS) return fail("正文太短（$wordCount 词）")
        if (wordCount > request.targetLength * 3) return fail("正文太长（$wordCount 词）")

        // 指定复习词必须都在正文里。
        val missing = request.reviewVocabulary.filterNot {
            ContentValidation.exampleContainsTerm(reading.body, it)
        }
        if (missing.isNotEmpty()) {
            return fail("这些复习词没出现在正文里：${missing.joinToString(", ")}")
        }

        // 目标词表自身的完整性。
        val newWords = reading.targetVocabulary.filter { it.role == "new" }
        if (newWords.size > request.maxNewWords) {
            return fail("新词超过上限：${newWords.size} > ${request.maxNewWords}")
        }
        for (target in reading.targetVocabulary) {
            if (target.role !in setOf("review", "new")) return fail("目标词 ${target.term} 的 role 不合法")
            if (target.meaningZh.isBlank()) return fail("目标词 ${target.term} 缺少释义")
            if (!ContentValidation.exampleContainsTerm(reading.body, target.term)) {
                return fail("目标词 ${target.term} 没出现在正文里")
            }
            if (target.exampleFromText.isNotBlank() &&
                !bodyContainsNormalized(reading.body, target.exampleFromText)
            ) {
                warnings.add("目标词 ${target.term} 的引文和正文对不上")
            }
        }

        // 语法例句必须是正文子串（按空白归一化）。
        for (grammar in reading.targetGrammar) {
            if (grammar.exampleFromText.isBlank()) {
                warnings.add("语法 ${grammar.name} 没给正文例句")
            } else if (!bodyContainsNormalized(reading.body, grammar.exampleFromText)) {
                return fail("语法 ${grammar.name} 的例句不是正文内容")
            }
        }

        // 理解题结构。
        if (reading.comprehensionQuestions.isEmpty()) return fail("没有理解题")
        if (reading.comprehensionQuestions.size > MAX_QUESTIONS) return fail("理解题太多")
        for ((index, question) in reading.comprehensionQuestions.withIndex()) {
            val label = "第 ${index + 1} 题"
            if (question.promptZh.isBlank()) return fail("$label 缺少题干")
            if (question.options.size !in 2..5) return fail("$label 选项数量不对")
            if (question.options.toSet().size != question.options.size) return fail("$label 选项重复")
            if (question.answerIndex !in question.options.indices) return fail("$label 答案索引越界")
            if (question.explanationZh.isBlank()) warnings.add("$label 没有解析")
        }

        return Outcome(failure = null, warnings = warnings)
    }

    private fun fail(reason: String) = Outcome(failure = reason, warnings = emptyList())

    /** 忽略连续空白差异后，正文是否包含该片段。 */
    fun bodyContainsNormalized(body: String, fragment: String): Boolean {
        val normalizedBody = body.replace(Regex("\\s+"), " ").trim()
        val normalizedFragment = fragment.replace(Regex("\\s+"), " ").trim()
        return normalizedFragment.isNotEmpty() && normalizedBody.contains(normalizedFragment)
    }

    const val MIN_BODY_WORDS = 40
    const val MAX_QUESTIONS = 5
}
