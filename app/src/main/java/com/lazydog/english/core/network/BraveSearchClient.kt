package com.lazydog.english.core.network

import com.lazydog.english.domain.generation.WebSearchHit
import com.lazydog.english.domain.generation.WebSearchProvider
import com.lazydog.english.domain.generation.WebSearchResult
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Brave Search API 的 [WebSearchProvider] 实现（`ARCHITECTURE.md` §8：
 * 密钥由用户在设置页保存在应用私有存储里，源码里只有占位符）。
 *
 * 免费档有每秒 1 次的限流，所以这里不做并发检索、不做重试放大：
 * 一次调用一个查询，被限流就如实说，由调用方退回不带事实的写法。
 */
class BraveSearchClient(
    private val apiKey: suspend () -> String,
    private val okHttpClient: OkHttpClient = defaultOkHttpClient,
    private val endpoint: String = ENDPOINT,
) : WebSearchProvider {

    override suspend fun isConfigured(): Boolean = apiKey().isNotBlank()

    override suspend fun search(query: String, count: Int, freshness: String): WebSearchResult =
        withContext(Dispatchers.IO) {
            val key = apiKey().trim()
            if (key.isBlank()) return@withContext WebSearchResult(failure = "没有配置 Brave 搜索密钥")
            if (query.isBlank()) return@withContext WebSearchResult(failure = "搜索词是空的")

            val url = buildString {
                append(endpoint)
                append("?q=").append(URLEncoder.encode(query.trim(), "UTF-8"))
                append("&count=").append(count.coerceIn(1, 20))
                if (freshness.isNotBlank()) append("&freshness=").append(freshness)
                append("&text_decorations=0")
                append("&safesearch=moderate")
            }
            val request = Request.Builder()
                .url(url)
                .header("X-Subscription-Token", key)
                .header("Accept", "application/json")
                .tag(CallHooks::class.java, CallHooks(op = "搜索"))
                .get()
                .build()
            try {
                okHttpClient.newCall(request).await().use { response ->
                    val body = response.body?.string().orEmpty()
                    when {
                        response.isSuccessful -> parse(body)
                        response.code == 401 || response.code == 403 ->
                            WebSearchResult(failure = "Brave 搜索密钥无效（HTTP ${response.code}）")
                        response.code == 429 ->
                            WebSearchResult(failure = "Brave 搜索被限流了，稍后再试")
                        else -> WebSearchResult(failure = "Brave 搜索失败：HTTP ${response.code}")
                    }
                }
            } catch (e: IOException) {
                WebSearchResult(failure = "搜索网络错误：${e.message ?: e.javaClass.simpleName}")
            }
        }

    private fun parse(body: String): WebSearchResult {
        val payload = runCatching { json.decodeFromString(BravePayload.serializer(), body) }
            .getOrNull()
        // 解不出来时把开头几十个字符带上：是 gzip 二进制、HTML 错误页还是别的结构，
        // 差别很大，只说一句"不是预期结构"下次还得再猜一遍。
            ?: return WebSearchResult(
                failure = "Brave 返回的不是预期结构：${body.take(60).replace(Regex("\\s+"), " ")}",
            )
        val hits = payload.web?.results.orEmpty().mapNotNull { result ->
            val title = stripTags(result.title)
            if (title.isBlank()) {
                null
            } else {
                WebSearchHit(
                    title = title,
                    summary = stripTags(result.description),
                    url = result.url.trim(),
                    age = result.pageAge.ifBlank { result.age }.trim(),
                )
            }
        }
        return if (hits.isEmpty()) WebSearchResult(failure = "没搜到可用结果") else WebSearchResult(hits)
    }

    /** 摘要里会带 `<strong>` 高亮；进提示词之前去掉，免得模型把标签当内容。 */
    private fun stripTags(raw: String): String =
        raw.replace(TAG, "").replace("&amp;", "&").replace("&quot;", "\"").trim()

    @Serializable
    private data class BravePayload(val web: BraveWeb? = null)

    @Serializable
    private data class BraveWeb(val results: List<BraveResult> = emptyList())

    @Serializable
    private data class BraveResult(
        val title: String = "",
        val description: String = "",
        val url: String = "",
        val age: String = "",
        @SerialName("page_age") val pageAge: String = "",
    )

    companion object {
        const val ENDPOINT = "https://api.search.brave.com/res/v1/web/search"

        private val TAG = Regex("<[^>]+>")

        private val json = Json { ignoreUnknownKeys = true }

        private val defaultOkHttpClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .eventListenerFactory { HttpTimingListener() }
            .build()
    }
}
