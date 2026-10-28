package com.lazydog.english.core.network

import com.lazydog.english.domain.generation.ArticleFetcher
import com.lazydog.english.domain.generation.FetchedArticle
import java.io.IOException
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 抓报道网页、抽正文（D-088）。不引入 HTML 解析库：新闻页的正文绝大多数就是一串 `<p>`，
 * 正则够用；抽不干净的段首段尾杂质交给后面的 AI 清理，抽不出来就换下一个链接。
 */
class HttpArticleFetcher(
    private val okHttpClient: OkHttpClient = defaultOkHttpClient,
) : ArticleFetcher {

    override suspend fun fetch(url: String): FetchedArticle? = withContext(Dispatchers.IO) {
        val request = runCatching {
            Request.Builder()
                .url(url)
                // 不少新闻站给"像爬虫"的请求回空壳页；用普通手机浏览器的 UA。
                .header("User-Agent", USER_AGENT)
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8,ja;q=0.7,ko;q=0.6")
                .tag(CallHooks::class.java, CallHooks(op = "新闻原文"))
                .get()
                .build()
        }.getOrNull() ?: return@withContext null
        try {
            okHttpClient.newCall(request).await().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body ?: return@withContext null
                val type = body.contentType()
                if (type != null && type.subtype != "html" && type.subtype != "xhtml+xml") return@withContext null
                val bytes = body.source().let { source ->
                    source.request(MAX_BYTES)
                    source.buffer.readByteArray(minOf(source.buffer.size, MAX_BYTES))
                }
                val html = String(bytes, charsetOf(type?.charset(), bytes))
                val paragraphs = ArticleExtractor.paragraphs(html)
                if (paragraphs.isEmpty()) null else FetchedArticle(url = response.request.url.toString(), paragraphs = paragraphs)
            }
        } catch (e: IOException) {
            null
        }
    }

    /** 响应头没写编码时看页面里的 `<meta charset>`：不少中文站还是 GBK，按 UTF-8 解就是一屏乱码。 */
    private fun charsetOf(declared: Charset?, bytes: ByteArray): Charset {
        if (declared != null) return declared
        val head = String(bytes, 0, minOf(bytes.size, 4_096), Charsets.ISO_8859_1)
        val name = META_CHARSET.find(head)?.groupValues?.get(1)?.trim()
        return name?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
    }

    companion object {
        /** 正文页 2MB 以内；再大就不是普通报道了，读了也是浪费流量。 */
        private const val MAX_BYTES = 2L * 1024 * 1024

        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

        private val META_CHARSET = Regex("""<meta[^>]+charset\s*=\s*["']?([\w-]+)""", RegexOption.IGNORE_CASE)

        private val defaultOkHttpClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .eventListenerFactory { HttpTimingListener() }
            .build()
    }
}

/** 从新闻网页的 HTML 里取正文段落。纯函数，单测直接喂 HTML。 */
internal object ArticleExtractor {

    /** 短于这个的 `<p>` 多半是按钮、图注或"分享到"。 */
    private const val MIN_LENGTH = 12

    /**
     * `<article>` 里至少这么多段或这么多字才信它；有的站把 `<article>` 用在侧栏卡片上，
     * 那种只有一两行标题。
     */
    private const val ARTICLE_MIN_PARAGRAPHS = 3
    private const val ARTICLE_MIN_CHARS = 200

    fun paragraphs(html: String): List<String> {
        val cleaned = NOISE.replace(html, " ")
        val article = ARTICLE.find(cleaned)?.groupValues?.get(1)
        val fromArticle = article?.let(::collect).orEmpty()
        val trusted = fromArticle.size >= ARTICLE_MIN_PARAGRAPHS || fromArticle.sumOf { it.length } >= ARTICLE_MIN_CHARS
        val chosen = if (trusted) fromArticle else collect(cleaned)
        // 有的页面正文和"相关阅读"都用 <p>，连着出现两次的同一段只留一次。
        return chosen.distinct()
    }

    private fun collect(html: String): List<String> =
        PARAGRAPH.findAll(html)
            .map { decode(it.groupValues[1].replace(BREAK, "\n").replace(TAG, "")) }
            .flatMap { it.split('\n').asSequence() }
            .map { it.replace(SPACES, " ").trim() }
            .filter { it.length >= MIN_LENGTH && JUNK.none { junk -> junk in it } }
            .toList()

    private fun decode(text: String): String =
        ENTITY.replace(text) { match ->
            val name = match.groupValues[1]
            when {
                name.startsWith("#x") || name.startsWith("#X") ->
                    name.drop(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: match.value
                name.startsWith("#") -> name.drop(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: match.value
                else -> NAMED[name] ?: match.value
            }
        }

    private val NOISE = Regex(
        """<(script|style|noscript|svg|nav|aside|footer|form|iframe|button)\b[^>]*>.*?</\1>|<!--.*?-->""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )
    private val ARTICLE = Regex("""<article\b[^>]*>(.*?)</article>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val PARAGRAPH = Regex("""<p\b[^>]*>(.*?)</p>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val BREAK = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
    private val TAG = Regex("<[^>]+>")
    private val SPACES = Regex("""[\s 　]+""")
    private val ENTITY = Regex("""&(#?[xX]?[0-9a-zA-Z]+);""")

    private val NAMED = mapOf(
        "nbsp" to " ", "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "ldquo" to "“", "rdquo" to "”", "lsquo" to "‘", "rsquo" to "’", "mdash" to "—", "ndash" to "–",
        "hellip" to "…", "middot" to "·", "times" to "×", "yen" to "¥", "deg" to "°",
    )

    /**
     * 整段都是站务文字的标记。只拦一眼能认出、正文里几乎不会出现的；
     * 「扫码」「举报」这种正文里也常见的词不放，拿不准的留给 AI 清理那一步。
     */
    private val JUNK = listOf(
        "版权所有", "未经授权", "禁止转载", "免责声明", "扫一扫", "下载客户端", "打开APP", "打开App",
        "点击查看大图", "分享到", "责任编辑：", "责任编辑:",
    )
}
