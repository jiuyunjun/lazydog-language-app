package com.lazydog.english.core.ai

import com.lazydog.english.core.network.await
import com.lazydog.english.domain.assessment.AssessmentQuestion
import com.lazydog.english.domain.assessment.validateAssessmentQuestions
import com.lazydog.english.domain.generation.ContentValidation
import com.lazydog.english.domain.generation.GeneratedGrammarLesson
import com.lazydog.english.domain.generation.GeneratedReading
import com.lazydog.english.domain.generation.GeneratedWord
import com.lazydog.english.domain.generation.GenerationResult
import com.lazydog.english.domain.generation.GrammarLessonRequest
import com.lazydog.english.domain.generation.LearningContentGenerator
import com.lazydog.english.domain.generation.NewWordsRequest
import com.lazydog.english.domain.generation.ReadingGenerationRequest
import com.lazydog.english.domain.generation.ReadingQuestion
import com.lazydog.english.domain.generation.ReadingTargetGrammar
import com.lazydog.english.domain.generation.ReadingTargetWord
import com.lazydog.english.domain.generation.ReadingValidation
import com.lazydog.english.domain.generation.WordExplanation
import com.lazydog.english.domain.generation.SentenceExplanation
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** AI 服务配置快照，调用时从偏好取。 */
data class AiConfig(val baseUrl: String, val apiKey: String, val model: String)

/**
 * OpenAI 兼容接口的 [LearningContentGenerator] 实现。
 * 输出走 json_object 模式，解析后先过 ContentValidation 再返回；
 * 网络错误 / 429 / 5xx 自动重试一次。提示词版本随结果返回（AI_CONTRACTS §1）。
 * 配置以挂起函数注入，便于用 MockWebServer 做契约测试。
 */
class OpenAiContentGenerator(
    private val config: suspend () -> AiConfig,
    private val okHttpClient: OkHttpClient = defaultOkHttpClient,
    private val retryDelayMs: Long = RETRY_DELAY_MS,
) : LearningContentGenerator {

    override suspend fun generateNewWords(
        request: NewWordsRequest,
        onProgress: ((Int) -> Unit)?,
    ): GenerationResult<List<GeneratedWord>> {
        val outcome = complete(
            systemPrompt = SYSTEM_PROMPT,
            userPrompt = buildNewWordsPrompt(request),
            onProgress = onProgress,
        )
        val content = when (outcome) {
            is Completion.Error -> return GenerationResult.Failure(outcome.reason)
            is Completion.Content -> outcome
        }
        val payload = decode<NewWordsPayload>(content.text)
            ?: return GenerationResult.Failure("AI 返回的不是预期的 JSON 结构")
        if (payload.schemaVersion != SCHEMA_VERSION) {
            return GenerationResult.Failure("schema 版本不对：${payload.schemaVersion}")
        }
        val validated = ContentValidation.validateNewWords(
            words = payload.words.map { it.toDomain() },
            maxCount = request.count,
            knownTerms = request.knownTerms,
        )
        if (validated.valid.isEmpty()) {
            return GenerationResult.Failure(
                "生成的词都没通过校验：${validated.droppedNotes.take(3).joinToString("；")}",
            )
        }
        return GenerationResult.Success(
            data = validated.valid,
            model = content.model,
            promptVersion = PROMPT_VERSION,
            droppedNotes = validated.droppedNotes,
        )
    }

    override suspend fun generateGrammarLesson(
        request: GrammarLessonRequest,
        onProgress: ((Int) -> Unit)?,
    ): GenerationResult<GeneratedGrammarLesson> {
        val outcome = complete(
            systemPrompt = SYSTEM_PROMPT,
            userPrompt = buildGrammarPrompt(request),
            onProgress = onProgress,
        )
        val content = when (outcome) {
            is Completion.Error -> return GenerationResult.Failure(outcome.reason)
            is Completion.Content -> outcome
        }
        val payload = decode<GrammarPayload>(content.text)
            ?: return GenerationResult.Failure("AI 返回的不是预期的 JSON 结构")
        if (payload.schemaVersion != SCHEMA_VERSION) {
            return GenerationResult.Failure("schema 版本不对：${payload.schemaVersion}")
        }
        val lesson = payload.toDomain()
        val problem = ContentValidation.validateGrammarLesson(lesson, request.knownGrammar)
        if (problem != null) return GenerationResult.Failure("讲解没通过校验：$problem")
        return GenerationResult.Success(lesson, content.model, PROMPT_VERSION)
    }

    override suspend fun generateReading(
        request: ReadingGenerationRequest,
        onProgress: ((Int) -> Unit)?,
    ): GenerationResult<GeneratedReading> {
        val outcome = complete(
            systemPrompt = SYSTEM_PROMPT,
            userPrompt = buildReadingPrompt(request),
            onProgress = onProgress,
        )
        val content = when (outcome) {
            is Completion.Error -> return GenerationResult.Failure(outcome.reason)
            is Completion.Content -> outcome
        }
        val payload = decode<ReadingPayload>(content.text)
            ?: return GenerationResult.Failure("AI 返回的不是预期的 JSON 结构")
        if (payload.schemaVersion != SCHEMA_VERSION) {
            return GenerationResult.Failure("schema 版本不对：${payload.schemaVersion}")
        }
        val reading = payload.toDomain()
        val validation = ReadingValidation.validate(reading, request)
        if (validation.failure != null) {
            return GenerationResult.Failure("短文没通过校验：${validation.failure}")
        }
        return GenerationResult.Success(
            data = reading,
            model = content.model,
            promptVersion = PROMPT_VERSION,
            droppedNotes = validation.warnings,
        )
    }

    override suspend fun explainWord(
        term: String,
        sentenceContext: String,
        learnerLevel: String,
    ): GenerationResult<WordExplanation> {
        val outcome = complete(
            systemPrompt = SYSTEM_PROMPT,
            userPrompt = buildExplainWordPrompt(term, sentenceContext, learnerLevel),
        )
        val content = when (outcome) {
            is Completion.Error -> return GenerationResult.Failure(outcome.reason)
            is Completion.Content -> outcome
        }
        val payload = decode<WordExplanationPayload>(content.text)
            ?: return GenerationResult.Failure("AI 返回的不是预期的 JSON 结构")
        val explanation = payload.toDomain()
        if (explanation.meaningZh.isBlank() || explanation.meaningZh.length > 160) {
            return GenerationResult.Failure("解释缺失或过长")
        }
        return GenerationResult.Success(explanation, content.model, PROMPT_VERSION)
    }

    override suspend fun explainSentence(
        sentence: String,
        learnerLevel: String,
    ): GenerationResult<SentenceExplanation> {
        val outcome = complete(
            systemPrompt = SYSTEM_PROMPT,
            userPrompt = buildExplainSentencePrompt(sentence, learnerLevel),
        )
        val content = when (outcome) {
            is Completion.Error -> return GenerationResult.Failure(outcome.reason)
            is Completion.Content -> outcome
        }
        val payload = decode<SentenceExplanationPayload>(content.text)
            ?: return GenerationResult.Failure("AI 返回的不是预期的 JSON 结构")
        val explanation = payload.toDomain()
        if (explanation.translationZh.isBlank()) {
            return GenerationResult.Failure("没拿到译文")
        }
        return GenerationResult.Success(explanation, content.model, PROMPT_VERSION)
    }

    override suspend fun generateAssessmentQuestions(
        cefrLevel: String,
        count: Int,
        topics: List<String>,
    ): GenerationResult<List<AssessmentQuestion>> {
        val outcome = complete(
            systemPrompt = SYSTEM_PROMPT,
            userPrompt = buildAssessmentPrompt(cefrLevel, count, topics),
        )
        val content = when (outcome) {
            is Completion.Error -> return GenerationResult.Failure(outcome.reason)
            is Completion.Content -> outcome
        }
        val payload = decode<AssessmentPayload>(content.text)
            ?: return GenerationResult.Failure("AI 返回的不是预期的 JSON 结构")
        if (payload.schemaVersion != SCHEMA_VERSION) {
            return GenerationResult.Failure("schema 版本不对：${payload.schemaVersion}")
        }
        val valid = validateAssessmentQuestions(payload.questions.map { it.toDomain() })
        if (valid.isEmpty()) return GenerationResult.Failure("生成的题都没通过校验")
        return GenerationResult.Success(valid, content.model, PROMPT_VERSION)
    }

    // ---- 请求执行 ----

    private sealed interface Completion {
        data class Content(val text: String, val model: String) : Completion
        data class Error(val reason: String) : Completion
    }

    private suspend fun complete(
        systemPrompt: String,
        userPrompt: String,
        onProgress: ((Int) -> Unit)? = null,
    ): Completion = withContext(Dispatchers.IO) {
        val (baseUrl, apiKey, model) = config()
        val streaming = onProgress != null

        val body = json.encodeToString(
            ChatRequest.serializer(),
            ChatRequest(
                model = model,
                messages = listOf(
                    ChatMessage("system", systemPrompt),
                    ChatMessage("user", userPrompt),
                ),
                stream = streaming,
            ),
        )
        val request = Request.Builder()
            .url(chatCompletionsUrl(baseUrl))
            .header("Authorization", "Bearer $apiKey")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        var lastReason = "未知错误"
        repeat(2) { attempt ->
            try {
                okHttpClient.newCall(request).await().use { response ->
                    if (response.isSuccessful) {
                        return@withContext if (streaming) {
                            readStreamed(response, model, onProgress!!)
                        } else {
                            readWhole(response, model)
                        }
                    }
                    lastReason = "HTTP ${response.code}"
                    if (response.code !in RETRYABLE_CODES) {
                        return@withContext Completion.Error(lastReason)
                    }
                }
            } catch (e: IOException) {
                lastReason = "网络错误：${e.message ?: e.javaClass.simpleName}"
            }
            if (attempt == 0) delay(retryDelayMs)
        }
        Completion.Error("$lastReason（已重试 1 次）")
    }

    private fun readWhole(response: okhttp3.Response, fallbackModel: String): Completion {
        val text = response.body?.string().orEmpty()
        val chat = decode<ChatResponse>(text)
        val content = chat?.choices?.firstOrNull()?.message?.content
        return if (content.isNullOrBlank()) {
            Completion.Error("AI 返回为空")
        } else {
            Completion.Content(extractJson(content), chat.model.ifBlank { fallbackModel })
        }
    }

    /** 读 SSE 流：每收到一段就回调累计字符数。流中断不重试（避免重复计费），直接报错。 */
    private fun readStreamed(
        response: okhttp3.Response,
        fallbackModel: String,
        onProgress: (Int) -> Unit,
    ): Completion {
        val source = response.body?.source() ?: return Completion.Error("AI 返回为空")
        val builder = StringBuilder()
        var model = ""
        try {
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") break
                val chunk = decode<StreamChunk>(data) ?: continue
                if (model.isBlank()) model = chunk.model
                val delta = chunk.choices.firstOrNull()?.delta?.content
                if (!delta.isNullOrEmpty()) {
                    builder.append(delta)
                    onProgress(builder.length)
                }
            }
        } catch (e: IOException) {
            return Completion.Error("流式传输中断：${e.message ?: e.javaClass.simpleName}")
        }
        return if (builder.isBlank()) {
            Completion.Error("AI 返回为空")
        } else {
            Completion.Content(extractJson(builder.toString()), model.ifBlank { fallbackModel })
        }
    }

    private inline fun <reified T> decode(text: String): T? =
        runCatching { json.decodeFromString<T>(text) }.getOrNull()

    // ---- JSON 载荷 ----

    @Serializable
    private data class ChatMessage(val role: String, val content: String = "")

    @Serializable
    private data class ResponseFormat(val type: String = "json_object")

    @Serializable
    private data class ChatRequest(
        val model: String,
        val messages: List<ChatMessage>,
        @SerialName("response_format") val responseFormat: ResponseFormat = ResponseFormat(),
        val stream: Boolean = false,
    )

    @Serializable
    private data class StreamDelta(val content: String = "")

    @Serializable
    private data class StreamChoice(val delta: StreamDelta = StreamDelta())

    @Serializable
    private data class StreamChunk(
        val model: String = "",
        val choices: List<StreamChoice> = emptyList(),
    )

    @Serializable
    private data class ChatChoice(val message: ChatMessage = ChatMessage("assistant"))

    @Serializable
    private data class ChatResponse(
        val model: String = "",
        val choices: List<ChatChoice> = emptyList(),
    )

    @Serializable
    private data class WordPayload(
        val term: String = "",
        val ipa: String = "",
        val meaningZh: String = "",
        val exampleEn: String = "",
        val exampleZh: String = "",
    ) {
        fun toDomain() = GeneratedWord(term, ipa, meaningZh, exampleEn, exampleZh)
    }

    @Serializable
    private data class NewWordsPayload(
        val schemaVersion: Int = 0,
        val words: List<WordPayload> = emptyList(),
    )

    @Serializable
    private data class ReadingTargetWordPayload(
        val term: String = "",
        val meaningZh: String = "",
        val exampleFromText: String = "",
        val role: String = "",
    )

    @Serializable
    private data class ReadingTargetGrammarPayload(
        val name: String = "",
        val exampleFromText: String = "",
        val explanationZh: String = "",
    )

    @Serializable
    private data class ReadingQuestionPayload(
        val promptZh: String = "",
        val options: List<String> = emptyList(),
        val answerIndex: Int = -1,
        val explanationZh: String = "",
    )

    @Serializable
    private data class ReadingPayload(
        val schemaVersion: Int = 0,
        val title: String = "",
        val body: String = "",
        val estimatedCefr: String = "",
        val targetVocabulary: List<ReadingTargetWordPayload> = emptyList(),
        val targetGrammar: List<ReadingTargetGrammarPayload> = emptyList(),
        val comprehensionQuestions: List<ReadingQuestionPayload> = emptyList(),
    ) {
        fun toDomain() = GeneratedReading(
            title = title.trim(),
            body = body.trim(),
            estimatedCefr = estimatedCefr.trim(),
            targetVocabulary = targetVocabulary.map {
                ReadingTargetWord(it.term.trim(), it.meaningZh.trim(), it.exampleFromText.trim(), it.role.trim())
            },
            targetGrammar = targetGrammar.map {
                ReadingTargetGrammar(it.name.trim(), it.exampleFromText.trim(), it.explanationZh.trim())
            },
            comprehensionQuestions = comprehensionQuestions.map {
                ReadingQuestion(it.promptZh.trim(), it.options, it.answerIndex, it.explanationZh.trim())
            },
        )
    }

    @Serializable
    private data class AssessmentQuestionPayload(
        val skill: String = "",
        val prompt: String = "",
        val options: List<String> = emptyList(),
        val answerIndex: Int = -1,
        val explanationZh: String = "",
    ) {
        fun toDomain() = AssessmentQuestion(
            skill = skill.trim(),
            prompt = prompt.trim(),
            options = options,
            answerIndex = answerIndex,
            explanationZh = explanationZh.trim(),
        )
    }

    @Serializable
    private data class AssessmentPayload(
        val schemaVersion: Int = 0,
        val questions: List<AssessmentQuestionPayload> = emptyList(),
    )

    @Serializable
    private data class SentenceExplanationPayload(
        val translationZh: String = "",
        val explanationZh: String = "",
    ) {
        fun toDomain() = SentenceExplanation(translationZh.trim(), explanationZh.trim())
    }

    @Serializable
    private data class WordExplanationPayload(
        val term: String = "",
        val ipa: String = "",
        val meaningZh: String = "",
        val usageNoteZh: String = "",
    ) {
        fun toDomain() = WordExplanation(term.trim(), ipa.trim(), meaningZh.trim(), usageNoteZh.trim())
    }

    @Serializable
    private data class GrammarPayload(
        val schemaVersion: Int = 0,
        val name: String = "",
        val patternEn: String = "",
        val explanationZh: String = "",
        val goodExampleEn: String = "",
        val goodExampleZh: String = "",
        val badExampleEn: String = "",
        val badExampleNoteZh: String = "",
        val tipZh: String = "",
    ) {
        fun toDomain() = GeneratedGrammarLesson(
            name = name.trim(),
            patternEn = patternEn.trim(),
            explanationZh = explanationZh.trim(),
            goodExampleEn = goodExampleEn.trim(),
            goodExampleZh = goodExampleZh.trim(),
            badExampleEn = badExampleEn.trim(),
            badExampleNoteZh = badExampleNoteZh.trim(),
            tipZh = tipZh.trim(),
        )
    }

    companion object {
        const val SCHEMA_VERSION = 1
        const val PROMPT_VERSION = 1
        private const val RETRY_DELAY_MS = 1200L
        private val RETRYABLE_CODES = setOf(429) + (500..599)

        // encodeDefaults：确保 response_format 这类默认值字段也会写进请求体。
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        private val defaultOkHttpClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .build()

        private const val SYSTEM_PROMPT =
            "你是给中文母语者出英语学习内容的助手。严格只输出一个 JSON 对象：" +
                "不要 markdown 代码块，不要输出 JSON 以外的任何文字，不要添加 schema 之外的字段。"

        internal fun buildNewWordsPrompt(request: NewWordsRequest): String = buildString {
            appendLine("生成 ${request.count} 个适合该学习者的英语新单词。")
            appendLine("学习者水平：${request.learnerLevel}。")
            if (request.topics.isNotEmpty()) {
                appendLine("兴趣主题（选词尽量贴近）：${request.topics.joinToString("、")}。")
            }
            if (request.knownTerms.isNotEmpty()) {
                appendLine("这些词已经学过，不要出现：${request.knownTerms.joinToString(", ")}。")
            }
            appendLine("每个词给美式音标 ipa、简洁中文释义 meaningZh（含词性）、一句自然的英文例句 exampleEn（必须包含该词）和例句中文翻译 exampleZh。")
            appendLine("输出 JSON schema：")
            appendLine("""{"schemaVersion":1,"words":[{"term":"...","ipa":"...","meaningZh":"...","exampleEn":"...","exampleZh":"..."}]}""")
        }

        internal fun buildReadingPrompt(request: ReadingGenerationRequest): String = buildString {
            appendLine("写一篇约 ${request.targetLength} 个英文单词的短文，给中文母语的英语学习者做渐进式阅读。")
            appendLine("学习者水平：${request.learnerLevel}。主题：${request.topic}。")
            if (request.reviewVocabulary.isNotEmpty()) {
                appendLine("正文必须自然地用上这些复习词（每个都要出现）：${request.reviewVocabulary.joinToString(", ")}。")
            }
            if (request.knownVocabulary.isNotEmpty()) {
                appendLine("学习者已掌握的词汇样本（正文难度以此为准，不要明显超纲）：${request.knownVocabulary.joinToString(", ")}。")
            }
            if (request.reviewGrammar.isNotEmpty()) {
                appendLine("尽量在文中体现这些语法点：${request.reviewGrammar.joinToString("、")}。")
            }
            appendLine("最多引入 ${request.maxNewWords} 个略高于当前水平的新词。")
            appendLine("targetVocabulary 里列出所有复习词（role=\"review\"）和引入的新词（role=\"new\"），")
            appendLine("exampleFromText 必须是正文里的原句；targetGrammar 的 exampleFromText 同样必须逐字来自正文。")
            appendLine("出 2~4 道中文单选理解题，选项不重复，answerIndex 从 0 开始。")
            appendLine("输出 JSON schema：")
            appendLine(
                """{"schemaVersion":1,"title":"...","body":"...","estimatedCefr":"A2",""" +
                    """"targetVocabulary":[{"term":"...","meaningZh":"...","exampleFromText":"...","role":"review"}],""" +
                    """"targetGrammar":[{"name":"...","exampleFromText":"...","explanationZh":"..."}],""" +
                    """"comprehensionQuestions":[{"promptZh":"...","options":["..."],"answerIndex":0,"explanationZh":"..."}]}""",
            )
        }

        internal fun buildAssessmentPrompt(level: String, count: Int, topics: List<String>): String = buildString {
            appendLine("给中文母语的英语学习者出 $count 道 CEFR $level 难度的单选题，用来评估水平。")
            appendLine("题型混合：语境选词（英文句子挖空选词，skill=\"vocab\"）和语法（skill=\"grammar\"）。")
            if (topics.isNotEmpty()) appendLine("语料可以贴近这些主题：${topics.joinToString("、")}。")
            appendLine("每题 3~4 个选项且不重复，只有一个正确答案，answerIndex 从 0 开始；explanationZh 一句话解析。")
            appendLine("难度务必贴住 $level：不要为了区分度混入明显更高或更低难度的题。")
            appendLine("输出 JSON schema：")
            appendLine(
                """{"schemaVersion":1,"questions":[{"skill":"vocab","prompt":"...","options":["..."],"answerIndex":0,"explanationZh":"..."}]}""",
            )
        }

        internal fun buildExplainSentencePrompt(sentence: String, level: String): String = buildString {
            appendLine("把这句英文翻译成中文，并给水平 $level 的中文母语学习者用一两句话讲讲句子结构或值得注意的用法：")
            appendLine(sentence)
            appendLine("""输出 JSON schema：{"translationZh":"...","explanationZh":"..."}""")
        }

        internal fun buildExplainWordPrompt(term: String, sentence: String, level: String): String = buildString {
            appendLine("解释单词 \"$term\" 在下面这句话里的意思，给水平 $level 的中文母语学习者看：")
            appendLine(sentence)
            appendLine("meaningZh 是简洁中文释义（含词性）；usageNoteZh 用一句话说明它在这句里的用法，可以为空字符串。")
            appendLine("""输出 JSON schema：{"term":"$term","ipa":"...","meaningZh":"...","usageNoteZh":"..."}""")
        }

        internal fun buildGrammarPrompt(request: GrammarLessonRequest): String = buildString {
            if (request.focus.isNullOrBlank()) {
                appendLine("挑一个适合该学习者水平、实用的英语语法点，写一段讲解。")
            } else {
                appendLine("讲解这个英语语法点：${request.focus}。")
            }
            appendLine("学习者水平：${request.learnerLevel}。")
            if (request.knownGrammar.isNotEmpty()) {
                appendLine("这些语法点已经学过，不要重复：${request.knownGrammar.joinToString("、")}。")
            }
            appendLine("要求：name 是语法点中文名；patternEn 是结构公式（如 have been doing）；")
            appendLine("explanationZh 用两三句大白话讲清楚什么时候用；goodExampleEn/goodExampleZh 一个正确例句和翻译；")
            appendLine("badExampleEn 一个中国学习者容易写错的句子，badExampleNoteZh 说明错在哪；tipZh 一句易混点提醒。")
            appendLine("输出 JSON schema：")
            appendLine(
                """{"schemaVersion":1,"name":"...","patternEn":"...","explanationZh":"...",""" +
                    """"goodExampleEn":"...","goodExampleZh":"...","badExampleEn":"...","badExampleNoteZh":"...","tipZh":"..."}""",
            )
        }
    }
}

/** 容错取出 content 里的 JSON：去掉可能的 ``` 围栏和 JSON 前后的杂质文本。 */
internal fun extractJson(content: String): String {
    val start = content.indexOf('{')
    val end = content.lastIndexOf('}')
    return if (start in 0 until end) content.substring(start, end + 1) else content.trim()
}

internal fun chatCompletionsUrl(baseUrl: String): String =
    baseUrl.trim().trimEnd('/') + "/chat/completions"
