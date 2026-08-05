package com.lazydog.english.domain.generation

/**
 * AI 内容生成的领域接口（AI_CONTRACTS.md §2）。
 * 领域层只依赖它；具体服务商实现在 core/ai。
 * 实现必须返回已通过本地校验的内容——不合格的返回 Failure，不得入库。
 */
interface LearningContentGenerator {

    /** [onProgress] 收到已生成的字符数，用于长请求的进度展示；实现应流式请求。 */
    suspend fun generateNewWords(
        request: NewWordsRequest,
        onProgress: ((Int) -> Unit)? = null,
    ): GenerationResult<List<GeneratedWord>>

    suspend fun generateGrammarLesson(
        request: GrammarLessonRequest,
        onProgress: ((Int) -> Unit)? = null,
    ): GenerationResult<GeneratedGrammarLesson>

    /** 生成渐进式阅读短文，返回前已通过 ReadingValidation。 */
    suspend fun generateReading(
        request: ReadingGenerationRequest,
        onProgress: ((Int) -> Unit)? = null,
    ): GenerationResult<GeneratedReading>

    /** 点词解释：结合所在句子解释一个词。 */
    suspend fun explainWord(
        term: String,
        sentenceContext: String,
        learnerLevel: String,
    ): GenerationResult<WordExplanation>

    /** 整句翻译加讲解（阅读里的点句操作）。 */
    suspend fun explainSentence(
        sentence: String,
        learnerLevel: String,
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
    ): GenerationResult<List<com.lazydog.english.domain.assessment.AssessmentQuestion>>

    /** 能力测试的独立阅读模块：一篇按等级定长的短文 + 4 道分技能标签的理解题。 */
    suspend fun generateDeepReading(
        cefrLevel: String,
        topics: List<String>,
    ): GenerationResult<com.lazydog.english.domain.assessment.DeepReadingTask>

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
    ): GenerationResult<com.lazydog.english.domain.assessment.ExpressionRubric>

    /**
     * 朗读反馈里的"少量可理解提示"（DESIGN.md 屏 19）：把 Azure 的客观分数/错误类型
     * 讲成 1～3 条人话。分数本身不经过 AI，只有措辞是 AI 生成的；调用失败时
     * 由 [com.lazydog.english.domain.speaking.localPronunciationTips] 本地兜底。
     */
    suspend fun explainPronunciation(
        referenceText: String,
        feedback: com.lazydog.english.domain.speaking.PronunciationFeedback,
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
)

data class GrammarLessonRequest(
    val learnerLevel: String,
    /** 用户指定想学的语法点；空则由 AI 挑选。 */
    val focus: String?,
    val knownGrammar: List<String>,
)

data class GeneratedGrammarLesson(
    val name: String,
    val patternEn: String,
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
