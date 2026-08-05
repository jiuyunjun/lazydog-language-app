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

    /** 能力测试出题：指定 CEFR 等级的单选题（词汇语境 / 语法 / 分级阅读混合）。 */
    suspend fun generateAssessmentQuestions(
        cefrLevel: String,
        count: Int,
        topics: List<String>,
    ): GenerationResult<List<com.lazydog.english.domain.assessment.AssessmentQuestion>>

    /**
     * 能力测试里“写一句话”的开放表达评估。题面本地模板生成，不需要 AI；
     * 只有评估这一步交给 AI（AI_CONTRACTS.md §6：不参与等级升降，只做反馈）。
     */
    suspend fun evaluateExpression(
        taskZh: String,
        userTextEn: String,
        cefrLevel: String,
    ): GenerationResult<com.lazydog.english.domain.assessment.ExpressionFeedback>
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
