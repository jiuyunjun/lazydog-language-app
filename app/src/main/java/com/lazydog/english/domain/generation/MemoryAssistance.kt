package com.lazydog.english.domain.generation

import kotlinx.serialization.Serializable

/**
 * 记忆策略（词汇记忆提示DESIGN.md §3）。
 *
 * 存在的理由是 §2.1：不同的词最值得记的东西不一样，unhappy 该拆构词，receive 该看拼写，
 * bizarre 只能靠画面。所以先让模型判断"这个词最值得记什么"，再只写那一两种；
 * 每个词都生成全部七类，产出的是一堆低价值信息。
 *
 * AI 只能从这个固定集合里选，认不出的标签本地归 [Context]——和语法错题标签一个处理方式。
 */
@Serializable
enum class MemoryType(val labelZh: String, val noteZh: String) {
    Morphology("构词", "拆前缀词根后缀"),
    Context("场景", "什么时候会用上"),
    Orthography("词形", "拼写结构和易错段"),
    Pronunciation("发音", "音节和重音"),
    Contrast("对比", "和谁容易混"),
    Collocation("搭配", "跟谁一起出现"),
    VisualAssociation("联想", "一眼能成像的画面"),
    ;

    companion object {
        /** 大小写、下划线和中间的空格都容忍；认不出的归 [Context]，不让一个标签拖垮整条提示。 */
        fun normalize(raw: String): MemoryType {
            val key = raw.trim().lowercase().replace("_", "").replace(" ", "")
            return entries.firstOrNull { it.name.lowercase() == key } ?: Context
        }

        /** 认不出就返回 null，用于"次要策略可以没有"这种场合。 */
        fun normalizeOrNull(raw: String): MemoryType? {
            if (raw.isBlank()) return null
            val key = raw.trim().lowercase().replace("_", "").replace(" ", "")
            return entries.firstOrNull { it.name.lowercase() == key }
        }
    }
}

/** 发音提示（§3.4）。中文谐音不在这里——谐音只有确实自然时才写进 [MemoryAssistance.memoryHookZh]。 */
@Serializable
data class MemoryPronunciation(
    val syllables: List<String> = emptyList(),
    /** 重音落在第几个音节，从 1 数。0 表示没给或给的位置不在音节范围内。 */
    val stress: Int = 0,
    val noteZh: String = "",
) {
    val isEmpty: Boolean get() = syllables.isEmpty() && noteZh.isBlank()
}

/** 易混词（§3.5）：一个词只说一个关键区别，凑数量的条目在校验时丢掉。 */
@Serializable
data class MemoryConfusion(val word: String, val differenceZh: String)

/**
 * 一个词的记忆提示（词汇记忆提示DESIGN.md §6 的结构化输出）。
 *
 * 除 [coreMeaningZh]、[primaryType]、[memoryHookZh] 外的字段都可以是空的，这是设计要求
 * 而不是"没生成好"：§10 的原则是宁缺毋滥，没有好的联想时不生成，优于生成牵强内容。
 * UI 按"空就不显示"处理。
 */
@Serializable
data class MemoryAssistance(
    val term: String,
    /** 最核心最常用的那个意思，一句话，不罗列次要释义。 */
    val coreMeaningZh: String,
    val primaryType: MemoryType,
    val secondaryType: MemoryType? = null,
    /** §9：5~20 字、只说一个记忆关系、看到就能指回目标词。首屏就显示它。 */
    val memoryHookZh: String,
    /** 构词拆解，如 "un + happy = 不 + 开心"。拆不出就留空——§3.1 明确禁止编造词根。 */
    val morphologyZh: String = "",
    /** 最容易写错的一段，是 [term] 里连续的一段。和拼写引擎的 Weak Segment 是同一个东西（§11）。 */
    val weakSegment: String = "",
    /** 真人常见的错拼形式。 */
    val commonErrors: List<String> = emptyList(),
    val pronunciation: MemoryPronunciation = MemoryPronunciation(),
    /** 1~2 句、具体、能瞬间成像的场景或画面（§3.7）。 */
    val visualAssociationZh: String = "",
    val confusions: List<MemoryConfusion> = emptyList(),
    val collocations: List<String> = emptyList(),
    val exampleEn: String = "",
    /** §9 的检索练习用：一个不直接暴露答案的问题。 */
    val recallQuestionZh: String = "",
) {
    /** 拆出词条级那三样，给同一个词条的其它词义复用（§16.2）。 */
    fun wordLevel() = MemoryWordLevel(morphologyZh, weakSegment, commonErrors, pronunciation)

    /** 用词条级材料覆盖这一条里对应的三样，保证同一个词条的各词义说法一致。 */
    fun withWordLevel(shared: MemoryWordLevel) = copy(
        morphologyZh = shared.morphologyZh,
        weakSegment = shared.weakSegment,
        commonErrors = shared.commonErrors,
        pronunciation = shared.pronunciation,
    )

    /** 首屏之外还有没有东西可展开（§7：先只显示词/意思/钩子/策略）。 */
    val hasDetails: Boolean
        get() = morphologyZh.isNotBlank() || weakSegment.isNotBlank() || commonErrors.isNotEmpty() ||
            !pronunciation.isEmpty || visualAssociationZh.isNotBlank() || confusions.isNotEmpty() ||
            collocations.isNotEmpty() || exampleEn.isNotBlank() || recallQuestionZh.isNotBlank()
}

/**
 * 生成一条记忆提示的请求。
 *
 * [weakSegments] / [observedErrors] 是 §11 说的闭环：用户真写错过的地方进提示词，
 * 生成的提示才是针对这个人的，而不是对着词典重写一遍。
 *
 * [avoidHookZh] / [avoidTypes] 服务"再来一条"：重试不该把同一条提示原样再生成一遍，
 * 上一条既然没帮上忙，就该换个策略。
 */
data class MemoryAssistanceRequest(
    val term: String,
    /**
     * 这个词条上已经生成过的词条级材料（构词 / 词形 / 发音）。
     *
     * 有的话就不再让模型重写一遍：这三样属于词形，和是哪个意思无关，
     * 一个词条只该有一份（`词汇记忆提示DESIGN.md` §16.2）。
     * `run` 的五个词义各生成一遍构词，既费 token，五份之间还可能互相矛盾。
     */
    val sharedWordLevel: MemoryWordLevel? = null,
    val meaningZh: String = "",
    val pos: String = "",
    val learnerLevel: String,
    /** 拼写引擎攒下的薄弱片段，按错得多的排前面。 */
    val weakSegments: List<String> = emptyList(),
    /** 这个人真写出来过的错误形式。 */
    val observedErrors: List<String> = emptyList(),
    /** §12：这个词现在哪一维最弱，决定重点讲什么。空则由模型自己判断。 */
    val focusZh: String = "",
    val avoidHookZh: String = "",
    val avoidTypes: List<MemoryType> = emptyList(),
)

/**
 * 一个词条的词条级记忆材料：构词、易错段、常见错拼、发音。
 *
 * 这三类属于词形本身，所有词义共用（§16.2）；场景、对比、搭配、联想属于意思，
 * 每个词义各有一份。
 */
data class MemoryWordLevel(
    val morphologyZh: String = "",
    val weakSegment: String = "",
    val commonErrors: List<String> = emptyList(),
    val pronunciation: MemoryPronunciation = MemoryPronunciation(),
) {
    val isEmpty: Boolean
        get() = morphologyZh.isBlank() && weakSegment.isBlank() &&
            commonErrors.isEmpty() && pronunciation.isEmpty
}

/**
 * 记忆提示的本地校验（§10 生成质量过滤）。
 *
 * 和别处的校验不一样的地方：这里绝大多数问题是**删掉那一项**而不是整条失败。
 * 设计里写死了"宁缺毋滥"——牵强的联想、凑数的易混词、指不到位置的易错段，
 * 留着比没有更糟；但它们不该连累那条真正有用的记忆钩子。
 *
 * 真正会让整条失败的只有三样：核心意思、记忆钩子、以及钩子长到没法在几秒内看完。
 */
object MemoryAssistanceValidation {

    /** §9 说好控制在 5~20 字。留一点余量，超过这个数才算"太长，重新生成"。 */
    const val MAX_HOOK_LENGTH = 30
    const val MIN_HOOK_LENGTH = 4
    const val MAX_CONFUSIONS = 3
    const val MAX_COLLOCATIONS = 3

    data class Cleaned(val value: MemoryAssistance, val droppedNotes: List<String>)

    /** 逐项过滤：留下站得住的，其余删掉并记原因。返回的内容可以直接展示和入库。 */
    fun clean(raw: MemoryAssistance): Cleaned {
        val dropped = mutableListOf<String>()
        val term = raw.term.trim()
        val lower = term.lowercase()

        // 易错段必须能在词里定位，否则"这里最容易错"指不到地方——和拼写事实同一条规矩。
        val weak = raw.weakSegment.trim()
        val weakOk = weak.isNotEmpty() && lower.contains(weak.lowercase().replace("-", ""))
        if (weak.isNotEmpty() && !weakOk) dropped.add("易错段不是这个词的一段")

        // 错拼里混进正确拼写，那道提示就等于把答案摆出来了。
        val errors = raw.commonErrors.map { it.trim() }
            .filter { it.isNotEmpty() && !it.equals(term, ignoreCase = true) }
            .distinctBy { it.lowercase() }
            .take(MAX_CONFUSIONS)
        if (errors.size < raw.commonErrors.size) dropped.add("常见错拼里有空值或正确拼写")

        // 音节拼起来必须还是这个词，拆错了的音节表会把重音也讲错。
        val syllables = raw.pronunciation.syllables.map { it.trim() }.filter { it.isNotEmpty() }
        val syllablesOk = syllables.size >= 2 &&
            syllables.joinToString("").lowercase().replace("-", "") == lower.replace("-", "").replace(" ", "")
        if (syllables.isNotEmpty() && !syllablesOk) dropped.add("音节拼起来和原词对不上")
        val stress = raw.pronunciation.stress.takeIf { syllablesOk && it in 1..syllables.size } ?: 0
        val pronunciation = MemoryPronunciation(
            syllables = if (syllablesOk) syllables else emptyList(),
            stress = stress,
            noteZh = raw.pronunciation.noteZh.trim().take(60),
        )

        // 易混词：最多 3 个，每个必须真说出一个区别，也不能把目标词自己列进去。
        val confusions = raw.confusions
            .map { MemoryConfusion(it.word.trim(), it.differenceZh.trim()) }
            .filter { it.word.isNotEmpty() && !it.word.equals(term, ignoreCase = true) && it.differenceZh.isNotEmpty() }
            .distinctBy { it.word.lowercase() }
            .take(MAX_CONFUSIONS)
        if (confusions.size < raw.confusions.size) dropped.add("易混词缺少区别说明或与目标词重复")

        val collocations = raw.collocations.map { it.trim() }
            .filter { it.isNotEmpty() && it.length <= 60 }
            .distinctBy { it.lowercase() }
            .take(MAX_COLLOCATIONS)

        // 例句不含目标词就不是这个词的语境，展示出来只会误导。
        val example = raw.exampleEn.trim()
            .takeIf { it.isNotEmpty() && it.length <= 200 && ContentValidation.exampleContainsTerm(it, term) }
            .orEmpty()
        if (raw.exampleEn.isNotBlank() && example.isEmpty()) dropped.add("例句里没有这个词或过长")

        // 联想要短。一长就不是"瞬间成像"，是又一段要背的东西（§3.7）。
        val visual = raw.visualAssociationZh.trim()
            .takeIf { it.isNotEmpty() && it.length <= 80 }
            .orEmpty()
        if (raw.visualAssociationZh.isNotBlank() && visual.isEmpty()) dropped.add("视觉联想过长")

        // 构词拆解要真拆出了这个词的字母，只写一句中文说明的算没拆出来。
        val morphology = raw.morphologyZh.trim()
            .takeIf { it.isNotEmpty() && it.length <= 120 && it.any { c -> c.isLetter() && c.code < 128 } }
            .orEmpty()
        if (raw.morphologyZh.isNotBlank() && morphology.isEmpty()) dropped.add("构词提示里没有可对照的英文形式")

        return Cleaned(
            value = raw.copy(
                term = term,
                coreMeaningZh = raw.coreMeaningZh.trim(),
                memoryHookZh = raw.memoryHookZh.trim(),
                morphologyZh = morphology,
                weakSegment = if (weakOk) weak else "",
                commonErrors = errors,
                pronunciation = pronunciation,
                visualAssociationZh = visual,
                confusions = confusions,
                collocations = collocations,
                exampleEn = example,
                recallQuestionZh = raw.recallQuestionZh.trim().take(60),
            ),
            droppedNotes = dropped,
        )
    }

    /** 整条能不能用。@return 失败原因，null 表示通过。 */
    fun validate(value: MemoryAssistance, expectedTerm: String): String? = when {
        !value.term.equals(expectedTerm.trim(), ignoreCase = true) -> "返回的不是请求的那个词"
        value.coreMeaningZh.isBlank() || value.coreMeaningZh.length > 60 -> "核心意思缺失或过长"
        value.memoryHookZh.isBlank() -> "没给记忆钩子"
        value.memoryHookZh.length < MIN_HOOK_LENGTH -> "记忆钩子太短，指不回这个词"
        value.memoryHookZh.length > MAX_HOOK_LENGTH -> "记忆钩子超过 $MAX_HOOK_LENGTH 字，几秒内看不完"
        else -> null
    }
}
