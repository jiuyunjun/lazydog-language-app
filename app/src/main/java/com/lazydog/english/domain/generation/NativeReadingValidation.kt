package com.lazydog.english.domain.generation

/**
 * 母语阅读的本地校验与渲染（`母语阅读DESIGN.md` §10、§24、§25、§42）。
 *
 * 这里做的是**能在本地判定的那部分**：span 能不能定位、有没有挤在一起、英语比例是不是失控、
 * 语法目标有没有超量。语义等价、地道程度这些判断留给模型侧的提示词，本地不假装能算。
 *
 * 一条原则贯穿全文件：**退回中文永远是合法结果**（§24）。所以除了"最后连一个 span 都不剩"，
 * 其余问题一律"丢掉这一条"，而不是"整篇作废"——一篇纯中文文章仍然是可读的，
 * 一篇报错的文章不是。
 */
object NativeReadingValidation {

    /** 两个陌生 span 之间至少隔这么多中文字（§10.5）。挨着出现等于连续两个坑。 */
    const val MIN_UNKNOWN_GAP_CHARS = 8

    /** 少于这么多 span 就不算一篇母语阅读了，退回让调用方重试。 */
    const val MIN_SPANS = 3

    /** 一篇里语法目标的上限（§16.1）。一个从句的认知负担比一个单词高得多。 */
    const val MAX_GRAMMAR_SPANS = 1

    /** 实际英语比例超过目标的这个倍数就往回削（§3.1：不是越多越高级）。 */
    private const val RATIO_OVERSHOOT = 1.5

    data class Outcome(
        /** null 表示通过。 */
        val failure: String?,
        val spans: List<LearningSpan> = emptyList(),
        val englishSurfaceActual: Double = 0.0,
        /** 非致命问题，随材料一起记录。 */
        val warnings: List<String> = emptyList(),
    )

    /**
     * 中文母版的校验。这一步只看"它是不是一篇能读的中文文章"，
     * 和学习目标无关——学习目标进来得太早，写作就会被它绑架（§7）。
     */
    fun validateCanonical(article: NativeCanonicalArticle): Outcome {
        if (article.title.isBlank() || article.title.length > 60) {
            return Outcome(failure = "标题缺失或过长")
        }
        val warnings = mutableListOf<String>()
        // §10.2：标题默认纯中文。缩写（GPS、AI）是中文里本来就那么说的东西，不算英语替换，
        // 所以只挡小写英文单词——挡全部字母会把"GPS 为什么会漂"这种正常标题也拦下来。
        if (LOWERCASE_WORD.containsMatchIn(article.title)) {
            warnings.add("标题里出现了英文单词，母语阅读的标题应当是纯中文")
        }
        if (article.paragraphs.size < 3) return Outcome(failure = "段落太少，凑不成一篇文章")
        if (article.paragraphs.size > 14) warnings.add("段落偏多，阅读页会显得碎")
        if (article.paragraphs.any { it.textZh.isBlank() }) return Outcome(failure = "有空段落")
        if (article.paragraphs.map { it.id }.toSet().size != article.paragraphs.size) {
            return Outcome(failure = "段落 id 有重复，替换方案会定位错")
        }
        val chars = article.paragraphs.sumOf { it.textZh.length }
        if (chars < 200) return Outcome(failure = "正文太短：$chars 字")
        if (article.readerPayoff.isBlank()) warnings.add("没有 readerPayoff，结尾不展示「值得记住的一件事」")

        val question = article.comprehension
        if (question == null) {
            warnings.add("没有理解题，读完直接进表达回忆")
        } else if (!questionIsUsable(question)) {
            warnings.add("理解题结构不完整，已丢弃")
        }
        return Outcome(failure = null, warnings = warnings)
    }

    private fun questionIsUsable(question: NativeComprehensionQuestion): Boolean {
        if (question.promptZh.isBlank()) return false
        if (question.options.size < 2 || question.options.size > 4) return false
        if (question.options.any { it.isBlank() }) return false
        if (question.options.map { it.trim() }.toSet().size != question.options.size) return false
        return question.answerIndex in question.options.indices
    }

    /** 理解题不合格时不该让整篇陪葬，交给调用方换成 null。 */
    fun usableQuestion(question: NativeComprehensionQuestion?): NativeComprehensionQuestion? =
        question?.takeIf { questionIsUsable(it) }

    /**
     * 替换方案的校验。按顺序做四件事，每一步都只丢条目不否决整篇：
     *
     * 1. 定不了位、字段不全的丢掉；
     * 2. 同一段里重叠的丢掉（后来的让位）；
     * 3. 语法目标超量的丢掉；
     * 4. 两个陌生 span 靠得太近的丢掉后面那个；
     * 5. 英语比例超出目标太多时，从优先级最低的开始削。
     */
    fun validatePlan(
        paragraphs: List<CanonicalParagraph>,
        spans: List<LearningSpan>,
        request: NativeSpanPlanRequest,
    ): Outcome {
        val warnings = mutableListOf<String>()
        val byId = paragraphs.associateBy { it.id }
        val totalChars = paragraphs.sumOf { it.textZh.length }
        if (totalChars == 0) return Outcome(failure = "中文母版是空的")

        // 1. 能不能定位。定不到位就没法在正文里换掉它，留着只会变成一条点不开的记录。
        val located = mutableListOf<Pair<LearningSpan, IntRange>>()
        spans.forEach { span ->
            val paragraph = byId[span.paragraphId]
            val source = span.sourceZh.trim()
            when {
                paragraph == null -> warnings.add("span「${span.renderedEn}」指向了不存在的段落")
                source.isEmpty() || span.renderedEn.isBlank() ->
                    warnings.add("span 缺少中文原文或英文替换，已丢弃")
                span.meaningZh.isBlank() ->
                    warnings.add("span「${span.renderedEn}」没有中文意思，点开会是空的，已丢弃")
                else -> {
                    val start = paragraph.textZh.indexOf(source)
                    if (start < 0) {
                        warnings.add("span「${span.renderedEn}」对应的中文「$source」不在原文里，已丢弃")
                    } else {
                        located.add(span.copy(sourceZh = source) to IntRange(start, start + source.length - 1))
                    }
                }
            }
        }

        // 2. 重叠。按段内位置排，后来的让位——先到的那个通常是模型更有把握的那个。
        val ordered = located.sortedWith(compareBy({ it.first.paragraphId }, { it.second.first }))
        val kept = mutableListOf<Pair<LearningSpan, IntRange>>()
        ordered.forEach { candidate ->
            val clash = kept.lastOrNull {
                it.first.paragraphId == candidate.first.paragraphId &&
                    it.second.last >= candidate.second.first
            }
            if (clash == null) {
                kept.add(candidate)
            } else {
                warnings.add("span「${candidate.first.renderedEn}」和前一个重叠，已丢弃")
            }
        }

        // 3. 语法目标数量（§16.1）。
        val grammarLimit = if (request.allowGrammar) MAX_GRAMMAR_SPANS else 0
        var grammarSeen = 0
        val afterGrammar = kept.filter { (span, _) ->
            if (!span.isGrammar) return@filter true
            grammarSeen++
            if (grammarSeen <= grammarLimit) {
                true
            } else {
                warnings.add("语法 span 超过 $grammarLimit 个，「${span.renderedEn}」已退回中文")
                false
            }
        }

        // 4. 相邻陌生项（§10.5）。一句里连着两个生词，第二个就没有可推断的语境了。
        val afterGap = mutableListOf<Pair<LearningSpan, IntRange>>()
        afterGrammar.forEach { candidate ->
            val previousUnknown = afterGap.lastOrNull {
                it.first.paragraphId == candidate.first.paragraphId && it.first.isTarget
            }
            val tooClose = candidate.first.isTarget && previousUnknown != null &&
                candidate.second.first - previousUnknown.second.last < MIN_UNKNOWN_GAP_CHARS
            if (tooClose) {
                warnings.add("两个新表达挨得太近，「${candidate.first.renderedEn}」已退回中文")
            } else {
                afterGap.add(candidate)
            }
        }

        // 5. 比例失控时往回削：先削 incidental 和 mastered——它们是"顺便"，
        //    削掉不影响这一篇要教的东西。
        val ceiling = (totalChars * request.englishAmount.surfaceRatio * RATIO_OVERSHOOT)
        var covered = afterGap.sumOf { it.first.sourceZh.length }.toDouble()
        val trimmed = afterGap.toMutableList()
        if (covered > ceiling) {
            val droppable = trimmed
                .filter { !it.first.isTarget && !it.first.isGrammar }
                .sortedByDescending { it.first.sourceZh.length }
            for (candidate in droppable) {
                if (covered <= ceiling) break
                trimmed.remove(candidate)
                covered -= candidate.first.sourceZh.length
                warnings.add("英语比例超出目标，「${candidate.first.renderedEn}」已退回中文")
            }
        }

        if (trimmed.size < MIN_SPANS) {
            return Outcome(failure = "可用的英语片段只剩 ${trimmed.size} 个，凑不成一篇母语阅读")
        }

        val ratio = covered / totalChars
        if (ratio < request.englishAmount.surfaceRatio * 0.5) {
            warnings.add("英语比例只有 ${(ratio * 100).toInt()}%，低于「${request.englishAmount.labelZh}」档")
        }
        val targets = trimmed.count { it.first.isTarget }
        if (targets == 0) warnings.add("这一篇没有新表达，全是复习")

        return Outcome(
            failure = null,
            spans = trimmed.map { it.first },
            englishSurfaceActual = ratio,
            warnings = warnings,
        )
    }

    /**
     * 把中文母版和替换方案铺成可渲染的段落（§28）。
     *
     * 只做定位和切分，不做任何判断——判断在 [validatePlan] 里已经做完了。
     * 传进来的 span 必须是它返回的那一批。
     */
    fun render(
        paragraphs: List<CanonicalParagraph>,
        spans: List<LearningSpan>,
    ): List<RenderedParagraph> {
        val grouped = spans.groupBy { it.paragraphId }
        return paragraphs.map { paragraph ->
            val here = grouped[paragraph.id].orEmpty()
                .mapNotNull { span ->
                    val start = paragraph.textZh.indexOf(span.sourceZh)
                    if (start < 0) null else span to start
                }
                .sortedBy { it.second }
            val segments = mutableListOf<ReadingSegment>()
            var cursor = 0
            here.forEach { (span, start) ->
                if (start < cursor) return@forEach
                if (start > cursor) {
                    segments.add(ReadingSegment.Zh(paragraph.textZh.substring(cursor, start)))
                }
                segments.add(ReadingSegment.Learning(span.renderedEn, span.id))
                cursor = start + span.sourceZh.length
            }
            if (cursor < paragraph.textZh.length) {
                segments.add(ReadingSegment.Zh(paragraph.textZh.substring(cursor)))
            }
            RenderedParagraph(paragraph.id, segments)
        }
    }

    /**
     * 读完之后回忆哪几个（§21.2）。优先新表达，再补该复习的；
     * 已掌握的不进来——"看到了"和"想得起来"是两件事，但已经会的东西不值得再问一遍。
     */
    fun recallCandidates(spans: List<LearningSpan>, limit: Int = 3): List<LearningSpan> {
        val targets = spans.filter { it.isTarget }
        val reviews = spans.filter { MasteryClass.normalize(it.masteryClass) == MasteryClass.Review }
        return (targets + reviews).distinctBy { it.renderedEn.lowercase() }.take(limit)
    }

    private val LOWERCASE_WORD = Regex("[a-z]{2,}")
}
