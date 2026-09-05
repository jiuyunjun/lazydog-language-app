package com.lazydog.english.domain.generation

/**
 * AI 内容生成的领域接口（AI_CONTRACTS.md §2）。
 * 领域层只依赖它；具体服务商实现在 core/ai。
 * 实现必须返回已通过本地校验的内容——不合格的返回 Failure，不得入库。
 */
interface LearningContentGenerator {

    /** 生成场景简报、对手和 4～6 条可判定目标。 */
    suspend fun generateScenario(
        request: com.lazydog.english.domain.scenario.ScenarioGenerationRequest,
        onStage: ((GenerationStage) -> Unit)? = null,
        /** 已经写出来的正文，用于等待期间铺内容而不是只显示一个字数（见 JsonStream）。 */
        onPartialText: ((String) -> Unit)? = null,
    ): GenerationResult<com.lazydog.english.domain.scenario.ScenarioBrief>

    /** 只扮演对手并推进对话；不得纠错或评价用户。 */
    suspend fun generateScenarioTurn(
        request: com.lazydog.english.domain.scenario.ScenarioTurnRequest,
        onStage: ((GenerationStage) -> Unit)? = null,
        /** 已经写出来的正文，用于等待期间铺内容而不是只显示一个字数（见 JsonStream）。 */
        onPartialText: ((String) -> Unit)? = null,
    ): GenerationResult<com.lazydog.english.domain.scenario.ScenarioTurn>

    /** 与对话生成分开的目标判定调用；只返回命中目标和沟通失败。 */
    suspend fun judgeScenarioTurn(
        request: com.lazydog.english.domain.scenario.ScenarioTurnRequest,
    ): GenerationResult<com.lazydog.english.domain.scenario.ScenarioJudgement>

    /** 对话结束后集中生成固定三条表达改进与待复习表达。 */
    suspend fun summarizeScenario(
        request: com.lazydog.english.domain.scenario.ScenarioSummaryRequest,
        onStage: ((GenerationStage) -> Unit)? = null,
        /** 已经写出来的正文，用于等待期间铺内容而不是只显示一个字数（见 JsonStream）。 */
        onPartialText: ((String) -> Unit)? = null,
    ): GenerationResult<com.lazydog.english.domain.scenario.ScenarioSummary>

    /**
     * [onStage] 报"现在卡在哪一步"（还没接通 / 模型在想 / 正在写），实现应流式请求。
     * 只报字符数的话，推理模型开口前的那段思考里界面完全静止，看着像卡死。
     */
    suspend fun generateNewWords(
        request: NewWordsRequest,
        onStage: ((GenerationStage) -> Unit)? = null,
        /** 已经写出来的正文，用于等待期间铺内容而不是只显示一个字数（见 JsonStream）。 */
        onPartialText: ((String) -> Unit)? = null,
    ): GenerationResult<List<GeneratedWord>>

    /**
     * [onPartialText] 收到已经生成的讲解正文（未闭合 JSON 里抽出来的），
     * 用于生成期间直接铺内容，而不是只显示一个字数。
     */
    suspend fun generateGrammarLesson(
        request: GrammarLessonRequest,
        onStage: ((GenerationStage) -> Unit)? = null,
        onPartialText: ((String) -> Unit)? = null,
    ): GenerationResult<GeneratedGrammarLesson>

    /**
     * 语法练习题：针对一个语法点出几道挖空变形题，程序判分。
     * 新学完当场做，到期复习也做题而不是重读讲解。
     */
    suspend fun generateGrammarDrill(
        request: GrammarDrillRequest,
        onStage: ((GenerationStage) -> Unit)? = null,
        /** 已经写出来的正文，用于等待期间铺内容而不是只显示一个字数（见 JsonStream）。 */
        onPartialText: ((String) -> Unit)? = null,
    ): GenerationResult<List<GrammarDrillItem>>

    /** 中译英产出练习：出几句要用上目标形式的中文。 */
    suspend fun generateTranslationTasks(
        request: com.lazydog.english.domain.production.TranslationRequest,
        onStage: ((GenerationStage) -> Unit)? = null,
        /** 已经写出来的正文，用于等待期间铺内容而不是只显示一个字数（见 JsonStream）。 */
        onPartialText: ((String) -> Unit)? = null,
    ): GenerationResult<List<com.lazydog.english.domain.production.TranslationTask>>

    /**
     * 判定一句中译英：意思到没到、形式错在哪几类。
     * 判定只返回结构化结果，掌握状态和错题画像由本地决定（AI_CONTRACTS §1）。
     */
    suspend fun gradeTranslation(
        task: com.lazydog.english.domain.production.TranslationTask,
        userTextEn: String,
        learnerLevel: String,
        onStage: ((GenerationStage) -> Unit)? = null,
        /** 已经写出来的正文，用于等待期间铺内容而不是只显示一个字数（见 JsonStream）。 */
        onPartialText: ((String) -> Unit)? = null,
    ): GenerationResult<com.lazydog.english.domain.production.TranslationFeedback>

    /**
     * 生成一轮听力训练的句子（英语听力训练模块DESIGN.md §18）。
     * 返回前已过 ListeningValidation，条数可能少于请求数——由调用方决定够不够开一局。
     *
     * [onItem] 每有一句通过校验就回调一次，顺序与最终返回的一致。十句一次性等太久，
     * 界面拿到前几句就能开练，剩下的边听边补；不关心增量的调用方不传即可。
     *
     * [onStage] 报的是"现在卡在哪一步"。这是所有调用里最慢的一个，等待期间必须让人看出
     * 到底是没接通还是模型在想——这两件事用户该做的反应不一样。
     */
    suspend fun generateListeningSet(
        request: com.lazydog.english.domain.listening.ListeningSetRequest,
        onStage: ((GenerationStage) -> Unit)? = null,
        onItem: ((com.lazydog.english.domain.listening.ListeningItem) -> Unit)? = null,
    ): GenerationResult<List<com.lazydog.english.domain.listening.ListeningItem>>

    /** 生成渐进式阅读短文，返回前已通过 ReadingValidation。 */
    suspend fun generateReading(
        request: ReadingGenerationRequest,
        onStage: ((GenerationStage) -> Unit)? = null,
        /** 已经写出来的正文，用于等待期间铺内容而不是只显示一个字数（见 JsonStream）。 */
        onPartialText: ((String) -> Unit)? = null,
    ): GenerationResult<GeneratedReading>

    /**
     * 一个词的记忆提示（词汇记忆提示DESIGN.md）。**和新词生成分开的一次调用**：
     * 新词生成一次要写十几个词，塞不下"先判断这个词最值得记什么再只写那一两种"这一整套；
     * 而且记忆提示是要重来的——第一条没帮上忙就该换个策略再要一条，
     * 不该连着例句、音标、拼写事实一起重新生成。
     */
    suspend fun generateMemoryAssistance(
        request: MemoryAssistanceRequest,
        onStage: ((GenerationStage) -> Unit)? = null,
        /** 已到达的记忆钩子，用于边生成边显示；最终仍以完整解析加校验为准。 */
        onPartialHook: ((String) -> Unit)? = null,
    ): GenerationResult<MemoryAssistance>

    /** 点词解释：结合所在句子解释一个词。 */
    /**
     * 把几个两到四周前学过的词组成一句自然的话，用于进步挑战
     * （`持续学习DESIGN.md` §15）。返回前已校验每个词都真的在句子里。
     */
    suspend fun generateProofSentence(
        terms: List<String>,
        learnerLevel: String,
        onStage: ((GenerationStage) -> Unit)? = null,
    ): GenerationResult<ProofSentence>

    suspend fun explainWord(
        term: String,
        sentenceContext: String,
        learnerLevel: String,
        /** 学习者关心的领域；讲解里新给的那个例句会优先落在这些场景（`持续学习DESIGN.md` §19.2）。 */
        topics: List<String> = emptyList(),
        /** 已接收的原始结构化文本；用于在最终校验完成前逐步展示讲解。 */
        onProgress: ((String) -> Unit)? = null,
    ): GenerationResult<WordExplanation>

    /**
     * 摇一摇提问：结合当前学习页面注册的结构化上下文回答一个问题。
     * [onPartialAnswer] 收到已生成的回答正文，用于边生成边显示；
     * 最终仍以完整 JSON 解析加校验为准。
     */
    suspend fun askAboutContext(
        request: com.lazydog.english.domain.ask.AskRequest,
        onPartialAnswer: ((String) -> Unit)? = null,
    ): GenerationResult<com.lazydog.english.domain.ask.AskAnswer>

    /** 整句翻译加讲解（阅读里的点句操作）。 */
    suspend fun explainSentence(
        sentence: String,
        learnerLevel: String,
        onProgress: ((String) -> Unit)? = null,
    ): GenerationResult<SentenceExplanation>

    /**
     * 能力测试客观题出题：指定 CEFR 等级的单选题。[skillFilter] 非空时只出该技能
     * （vocab / grammar / reading / pragmatics，见 AssessmentSkill），用于定位题、
     * 覆盖约束强制换技能、探顶题这些需要精确指定的场景。
     */
    suspend fun generateAssessmentQuestions(
        cefrLevel: String,
        count: Int,
        topics: List<String>,
        skillFilter: String? = null,
        onStage: ((GenerationStage) -> Unit)? = null,
        /** 已经写出来的正文，用于等待期间铺内容而不是只显示一个字数（见 JsonStream）。 */
        onPartialText: ((String) -> Unit)? = null,
    ): GenerationResult<List<com.lazydog.english.domain.assessment.AssessmentQuestion>>

    /** 能力测试的独立阅读模块：一篇按等级定长的短文 + 4 道分技能标签的理解题。 */
    suspend fun generateDeepReading(
        cefrLevel: String,
        topics: List<String>,
        onStage: ((GenerationStage) -> Unit)? = null,
        /** 已经写出来的正文，用于等待期间铺内容而不是只显示一个字数（见 JsonStream）。 */
        onPartialText: ((String) -> Unit)? = null,
    ): GenerationResult<com.lazydog.english.domain.assessment.DeepReadingTask>

    /**
     * 能力测试客观题梯度里的"纠错或短答"题（覆盖约束第 5 类技能）：
     * 给一句带错的英文，学习者自己改，本地按相似度打部分分（不是简单对错二选一）。
     */
    suspend fun generateCorrectionItem(
        cefrLevel: String,
        topics: List<String>,
        onStage: ((GenerationStage) -> Unit)? = null,
        /** 已经写出来的正文，用于等待期间铺内容而不是只显示一个字数（见 JsonStream）。 */
        onPartialText: ((String) -> Unit)? = null,
    ): GenerationResult<com.lazydog.english.domain.assessment.CorrectionItem>

    /**
     * 能力测试里开放表达的评分（EXT_TEST_DESIGN.md §六：5 维度、每维 0~4 分、要求举证）。
     * [referenceCefrLevel] 为 null 时是"盲评"（不告诉 AI 参考等级），非空时是对照量表的第二轮评分。
     * 两轮都交给 AI，但升降级判断、是否需要复核的比较逻辑都在本地做
     * （AI_CONTRACTS.md §6：AI 不直接决定结论）。
     */
    suspend fun evaluateExpressionRubric(
        taskZh: String,
        userTextEn: String,
        referenceCefrLevel: String?,
        onStage: ((GenerationStage) -> Unit)? = null,
        /** 已经写出来的正文，用于等待期间铺内容而不是只显示一个字数（见 JsonStream）。 */
        onPartialText: ((String) -> Unit)? = null,
    ): GenerationResult<com.lazydog.english.domain.assessment.ExpressionRubric>

    /**
     * 朗读反馈里的"少量可理解提示"（UI_BRIEF.md 屏 19）：把 Azure 的客观分数/错误类型
     * 讲成 1～3 条人话。分数本身不经过 AI，只有措辞是 AI 生成的；调用失败时
     * 由 [com.lazydog.english.domain.speaking.localPronunciationTips] 本地兜底。
     */
    suspend fun explainPronunciation(
        referenceText: String,
        feedback: com.lazydog.english.domain.speaking.PronunciationFeedback,
        onStage: ((GenerationStage) -> Unit)? = null,
    ): GenerationResult<List<com.lazydog.english.domain.speaking.PronunciationTip>>
}

data class NewWordsRequest(
    val count: Int,
    /** 粗略水平描述，能力测试（M2）上线前用默认值。 */
    val learnerLevel: String,
    val topics: List<String>,
    /** 已在知识库里的词，生成时避开。调用方负责截断到合理数量。 */
    val knownTerms: List<String>,
)

/**
 * 一个搭配短语和它的中文翻译。
 *
 * 翻译跟着搭配一起生成、一起入库：卡片上那几个裸英文短语对初学者等于没写，
 * 而「点一下现翻」意味着每看一个词都要多等一次网络往返。老数据里 [zh] 是空的，
 * 界面上仍然可以点开现翻。
 */
data class Collocation(
    val en: String,
    val zh: String = "",
)

data class GeneratedWord(
    val term: String,
    val ipa: String,
    val meaningZh: String,
    val exampleEn: String,
    val exampleZh: String,
    /** 词性，封闭集合里的值（`PartOfSpeech.wire`，如 "VERB"/"NOUN"）。 */
    val pos: String = "",
    /**
     * 不规则变形（单词记忆DESIGN.md §4）：go → went/gone、child → children。
     * 规则变形不给——那是词法规则不是这个词的属性，存下来等于把同一条规则抄一百份。
     */
    val forms: List<String> = emptyList(),
    /** 1~2 个高价值搭配，不是孤立单词（§6.5"每个附带 1~2 个高价值搭配"）。 */
    val collocations: List<Collocation> = emptyList(),
    /**
     * 这个词的记忆方法：优先拆词根词缀并说明怎么合出这个意思，拆不出来的用联想/构词/形近对比。
     * 要针对这个词，不是"多读几遍"这种通用建议。
     */
    val memoryHintZh: String = "",
    /**
     * 下面三项是这个词的拼写事实，第一次生成就要给全。
     * 本地虽然能猜，但猜出来的是 necessary → nec/ess/ary、separate 的干扰项里
     * 没有 seperate 这种货色——拿去出题等于教错东西。
     */
    /** 词块拆分，按顺序拼起来等于 term。 */
    val chunks: List<String> = emptyList(),
    /** 最容易拼错的那一段，必须是 term 的子串。 */
    val trickyPart: String = "",
    /** 真人常写错的形式，2~3 个，用作四选一干扰项。 */
    val misspellings: List<String> = emptyList(),
)

data class GrammarLessonRequest(
    val learnerLevel: String,
    /** 用户指定想学的语法点；空则由 AI 挑选。 */
    val focus: String?,
    val knownGrammar: List<String>,
    /**
     * 最近错得最多的形式类别。[focus] 为空时用它挑语法点——
     * 学的内容应该由错题决定，而不是让 AI 随便挑一个。
     */
    val weakSpots: List<com.lazydog.english.domain.practice.MistakeSummary> = emptyList(),
)

data class GeneratedGrammarLesson(
    /** 可直接套用的英文结构公式，如 "be going to + base verb"。 */
    val patternEn: String,
    /**
     * 语法大类（`GrammarCategory.wire`）。它是身份键的一半：
     * 光看公式的话，`was/were + verb-ing` 和 `am/is/are + verb-ing` 归一化后一模一样。
     */
    val category: String = "",
    /** 中文语法标签，如“be going to 将来表达”。不作为卡片主标题。 */
    val labelZh: String,
    /** 列表和记忆卡第一眼看到的一句话用途。 */
    val summaryZh: String,
    /** 何时使用、语气和易混区别等完整讲解。 */
    val explanationZh: String,
    val goodExampleEn: String,
    val goodExampleZh: String,
    val badExampleEn: String,
    val badExampleNoteZh: String,
    val tipZh: String,
)

sealed interface GenerationResult<out T> {
    data class Success<T>(
        val data: T,
        val model: String,
        val promptVersion: Int,
        /** 校验中被丢弃的条目说明，给 UI 提示或日志用。 */
        val droppedNotes: List<String> = emptyList(),
    ) : GenerationResult<T>

    data class Failure(val reason: String) : GenerationResult<Nothing>
}

/** 进步挑战用的一句话（`持续学习DESIGN.md` §15）。 */
data class ProofSentence(
    val sentenceEn: String,
    val sentenceZh: String,
)
