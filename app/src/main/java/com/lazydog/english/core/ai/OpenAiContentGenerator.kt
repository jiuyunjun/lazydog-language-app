package com.lazydog.english.core.ai

import com.lazydog.english.core.network.await
import com.lazydog.english.domain.generation.ContentValidation
import com.lazydog.english.domain.generation.GeneratedGrammarLesson
import com.lazydog.english.domain.generation.GeneratedWord
import com.lazydog.english.domain.generation.GenerationResult
import com.lazydog.english.domain.generation.GrammarLessonRequest
import com.lazydog.english.domain.generation.LearningContentGenerator
import com.lazydog.english.domain.generation.NewWordsRequest
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
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
    ): GenerationResult<List<GeneratedWord>> {
        val outcome = complete(
            systemPrompt = SYSTEM_PROMPT,
            userPrompt = buildNewWordsPrompt(request),
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
    ): GenerationResult<GeneratedGrammarLesson> {
        val outcome = complete(
            systemPrompt = SYSTEM_PROMPT,
            userPrompt = buildGrammarPrompt(request),
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

    // ---- 请求执行 ----

    private sealed interface Completion {
        data class Content(val text: String, val model: String) : Completion
        data class Error(val reason: String) : Completion
    }

    private suspend fun complete(systemPrompt: String, userPrompt: String): Completion {
        val (baseUrl, apiKey, model) = config()

        val body = json.encodeToString(
            ChatRequest.serializer(),
            ChatRequest(
                model = model,
                messages = listOf(
                    ChatMessage("system", systemPrompt),
                    ChatMessage("user", userPrompt),
                ),
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
                    val text = response.body?.string().orEmpty()
                    if (response.isSuccessful) {
                        val chat = decode<ChatResponse>(text)
                        val content = chat?.choices?.firstOrNull()?.message?.content
                        return if (content.isNullOrBlank()) {
                            Completion.Error("AI 返回为空")
                        } else {
                            Completion.Content(extractJson(content), chat.model.ifBlank { model })
                        }
                    }
                    lastReason = "HTTP ${response.code}"
                    if (response.code !in RETRYABLE_CODES) return Completion.Error(lastReason)
                }
            } catch (e: IOException) {
                lastReason = "网络错误：${e.message ?: e.javaClass.simpleName}"
            }
            if (attempt == 0) delay(retryDelayMs)
        }
        return Completion.Error("$lastReason（已重试 1 次）")
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
