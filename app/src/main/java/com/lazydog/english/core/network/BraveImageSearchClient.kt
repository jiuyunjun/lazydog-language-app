package com.lazydog.english.core.network

import com.lazydog.english.domain.vocabulary.VocabularyImageAsset
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

/** 一次图片检索的结果。失败时 [failure] 是给用户看的一句话，[hits] 为空。 */
data class ImageSearchResult(
    val hits: List<VocabularyImageAsset> = emptyList(),
    val failure: String? = null,
)

/** 一个词义要图片时，谁去搜。抽出来是为了单测和"没配密钥"这条路径能各自替换。 */
interface ImageSearchProvider {
    suspend fun isConfigured(): Boolean
    suspend fun search(query: String, count: Int = BraveImageSearchClient.DEFAULT_COUNT): ImageSearchResult
}

/**
 * Brave Image Search（`单词视觉记忆图片DESIGN.md` §16、§77）。
 *
 * 和 [BraveSearchClient] 共用同一个密钥：用户在设置页填的那一个，存在应用私有存储里
 * （`ARCHITECTURE.md` §8）。设计文档 §38 要求密钥只放后端，但这个 App 没有后端那一层，
 * 密钥是用户自己的——和 AI 密钥、Azure 密钥同一个口径（D-071）。
 *
 * 图片搜索**不支持分页**，一次要多少就是多少；一个词义 20 张候选足够（§16），
 * 拉 100 张只是多花配额。
 */
class BraveImageSearchClient(
    private val apiKey: suspend () -> String,
    private val okHttpClient: OkHttpClient = defaultOkHttpClient,
    private val endpoint: String = ENDPOINT,
) : ImageSearchProvider {

    override suspend fun isConfigured(): Boolean = apiKey().isNotBlank()

    override suspend fun search(query: String, count: Int): ImageSearchResult =
        withContext(Dispatchers.IO) {
            val key = apiKey().trim()
            if (key.isBlank()) return@withContext ImageSearchResult(failure = "没有配置 Brave 搜索密钥")
            if (query.isBlank()) return@withContext ImageSearchResult(failure = "搜索词是空的")

            val url = buildString {
                append(endpoint)
                append("?q=").append(URLEncoder.encode(query.trim(), "UTF-8"))
                append("&count=").append(count.coerceIn(1, MAX_COUNT))
                // 学习产品默认最严（§52）。即使这样也仍然要有应用层的敏感词路由。
                append("&safesearch=strict")
                append("&spellcheck=1")
                // 英文词汇学习用英文查询，结果明显更丰富；不锁用户所在国家（§15）。
                append("&search_lang=en")
                append("&country=ALL")
            }
            val request = Request.Builder()
                .url(url)
                .header("X-Subscription-Token", key)
                .header("Accept", "application/json")
                .tag(CallHooks::class.java, CallHooks(op = "图片搜索"))
                .get()
                .build()
            try {
                okHttpClient.newCall(request).await().use { response ->
                    val body = response.body?.string().orEmpty()
                    when {
                        response.isSuccessful -> parse(body)
                        response.code == 401 || response.code == 403 ->
                            ImageSearchResult(failure = "Brave 搜索密钥无效（HTTP ${response.code}）")
                        response.code == 429 ->
                            ImageSearchResult(failure = "Brave 搜索被限流了，稍后再试")
                        else -> ImageSearchResult(failure = "Brave 图片搜索失败：HTTP ${response.code}")
                    }
                }
            } catch (e: IOException) {
                ImageSearchResult(failure = "搜索网络错误：${e.message ?: e.javaClass.simpleName}")
            }
        }

    private fun parse(body: String): ImageSearchResult {
        val payload = runCatching { json.decodeFromString(ImagePayload.serializer(), body) }.getOrNull()
        // 和网页搜索一个理由：解不出来时把开头带上，否则下次还得再猜一遍是哪种坏。
            ?: return ImageSearchResult(
                failure = "Brave 返回的不是预期结构：${body.take(60).replace(Regex("\\s+"), " ")}",
            )
        val hits = payload.results.mapNotNull { result ->
            // 缩略图是 Brave 的图片代理出的，稳定、约 500px 宽、保持比例（§17），
            // 正好是词卡要的尺寸；没有缩略图时才退到原图。
            val thumbnail = result.thumbnail?.src.orEmpty().ifBlank { result.properties?.url.orEmpty() }
            if (thumbnail.isBlank()) {
                null
            } else {
                VocabularyImageAsset(
                    thumbnailUrl = thumbnail,
                    originalUrl = result.properties?.url.orEmpty(),
                    sourcePageUrl = result.url.trim(),
                    publisher = result.source.trim(),
                    title = stripTags(result.title),
                    width = result.properties?.width ?: result.thumbnail?.width ?: 0,
                    height = result.properties?.height ?: result.thumbnail?.height ?: 0,
                )
            }
        }
        return if (hits.isEmpty()) ImageSearchResult(failure = "没搜到可用图片") else ImageSearchResult(hits)
    }

    private fun stripTags(raw: String): String =
        raw.replace(TAG, "").replace("&amp;", "&").replace("&quot;", "\"").trim()

    @Serializable
    private data class ImagePayload(val results: List<ImageResult> = emptyList())

    @Serializable
    private data class ImageResult(
        val title: String = "",
        /** 图片所在的网页，不是图片本身。 */
        val url: String = "",
        val source: String = "",
        val thumbnail: ImageThumbnail? = null,
        val properties: ImageProperties? = null,
    )

    @Serializable
    private data class ImageThumbnail(
        val src: String = "",
        val width: Int? = null,
        val height: Int? = null,
    )

    @Serializable
    private data class ImageProperties(
        val url: String = "",
        @SerialName("placeholder") val placeholder: String = "",
        val width: Int? = null,
        val height: Int? = null,
    )

    companion object {
        const val ENDPOINT = "https://api.search.brave.com/res/v1/images/search"

        /** §16：10~30 个候选就够，不需要每次拉 100~200 张。 */
        const val DEFAULT_COUNT = 20

        /** Brave 文档给的上限。 */
        const val MAX_COUNT = 200

        private val TAG = Regex("<[^>]+>")

        private val json = Json { ignoreUnknownKeys = true }

        private val defaultOkHttpClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .eventListenerFactory { HttpTimingListener() }
            .build()
    }
}
