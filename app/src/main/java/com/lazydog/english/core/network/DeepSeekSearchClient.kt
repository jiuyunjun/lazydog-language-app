package com.lazydog.english.core.network

import com.lazydog.english.domain.generation.WebSearchHit
import com.lazydog.english.domain.generation.WebSearchProvider
import com.lazydog.english.domain.generation.WebSearchResult
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * DeepSeek Search API 的 [WebSearchProvider] 实现（D-090）。国内直连可用，Brave 在大陆要代理。
 *
 * 接口是 `POST /v1/tools/search`，和对话共用一个密钥。它和 Brave 的差别都在这里抹平：
 * - **没有时间窗参数**，只有查询里的 `after:` 运算符。`pd`/`pw`/`pm`/`py` 换算成具体日期拼进查询。
 * - **没有新闻分区、没有国家参数**（`localization` 收的是 IP），[news] 只能把语言写成 `lang:`，
 *   国家丢掉。热点主路是 Google 新闻，这里只是兜底，粗一点能接受。
 * - 查询最多 128 字，运算符也算在里面，所以先给运算符留位置再截正文。
 */
class DeepSeekSearchClient(
    private val apiKey: suspend () -> String,
    private val okHttpClient: OkHttpClient = defaultOkHttpClient,
    private val endpoint: String = ENDPOINT,
    /** 按 UTC 取今天；测试里固定下来，`after:` 才可断言。 */
    private val today: () -> LocalDate = { LocalDate.now(ZoneOffset.UTC) },
) : WebSearchProvider {

    override suspend fun isConfigured(): Boolean = apiKey().isNotBlank()

    override suspend fun search(query: String, count: Int, freshness: String): WebSearchResult =
        post(buildQuery(query, freshness, lang = ""), count, op = "搜索", emptyFailure = "没搜到可用结果")

    override suspend fun news(
        query: String,
        count: Int,
        freshness: String,
        country: String,
        searchLang: String,
    ): WebSearchResult =
        post(buildQuery(query, freshness, langOperator(searchLang)), count, op = "新闻", emptyFailure = "没搜到最近的新闻")

    internal fun buildQuery(query: String, freshness: String, lang: String): String {
        val operators = listOfNotNull(
            lang.takeIf { it.isNotBlank() }?.let { "lang:$it" },
            afterDate(freshness)?.let { "after:$it" },
        ).joinToString(" ")
        val room = MAX_QUERY_CHARS - if (operators.isEmpty()) 0 else operators.length + 1
        val base = query.trim().take(room.coerceAtLeast(1)).trim()
        return if (operators.isEmpty()) base else "$base $operators"
    }

    private fun afterDate(freshness: String): LocalDate? = when (freshness) {
        WebSearchProvider.FRESH_DAY -> today().minusDays(1)
        WebSearchProvider.FRESH_WEEK -> today().minusDays(7)
        WebSearchProvider.FRESH_MONTH -> today().minusMonths(1)
        WebSearchProvider.FRESH_YEAR -> today().minusYears(1)
        else -> null
    }

    /** Brave 的 search_lang（`zh-hans`、`ja`、`en`）换成 DeepSeek 的语言码；简繁都是 `zh`。 */
    private fun langOperator(searchLang: String): String =
        searchLang.trim().lowercase().substringBefore('-')

    private suspend fun post(query: String, count: Int, op: String, emptyFailure: String): WebSearchResult =
        withContext(Dispatchers.IO) {
            val key = apiKey().trim()
            if (key.isBlank()) return@withContext WebSearchResult(failure = "没有配置 DeepSeek 密钥")
            if (query.isBlank()) return@withContext WebSearchResult(failure = "搜索词是空的")

            val body = json.encodeToString(SearchRequest.serializer(), SearchRequest(query, count.coerceIn(1, MAX_COUNT)))
            val request = Request.Builder()
                .url(endpoint)
                .header("Authorization", "Bearer $key")
                .header("Accept", "application/json")
                .tag(CallHooks::class.java, CallHooks(op = op))
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .build()
            try {
                okHttpClient.newCall(request).await().use { response ->
                    val text = response.body?.string().orEmpty()
                    when {
                        response.isSuccessful -> parse(text, emptyFailure)
                        response.code == 401 -> WebSearchResult(failure = "DeepSeek 密钥无效（HTTP 401）")
                        response.code == 402 -> WebSearchResult(failure = "DeepSeek 账户余额不足（HTTP 402）")
                        response.code == 429 -> WebSearchResult(failure = "DeepSeek 搜索被限流了，稍后再试")
                        else -> WebSearchResult(
                            failure = "DeepSeek 搜索失败：HTTP ${response.code}" +
                                (errorMessage(text)?.let { "，$it" } ?: ""),
                        )
                    }
                }
            } catch (e: IOException) {
                WebSearchResult(failure = "搜索网络错误：${e.message ?: e.javaClass.simpleName}")
            }
        }

    private fun parse(body: String, emptyFailure: String): WebSearchResult {
        val payload = runCatching { json.decodeFromString(SearchResponse.serializer(), body) }.getOrNull()
            ?: return WebSearchResult(
                failure = "DeepSeek 返回的不是预期结构：${body.take(60).replace(Regex("\\s+"), " ")}",
            )
        val hits = payload.results.mapNotNull { result ->
            val title = result.title.trim()
            if (title.isBlank()) {
                null
            } else {
                WebSearchHit(
                    title = title,
                    // snippet 是给界面看的短摘要（100～200 字）；summary 六七百字，进 Fact Pack 也会被截到 220。
                    summary = result.snippet.ifBlank { result.summary }.trim(),
                    url = result.url.trim(),
                    age = result.date.orEmpty().trim(),
                    source = result.siteName?.trim()?.takeIf { it.isNotEmpty() }
                        ?: hostOf(result.url),
                )
            }
        }
        // 空结果是 200 + 空数组，不计费，不是错误——但对调用方来说就是"没搜到"。
        return if (hits.isEmpty()) WebSearchResult(failure = emptyFailure) else WebSearchResult(hits)
    }

    private fun errorMessage(body: String): String? =
        runCatching { json.decodeFromString(ErrorResponse.serializer(), body).error?.message }
            .getOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.take(120)

    private fun hostOf(url: String): String =
        runCatching { java.net.URI(url.trim()).host.orEmpty() }.getOrDefault("").removePrefix("www.")

    @Serializable
    private data class SearchRequest(val query: String, val count: Int)

    @Serializable
    private data class SearchResponse(val results: List<SearchResult> = emptyList())

    @Serializable
    private data class SearchResult(
        val title: String = "",
        val url: String = "",
        val snippet: String = "",
        val summary: String = "",
        @SerialName("site_name") val siteName: String? = null,
        val date: String? = null,
    )

    @Serializable
    private data class ErrorResponse(val error: ErrorBody? = null)

    @Serializable
    private data class ErrorBody(val message: String = "")

    companion object {
        const val ENDPOINT = "https://api.deepseek.com/v1/tools/search"
        const val MAX_QUERY_CHARS = 128
        const val MAX_COUNT = 50

        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        private val json = Json { ignoreUnknownKeys = true }

        private val defaultOkHttpClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .eventListenerFactory { HttpTimingListener() }
            .build()
    }
}
