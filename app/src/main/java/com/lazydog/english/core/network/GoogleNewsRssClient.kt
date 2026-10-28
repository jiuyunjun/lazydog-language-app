package com.lazydog.english.core.network

import com.lazydog.english.domain.generation.NewsCategory
import com.lazydog.english.domain.generation.NewsFeed
import com.lazydog.english.domain.generation.NewsFeedResult
import com.lazydog.english.domain.generation.NewsQuery
import com.lazydog.english.domain.generation.NewsStory
import com.lazydog.english.domain.generation.NewsText
import com.lazydog.english.domain.generation.RelatedCoverage
import java.io.IOException
import java.net.URLEncoder
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
 * Google 新闻的公开 RSS（D-088）。不要密钥，有版本、分区和按地名的本地新闻，
 * 每条下面还挂着同一件事别家的报道——这正是 `母语阅读DESIGN.md` §6.2 想要的"候选新闻聚类"。
 *
 * 四种地址，按"有没有地区 × 是不是要闻"选：
 * - 不限地区、要闻：首页 `rss`
 * - 不限地区、分类：分区 `rss/headlines/section/topic/TECHNOLOGY`
 * - 有地区、要闻：本地 `rss/headlines/section/geo/杭州`；国家名在这里是空的，空了改检索 `日本 when:1d`
 * - 有地区、分类：检索 `rss/search?q=杭州 体育 when:2d`（Google 没有"某城市的体育分区"）
 *
 * 在大陆要代理才连得上，所以连接超时设得短，连不上尽快交给兜底源。
 */
class GoogleNewsRssClient(
    private val okHttpClient: OkHttpClient = defaultOkHttpClient,
    private val base: String = BASE,
) : NewsFeed {

    override suspend fun headlines(query: NewsQuery): NewsFeedResult {
        val first = fetch(urlFor(query))
        // 本地分区只认城市和省份，填「日本」「美国」这种国家名拿回来是空的；
        // 那就按地名检索一天内的报道——中文媒体讲这个国家的新闻就在这里。
        if (first.stories.isEmpty() && query.location.isNotBlank() && query.category == NewsCategory.Top) {
            val searched = fetch(searchUrl(query, "${query.location.trim()} when:1d"))
            if (searched.stories.isNotEmpty()) return searched
        }
        return first
    }

    private suspend fun fetch(url: String): NewsFeedResult = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .tag(CallHooks::class.java, CallHooks(op = "新闻列表"))
            .get()
            .build()
        try {
            okHttpClient.newCall(request).await().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) return@withContext NewsFeedResult(failure = "Google 新闻返回 HTTP ${response.code}")
                val stories = parse(body)
                    ?: return@withContext NewsFeedResult(failure = "Google 新闻返回的不是 RSS")
                if (stories.isEmpty()) NewsFeedResult(failure = "这个地区和分类最近没有新闻") else NewsFeedResult(stories)
            }
        } catch (e: IOException) {
            NewsFeedResult(failure = "连不上 Google 新闻：${e.message ?: e.javaClass.simpleName}")
        }
    }

    internal fun urlFor(query: NewsQuery): String {
        val edition = query.edition
        val locale = "hl=${edition.hl}&gl=${edition.gl}&ceid=${edition.ceid}"
        val location = query.location.trim()
        val topic = query.category.googleTopic
        return when {
            location.isEmpty() && topic == null -> "$base/rss?$locale"
            location.isEmpty() -> "$base/rss/headlines/section/topic/$topic?$locale"
            query.category == NewsCategory.Top -> "$base/rss/headlines/section/geo/${encodePath(location)}?$locale"
            else -> searchUrl(query, "$location ${query.category.keyword} when:2d")
        }
    }

    private fun searchUrl(query: NewsQuery, q: String): String {
        val edition = query.edition
        return "$base/rss/search?q=${encode(q)}&hl=${edition.hl}&gl=${edition.gl}&ceid=${edition.ceid}"
    }

    internal fun parse(xml: String): List<NewsStory>? {
        val document = runCatching {
            val factory = DocumentBuilderFactory.newInstance().apply {
                // RSS 用不到 DTD 和外部实体；关掉，免得被喂一个读本地文件的 XML。
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                isExpandEntityReferences = false
            }
            factory.newDocumentBuilder().parse(xml.byteInputStream())
        }.getOrNull() ?: return null
        val items = document.getElementsByTagName("item")
        val stories = (0 until items.length).mapNotNull { i ->
            val item = items.item(i) as? Element ?: return@mapNotNull null
            val sourceElement = item.getElementsByTagName("source").item(0) as? Element
            val source = sourceElement?.textContent.orEmpty().trim()
            val title = NewsText.cleanTitle(item.text("title"), source)
            if (title.isBlank()) return@mapNotNull null
            NewsStory(
                title = title,
                source = source,
                sourceSite = sourceElement?.getAttribute("url").orEmpty().trim(),
                link = item.text("link"),
                direct = false,
                publishedAt = parseDate(item.text("pubDate")),
                related = related(item.text("description"), title),
            )
        }
        return NewsText.distinct(stories).take(MAX_STORIES)
    }

    private fun Element.text(tag: String): String =
        getElementsByTagName(tag).item(0)?.textContent.orEmpty().trim()

    /** description 是一段 `<ol><li><a>标题</a>&nbsp;<font>来源</font></li>…</ol>`，第一条通常就是本条。 */
    private fun related(description: String, title: String): List<RelatedCoverage> =
        RELATED.findAll(description).map { match ->
            val source = match.groupValues[2].stripTags()
            RelatedCoverage(title = NewsText.cleanTitle(match.groupValues[1].stripTags(), source), source = source)
        }.filter { it.title.isNotBlank() && it.title != title }.toList()

    private fun String.stripTags(): String = replace(TAG, "").replace("&nbsp;", " ").replace("&amp;", "&").trim()

    private fun parseDate(raw: String): Long? =
        runCatching { ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()

    private fun encode(text: String) = URLEncoder.encode(text, "UTF-8")

    /** 路径里的空格要是 %20，不能是 URLEncoder 给的 +。 */
    private fun encodePath(text: String) = encode(text).replace("+", "%20")

    companion object {
        const val BASE = "https://news.google.com"

        /** 列表最多留几条。去重之后十五条够挑，再多翻不完。 */
        const val MAX_STORIES = 15

        private val RELATED = Regex("""<a [^>]*>(.*?)</a>(?:\s|&nbsp;| )*<font[^>]*>(.*?)</font>""", RegexOption.DOT_MATCHES_ALL)
        private val TAG = Regex("<[^>]+>")

        private val defaultOkHttpClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .eventListenerFactory { HttpTimingListener() }
            .build()
    }
}
