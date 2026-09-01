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
    ): GenerationResult<com.lazydog.english.domain.scenario.ScenarioBrief>

    /** 只扮演对手并推进对话；不得纠错或评价用户。 */
    suspend fun generateScenarioTurn(
        request: com.lazydog.english.domain.scenario.ScenarioTurnRequest,
        onStage: ((GenerationStage) -> Unit)? = null,
    ): GenerationResult<com.lazydog.english.domain.scenario.ScenarioTurn>

    /** 与对话生成分开的目标判定调用；只返回命中目标和沟通失败。 */
    suspend fun judgeScenarioTurn(
        request: com.lazydog.english.domain.scenario.ScenarioTurnRequest,
    ): GenerationResult<com.lazydog.english.domain.scenario.ScenarioJudgement>

    /** 对话结束后集中生成固定三条表达改进与待复习表达。 */
    suspend fun summarizeScenario(
        request: com.lazydog.english.domain.scenario.ScenarioSummaryRequest,
        onStage: ((GenerationStage) -> Unit)? = null,
    ): GenerationResult<com.lazydog.english.domain.scenario.ScenarioSummary>

    /**
     * [onStage] 报"现在卡在哪一步"（还没接通 / 模型在想 / 正在写），实现应流式请求。
     * 只报字符数的话，推理模型开口前的那段思考里界面完全静止，看着像卡死。
     */
    suspend fun generateNewWords(
        request: NewWordsRequest,
        onStage: ((GenerationStage) -> Unit)? = null,
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
    ): GenerationResult<List<GrammarDrillItem>>

    /** 中译英产出练习：出几句要用上目标形式的中文。 */
    suspend fun generateTranslationTasks(
        request: com.lazydog.english.domain.production.TranslationRequest,
        onStage: ((GenerationStage) -> Unit)? = null,
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
    ): GenerationResult<GeneratedReading>

    /** 点词解释：结合所在句子解释一个词。 */
    suspend fun explainWord(
        term: String,
        sentenceContext: String,
        learnerLevel: String,
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
    ): GenerationResult<List<com.lazydog.english.domain.assessment.AssessmentQuestion>>

    /** 能力测试的独立阅读模块：一篇按等级定长的短文 + 4 道分技能标签的理解题。 */
    suspend fun generateDeepReading(
        cefrLevel: String,
        topics: List<String>,
        onStage: ((GenerationStage) -> Unit)? = null,
    ): GenerationResult<com.lazydog.english.domain.assessment.DeepReadingTask>

    /**
     * 能力测试客观题梯度里的"纠错或短答"题（覆盖约束第 5 类技能）：
     * 给一句带错的英文，学习者自己改，本地按相似度打部分分（不是简单对错二选一）。
     */
    suspend fun generateCorrectionItem(
        cefrLevel: String,
        topics: List<String>,
        onStage: ((GenerationStage) -> Unit)? = null,
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
    ): GenerationResult<com.lazydog.english.domain.assessment.ExpressionRubric>

    /**
     * 朗读反馈里的"少量可理解提示"（DESIGN.md 屏 19）：把 Azure 的客观分数/错误类型
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

data class GeneratedWord(
    val term: String,
    val ipa: String,
    val meaningZh: String,
    val exampleEn: String,
    val exampleZh: String,
    /** 词性，如 "v."/"n."/"adj."（CEFR 设计文档 §6.2 的词义单位字段之一）。 */
    val pos: String = "",
    /** 1~2 个高价值搭配，不是孤立单词（§6.5"每个附带 1~2 个高价值搭配"）。 */
    val collocations: List<String> = emptyList(),
    /**
     * 这个词的记忆方法：优先拆词根词缀并说明怎么合出这个意思，拆不出来的用联想/构词/形近对比。
     * 要针对这个词，不是"多读几遍"这种通用建议。
     */
    val memoryHintZh: String = "",
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
