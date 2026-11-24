package com.lazydog.english.core.network

import com.lazydog.english.domain.reading.WebReadingArticle
import com.lazydog.english.domain.reading.WebReadingCard
import com.lazydog.english.domain.reading.WebReadingFeed
import com.lazydog.english.domain.reading.WebReadingRules
import com.lazydog.english.domain.reading.WebReadingSource
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Simple English Wikipedia（`读书与网络阅读DESIGN.md` §2.1）：用基础词汇写的百科条目。
 *
 * 走 MediaWiki 的公开 API，不要密钥：列表用 `generator=search` 或 `generator=random` 顺带取导语，
 * 打开时取全文纯文本。正文是 CC BY-SA，阅读页标出处。在中国大陆通常要代理。
 */
class SimpleWikipediaClient(
    private val okHttpClient: OkHttpClient = defaultOkHttpClient,
    private val base: String = BASE,
) {

    /** [query] 为空时随机来一批；不为空就按它检索。 */
    suspend fun list(query: String): WebReadingFeed = withContext(Dispatchers.IO) {
        val generator = if (query.isBlank()) {
            "generator=random&grnnamespace=0&grnlimit=$MAX_CARDS"
        } else {
            "generator=search&gsrsearch=${encode(query.trim())}&gsrlimit=$MAX_CARDS"
        }
        val url = "$base/w/api.php?action=query&$generator&prop=extracts&exintro=1&explaintext=1&exsentences=2" +
            "&format=json&formatversion=2"
        val body = get(url, op = "维基列表")
            ?: return@withContext WebReadingFeed(failure = "连不上维基百科（在中国大陆通常要代理）")
        val cards = parseList(body) ?: return@withContext WebReadingFeed(failure = "维基返回的格式不对")
        if (cards.isEmpty()) WebReadingFeed(failure = "没找到相关条目，换个英文关键词试试") else WebReadingFeed(cards)
    }

    suspend fun load(card: WebReadingCard): WebReadingArticle? = withContext(Dispatchers.IO) {
        val url = "$base/w/api.php?action=query&prop=extracts&explaintext=1&redirects=1" +
            "&titles=${encode(card.key)}&format=json&formatversion=2"
        val body = get(url, op = "维基正文") ?: return@withContext null
        val paragraphs = parseArticle(body) ?: return@withContext null
        if (!WebReadingRules.usable(paragraphs)) return@withContext null
        WebReadingArticle(
            card = card,
            paragraphs = WebReadingRules.trim(paragraphs),
            url = card.link,
            attribution = WebReadingSource.Wiki.attribution,
        )
    }

    private fun get(url: String, op: String): String? {
        val request = Request.Builder().url(url)
            // 维基要求自动化请求带上能联系到的 UA；不带个人信息，只写应用名。
            .header("User-Agent", "LazyDogEnglishReader/1.0 (Android reading app)")
            .tag(CallHooks::class.java, CallHooks(op = op))
            .get().build()
        return try {
            okHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) response.body?.string() else null
            }
        } catch (_: IOException) {
            null
        }
    }

    @Serializable
    private data class Response(val query: Query? = null)

    @Serializable
    private data class Query(val pages: List<Page> = emptyList())

    @Serializable
    private data class Page(val title: String = "", val extract: String = "", val index: Int = 0, val missing: Boolean = false)

    companion object {
        const val BASE = "https://simple.wikipedia.org"
        const val MAX_CARDS = 20

        private val json = Json { ignoreUnknownKeys = true }

        private val defaultOkHttpClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .eventListenerFactory { HttpTimingListener() }
            .build()

        private fun encode(text: String) = URLEncoder.encode(text, "UTF-8")

        fun articleUrl(title: String) = "$BASE/wiki/" + encode(title.replace(' ', '_')).replace("+", "%20")

        /** 列表：消歧义页、"List of"、导语太短的不要——点进去不是一篇能读的文章。 */
        internal fun parseList(body: String): List<WebReadingCard>? {
            val response = runCatching { json.decodeFromString(Response.serializer(), body) }.getOrNull() ?: return null
            return response.query?.pages.orEmpty()
                .sortedBy { it.index }
                .filter { page ->
                    page.title.isNotBlank() && !page.missing && page.extract.length >= 60 &&
                        !page.title.startsWith("List of") && "may refer to" !in page.extract
                }
                .map { page ->
                    WebReadingCard(
                        source = WebReadingSource.Wiki,
                        key = page.title,
                        title = page.title,
                        teaser = page.extract.trim(),
                        channel = WebReadingSource.Wiki.labelZh,
                        link = articleUrl(page.title),
                    )
                }
        }

        /**
         * 全文：按行切段，小节标题（`== History ==`）不当正文；
         * 「Related pages」「References」「Other websites」以后都是链接列表，截掉。
         */
        internal fun parseArticle(body: String): List<String>? {
            val response = runCatching { json.decodeFromString(Response.serializer(), body) }.getOrNull() ?: return null
            val page = response.query?.pages?.firstOrNull()?.takeIf { !it.missing } ?: return null
            val out = mutableListOf<String>()
            for (raw in page.extract.lines()) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                val heading = HEADING.matchEntire(line)
                if (heading != null) {
                    if (heading.groupValues[1].trim() in TAIL_SECTIONS) break
                    continue
                }
                out += line
            }
            return out
        }

        private val HEADING = Regex("""^=+\s*(.*?)\s*=+$""")
        private val TAIL_SECTIONS = setOf(
            "Related pages", "References", "Other websites", "Notes", "Sources", "Further reading", "Gallery",
        )
    }
}
