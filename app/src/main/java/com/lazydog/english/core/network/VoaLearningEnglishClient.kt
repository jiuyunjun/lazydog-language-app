package com.lazydog.english.core.network

import com.lazydog.english.domain.reading.VoaChannel
import com.lazydog.english.domain.reading.WebReadingArticle
import com.lazydog.english.domain.reading.WebReadingCard
import com.lazydog.english.domain.reading.WebReadingFeed
import com.lazydog.english.domain.reading.WebReadingRules
import com.lazydog.english.domain.reading.WebReadingSource
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.w3c.dom.Element

/**
 * VOA Learning English（`读书与网络阅读DESIGN.md` §2.1）：按栏目读 RSS，点开再抓文章页取正文。
 *
 * 美国政府作品，属于公有领域；页面底部仍然标出处。文章页的正文在 `<div class="wsw">` 里，
 * 后面跟着一条下划线和「Words in This Story」生词表——生词表不算正文，截掉。
 * 在中国大陆要代理才连得上，连不上就报错，不影响别的来源。
 */
class VoaLearningEnglishClient(
    private val okHttpClient: OkHttpClient = defaultOkHttpClient,
    private val base: String = BASE,
) {

    suspend fun list(channel: VoaChannel): WebReadingFeed = withContext(Dispatchers.IO) {
        val body = get(base + channel.feedPath, op = "VOA 列表")
            ?: return@withContext WebReadingFeed(failure = "连不上 VOA（在中国大陆通常要代理）")
        val cards = parseFeed(body, channel)
            ?: return@withContext WebReadingFeed(failure = "VOA 返回的不是 RSS")
        if (cards.isEmpty()) WebReadingFeed(failure = "这个栏目暂时没有文章") else WebReadingFeed(cards)
    }

    suspend fun load(card: WebReadingCard): WebReadingArticle? = withContext(Dispatchers.IO) {
        val html = get(card.link, op = "VOA 正文") ?: return@withContext null
        val paragraphs = extractBody(html)
        if (!WebReadingRules.usable(paragraphs)) return@withContext null
        WebReadingArticle(
            card = card,
            paragraphs = WebReadingRules.trim(paragraphs),
            url = card.link,
            attribution = WebReadingSource.Voa.attribution,
        )
    }

    private fun get(url: String, op: String): String? {
        val request = runCatching {
            Request.Builder().url(url)
                .header("User-Agent", USER_AGENT)
                .tag(CallHooks::class.java, CallHooks(op = op))
                .get().build()
        }.getOrNull() ?: return null
        return try {
            okHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) response.body?.string() else null
            }
        } catch (_: IOException) {
            null
        }
    }

    companion object {
        const val BASE = "https://learningenglish.voanews.com"

        /** 一个栏目列多少条。RSS 本身给 20 条左右。 */
        const val MAX_CARDS = 20

        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

        private val defaultOkHttpClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .eventListenerFactory { HttpTimingListener() }
            .build()

        internal fun parseFeed(xml: String, channel: VoaChannel): List<WebReadingCard>? {
            val document = runCatching {
                val factory = DocumentBuilderFactory.newInstance().apply {
                    runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                    isExpandEntityReferences = false
                }
                factory.newDocumentBuilder().parse(xml.byteInputStream())
            }.getOrNull() ?: return null
            val items = document.getElementsByTagName("item")
            return (0 until items.length).mapNotNull { i ->
                val item = items.item(i) as? Element ?: return@mapNotNull null
                val link = item.text("link")
                val title = item.text("title")
                if (link.isBlank() || title.isBlank()) return@mapNotNull null
                WebReadingCard(
                    source = WebReadingSource.Voa,
                    key = link,
                    title = title,
                    teaser = item.text("description"),
                    channel = channel.labelZh,
                    link = link,
                    publishedAt = runCatching {
                        ZonedDateTime.parse(item.text("pubDate"), DateTimeFormatter.RFC_1123_DATE_TIME)
                            .toInstant().toEpochMilli()
                    }.getOrNull(),
                )
            }.distinctBy { it.key }.take(MAX_CARDS)
        }

        private fun Element.text(tag: String): String =
            getElementsByTagName(tag).item(0)?.textContent.orEmpty().trim()

        /**
         * 文章页 → 正文段落。从 `class="wsw"` 起，到生词表的下划线、「Words in This Story」
         * 或评论区为止，取其中的 `<p>`；播放器占位文字丢掉。
         */
        internal fun extractBody(html: String): List<String> {
            val start = html.indexOf("class=\"wsw\"")
            if (start < 0) return emptyList()
            val rest = html.substring(start)
            val end = END_MARKERS.map { rest.indexOf(it) }.filter { it > 0 }.minOrNull() ?: rest.length
            return PARAGRAPH.findAll(rest.substring(0, end))
                .map { decode(it.groupValues[1].replace(BREAK, " ").replace(TAG, "")).replace(SPACES, " ").trim() }
                .filter { it.isNotEmpty() && JUNK.none { junk -> it.equals(junk, ignoreCase = true) } }
                .toList()
        }

        private val END_MARKERS = listOf("____", "Words in This Story", "id=\"comments\"")
        private val JUNK = listOf("No media source currently available", "See comments", "Share")
        private val PARAGRAPH = Regex("""<p\b[^>]*>(.*?)</p>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        private val BREAK = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
        private val TAG = Regex("<[^>]+>")
        private val SPACES = Regex("""[\s ]+""")
        private val ENTITY = Regex("""&(#?[xX]?[0-9a-zA-Z]+);""")
        private val NAMED = mapOf(
            "nbsp" to " ", "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
            "ldquo" to "“", "rdquo" to "”", "lsquo" to "‘", "rsquo" to "’", "mdash" to "—", "ndash" to "–",
            "hellip" to "…",
        )

        private fun decode(text: String): String = ENTITY.replace(text) { match ->
            val name = match.groupValues[1]
            when {
                name.startsWith("#x") || name.startsWith("#X") ->
                    name.drop(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: match.value
                name.startsWith("#") -> name.drop(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: match.value
                else -> NAMED[name] ?: match.value
            }
        }
    }
}
