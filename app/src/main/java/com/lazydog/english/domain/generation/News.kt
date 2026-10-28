package com.lazydog.english.domain.generation

import kotlinx.serialization.Serializable

/**
 * 母语阅读「今日热点」的领域模型（D-088，`母语阅读DESIGN.md` §6.2 模式 B）。
 *
 * 整条链路：`版本 + 地区 + 分类 → 新闻列表 → 挑一条 → 找原文链接 → 抓正文 → AI 只做清理 → 英语替换`。
 * 这里的类型都在拦一件事：**新闻写出来必须是原文，不是模型的转述**。
 * 所以列表只给标题和出处，正文一定从报道网页上抓，AI 那一步只能删、不能改（[NewsCleanup]）。
 */

/** 新闻原文的语言。不是中文的，读之前要全文翻成中文（D-089）。 */
enum class NewsLanguage(val labelZh: String, val maxSourceChars: Int) {
    Chinese("中文", 4_000),
    /** 英文字符多、信息密度低，同样篇幅给得宽一些；翻成中文大约是原文字符数的一半。 */
    English("英文", 5_000),
    /** 日文、韩文翻成中文篇幅相近，收紧一点，免得一篇译文读不完。 */
    Japanese("日文", 2_500),
    Korean("韩文", 2_500),
    ;

    val needsTranslation: Boolean get() = this != Chinese
}

/**
 * 看哪里的媒体。决定的是**谁来报**：用哪国/地区的媒体、什么口径、什么语言；
 * 「报哪里」由地区决定。外文媒体的新闻全文翻成中文再读（D-089）。
 */
enum class NewsEdition(
    val labelZh: String,
    val language: NewsLanguage,
    /** Google 新闻的 hl / gl / ceid 三件套。 */
    val hl: String,
    val gl: String,
    val ceid: String,
    /** Brave 兜底时的 country / search_lang。 */
    val braveCountry: String,
    val braveLang: String,
) {
    Mainland("中国大陆", NewsLanguage.Chinese, "zh-CN", "CN", "CN:zh-Hans", "CN", "zh-hans"),
    HongKong("香港", NewsLanguage.Chinese, "zh-HK", "HK", "HK:zh-Hant", "HK", "zh-hant"),
    Taiwan("台湾", NewsLanguage.Chinese, "zh-TW", "TW", "TW:zh-Hant", "TW", "zh-hant"),
    Japan("日本", NewsLanguage.Japanese, "ja", "JP", "JP:ja", "JP", "ja"),
    UnitedStates("美国", NewsLanguage.English, "en-US", "US", "US:en", "US", "en"),
    UnitedKingdom("英国", NewsLanguage.English, "en-GB", "GB", "GB:en", "GB", "en"),
    Korea("韩国", NewsLanguage.Korean, "ko", "KR", "KR:ko", "KR", "ko"),
    ;

    companion object {
        fun fromName(name: String): NewsEdition = entries.firstOrNull { it.name == name } ?: Mainland
    }
}

/**
 * 新闻分类。[googleTopic] 是 Google 新闻的分区名，`null` 表示首页要闻。
 * 关键词用在"地区 + 分类"的检索里和 Brave 兜底里——这两条路都没有分区，只能靠词，
 * 而且得是那一版媒体的语言：拿「体育」去搜日本媒体搜不到东西。
 */
enum class NewsCategory(
    val labelZh: String,
    val googleTopic: String?,
    private val zh: String,
    private val ja: String,
    private val en: String,
    private val ko: String,
) {
    Top("要闻", null, "新闻", "ニュース", "news", "뉴스"),
    Nation("国内", "NATION", "国内新闻", "国内", "national news", "국내"),
    World("国际", "WORLD", "国际新闻", "国際", "world news", "국제"),
    Business("财经", "BUSINESS", "财经", "経済", "business", "경제"),
    Tech("科技", "TECHNOLOGY", "科技", "テクノロジー", "technology", "기술"),
    Sports("体育", "SPORTS", "体育", "スポーツ", "sports", "스포츠"),
    Entertainment("娱乐", "ENTERTAINMENT", "娱乐", "エンタメ", "entertainment", "연예"),
    Science("科学", "SCIENCE", "科学", "科学", "science", "과학"),
    Health("健康", "HEALTH", "健康", "健康", "health", "건강"),
    ;

    fun keyword(language: NewsLanguage): String = when (language) {
        NewsLanguage.Chinese -> zh
        NewsLanguage.Japanese -> ja
        NewsLanguage.English -> en
        NewsLanguage.Korean -> ko
    }

    companion object {
        fun fromName(name: String): NewsCategory = entries.firstOrNull { it.name == name } ?: Top
    }
}

/** 一次列表请求要什么。[location] 是用户写的地名，空表示不限。 */
data class NewsQuery(
    val edition: NewsEdition,
    val category: NewsCategory,
    val location: String = "",
)

/** 同一件事别家的报道。Google 新闻把它们挂在每条下面，我们拿来显示"另有几家"和兜底找原文。 */
data class RelatedCoverage(val title: String, val source: String)

/**
 * 列表里的一条新闻。
 *
 * [link] 是 Google 新闻的跳转链接时打不开原文（那是要跑脚本才能跳的中转页），
 * 只有 [direct] 为 true（Brave 兜底给的）才是报道本身的地址。
 */
data class NewsStory(
    val title: String,
    val source: String,
    /** 来源站点首页，如 `https://www.thepaper.cn`。找原文时优先认这个域名。 */
    val sourceSite: String = "",
    val link: String = "",
    val direct: Boolean = false,
    /** 发布时间，epoch 毫秒；拿不到是 null。 */
    val publishedAt: Long? = null,
    val related: List<RelatedCoverage> = emptyList(),
    /** 外文标题的中文译文，列表上给人看；中文新闻和没译成的为空（D-089）。 */
    val titleZh: String = "",
)

data class NewsFeedResult(
    val stories: List<NewsStory> = emptyList(),
    val failure: String? = null,
    /** 这批来自兜底源（Google 连不上时的 Brave）。界面要说一声，质量会差一些。 */
    val fromFallback: Boolean = false,
)

/** 新闻列表从哪来。实现见 core/network。 */
interface NewsFeed {
    suspend fun headlines(query: NewsQuery): NewsFeedResult
}

/** 从报道网页上抽出来的正文。[paragraphs] 是原文的段落，一个字没改过。 */
data class FetchedArticle(val url: String, val paragraphs: List<String>)

/** 抓一个网页、抽出正文。失败返回 null，原因由调用方汇总。 */
interface ArticleFetcher {
    suspend fun fetch(url: String): FetchedArticle?
}

/** 存在母语阅读材料里的出处：阅读页开头显示，点开去原文。 */
@Serializable
data class NewsSourceRef(
    val name: String,
    val url: String,
    val publishedAt: Long? = null,
    /** 原文是外文时写「日文」「英文」，阅读页标「译自日文」；中文原文为空。 */
    val translatedFrom: String = "",
    /** 外文原标题，出处行里给对照用。 */
    val originalTitle: String = "",
)

object NewsText {

    /**
     * Google 新闻的标题是「标题 - 来源」。来源另有字段，标题里这截去掉，
     * 否则文章标题、检索词都带着「- 澎湃新闻」。
     */
    fun cleanTitle(title: String, source: String): String {
        val trimmed = title.trim()
        if (source.isBlank()) return trimmed
        val suffix = " - ${source.trim()}"
        return if (trimmed.endsWith(suffix)) trimmed.dropLast(suffix.length).trim() else trimmed
    }

    /** 列表上的"3 小时前"。超过一周直接写日期，不写"23 天前"。 */
    fun ageZh(publishedAt: Long?, now: Long): String {
        if (publishedAt == null) return ""
        val minutes = ((now - publishedAt) / 60_000).coerceAtLeast(0)
        return when {
            minutes < 1 -> "刚刚"
            minutes < 60 -> "$minutes 分钟前"
            minutes < 24 * 60 -> "${minutes / 60} 小时前"
            minutes < 7 * 24 * 60 -> "${minutes / (24 * 60)} 天前"
            else -> java.time.Instant.ofEpochMilli(publishedAt)
                .atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()
        }
    }

    /**
     * 两个标题像不像，0..1。按汉字/字母二元组的 Jaccard 算：
     * 转载常常改几个字、加个前缀，整串比较认不出来，按词切又切不了中文。
     */
    fun similarity(a: String, b: String): Float {
        val x = bigrams(a)
        val y = bigrams(b)
        if (x.isEmpty() || y.isEmpty()) return 0f
        return (x intersect y).size.toFloat() / (x union y).size
    }

    private fun bigrams(text: String): Set<String> {
        val chars = text.lowercase().filter { it.isLetterOrDigit() }
        return if (chars.length < 2) setOf(chars) - "" else chars.windowed(2).toSet()
    }

    /** 列表去重：转载同一件事的，按标题相似度只留第一条。 */
    fun distinct(stories: List<NewsStory>, threshold: Float = 0.6f): List<NewsStory> {
        val kept = mutableListOf<NewsStory>()
        stories.forEach { story ->
            if (kept.none { similarity(it.title, story.title) >= threshold }) kept += story
        }
        return kept
    }

    fun hostOf(url: String): String =
        runCatching { java.net.URI(url.trim()).host.orEmpty() }.getOrDefault("").removePrefix("www.").lowercase()
}

/**
 * 给挑中的新闻找原文：Brave 搜这个标题 → 按"同站优先、标题够像"挑链接 → 一个个抓，抓到正文就停。
 *
 * Google 新闻的链接是中转页，打不开原文，所以绕一道检索拿报道本身的地址。
 * 这一步是"写不写得出原汁原味的新闻"的关键，宁可说"没拿到原文"也不拿摘要凑。
 */
class NewsArticleLoader(
    private val search: WebSearchProvider,
    private val fetcher: ArticleFetcher,
) {

    sealed interface Result {
        data class Success(val article: FetchedArticle) : Result
        data class Failure(val reason: String) : Result
    }

    suspend fun load(story: NewsStory): Result {
        val tried = mutableSetOf<String>()
        suspend fun attempt(url: String): FetchedArticle? {
            if (tried.size >= MAX_FETCHES || !tried.add(url)) return null
            return fetcher.fetch(url)?.takeIf(::usable)
        }

        // 兜底源给的就是报道本身的地址，先试它，省一次检索。
        if (story.direct && story.link.isNotBlank()) attempt(story.link)?.let { return Result.Success(it) }

        if (!search.isConfigured()) {
            return Result.Failure("找原文要用 Brave 搜索，请先在设置里填 Brave 密钥")
        }
        val found = search.search(story.title, count = SEARCH_COUNT, freshness = WebSearchProvider.FRESH_WEEK)
        val candidates = rankCandidates(story, found.hits)
        if (candidates.isEmpty() && tried.isEmpty()) {
            return Result.Failure("没找到这条新闻的原文链接" + (found.failure?.let { "：$it" } ?: ""))
        }
        candidates.forEach { url -> attempt(url)?.let { return Result.Success(it) } }
        return Result.Failure("试了 ${tried.size} 个原文链接，都没取出正文（可能要登录、是视频页或被网站挡了）")
    }

    /**
     * 同一来源站的排最前；其余只留标题够像的，按像的程度排。
     * 不够像的不要：同一个搜索词常常搜回一年前的旧闻或者别的事。
     */
    internal fun rankCandidates(story: NewsStory, hits: List<WebSearchHit>): List<String> {
        val sourceHost = NewsText.hostOf(story.sourceSite)
        return hits
            .filter { it.url.isNotBlank() }
            .map { hit ->
                val sameSite = sourceHost.isNotEmpty() && NewsText.hostOf(hit.url).endsWith(sourceHost)
                Triple(hit.url, sameSite, NewsText.similarity(story.title, hit.title))
            }
            .filter { (_, sameSite, sim) -> (sameSite && sim >= SAME_SITE_SIMILARITY) || sim >= MIN_SIMILARITY }
            .sortedWith(compareByDescending<Triple<String, Boolean, Float>> { it.second }.thenByDescending { it.third })
            .map { it.first }
    }

    private fun usable(article: FetchedArticle): Boolean =
        article.paragraphs.size >= MIN_PARAGRAPHS && article.paragraphs.sumOf { it.length } >= MIN_CHARS

    companion object {
        const val SEARCH_COUNT = 10
        const val MAX_FETCHES = 3
        const val MIN_SIMILARITY = 0.45f
        const val SAME_SITE_SIMILARITY = 0.25f
        const val MIN_PARAGRAPHS = 3
        const val MIN_CHARS = 200
    }
}

/**
 * AI 清理新闻原文这一步的输入和本地校验（AI_CONTRACTS「母语阅读新闻清理」）。
 *
 * 用户要的是原汁原味：AI 只能决定**留哪几段**、以及把段首段尾的杂质
 * （「记者 xx 报道」「责任编辑」「点击查看大图」）切掉。它交回来的每一段都必须是对应原段落里
 * 连续的一截——不是的话就当它改写了，退回原段落，不用它的版本。
 */
object NewsCleanup {

    /**
     * 交给 AI 的原文最多这么多段；字数上限按语言（[NewsLanguage.maxSourceChars]）。
     * 新闻是倒金字塔，砍尾巴损失最小。
     */
    const val MAX_SOURCE_PARAGRAPHS = 30
    val MAX_SOURCE_CHARS = NewsLanguage.Chinese.maxSourceChars

    /** 清理后最多留几段。阅读页超过这个数就碎了（`NativeReadingValidation`）。 */
    const val MAX_KEPT = 14

    fun trimSource(paragraphs: List<String>, maxChars: Int = MAX_SOURCE_CHARS): List<String> {
        var total = 0
        return paragraphs.take(MAX_SOURCE_PARAGRAPHS).takeWhile { p ->
            val fits = total == 0 || total + p.length <= maxChars
            total += p.length
            fits
        }
    }

    /** AI 说"留第 [index] 段，内容是 [text]"。 */
    data class Kept(val index: Int, val text: String)

    data class Outcome(val paragraphs: List<String>, val notes: List<String>)

    /**
     * 按 AI 的选择拼出正文，处处以原文为准：
     * 序号越界或重复的丢掉；[Kept.text] 不是原段落里的连续一截就用原段落；顺序按原文。
     */
    fun apply(source: List<String>, kept: List<Kept>): Outcome {
        val notes = mutableListOf<String>()
        val used = mutableSetOf<Int>()
        val paragraphs = kept
            .filter { it.index in source.indices && used.add(it.index) }
            .sortedBy { it.index }
            .mapNotNull { item ->
                val original = source[item.index]
                val text = item.text.trim()
                when {
                    text.isEmpty() -> original
                    normalize(original).contains(normalize(text)) -> text
                    else -> {
                        notes += "第 ${item.index + 1} 段 AI 改了字，已退回原文"
                        original
                    }
                }.takeIf { it.isNotBlank() }
            }
        if (paragraphs.size > MAX_KEPT) notes += "原文段落较多，只保留前 $MAX_KEPT 段"
        return Outcome(paragraphs.take(MAX_KEPT), notes)
    }

    private fun normalize(text: String) = text.filterNot { it.isWhitespace() }
}

/**
 * 外文新闻全文翻译的本地校验（D-089，AI_CONTRACTS「母语阅读外文新闻翻译」）。
 *
 * 和 [NewsCleanup] 一样，AI 能决定的只有**留哪几段**；留下的每一段必须整段译出来。
 * 译文核对不了"一字不差"，能核对的是两件最容易出的错：
 * - **偷偷写成摘要**：译文比原文短太多。按语言给一个最低篇幅比（中文字数 / 原文字符数）。
 * - **没翻**：还是满屏假名、韩文或英文。
 */
object NewsTranslation {

    /** AI 说"第 [index] 段译成 [textZh]"。 */
    data class Translated(val index: Int, val textZh: String)

    data class Outcome(val paragraphs: List<String>, val notes: List<String>, val failure: String? = null)

    /**
     * 中文译文字数 / 原文字符数的下限。按常见篇幅比的一半左右定：
     * 英文 1000 字符通常译成 300~450 个汉字，日文译中文篇幅接近，韩文略短。
     * 低于这个基本就是摘要了。
     */
    internal fun minRatio(language: NewsLanguage): Float = when (language) {
        NewsLanguage.English -> 0.18f
        NewsLanguage.Japanese -> 0.45f
        NewsLanguage.Korean -> 0.3f
        NewsLanguage.Chinese -> 0.8f
    }

    fun apply(source: List<String>, translated: List<Translated>, language: NewsLanguage): Outcome {
        val notes = mutableListOf<String>()
        val used = mutableSetOf<Int>()
        val kept = translated
            .filter { it.index in source.indices && it.textZh.isNotBlank() && used.add(it.index) }
            .sortedBy { it.index }
        val untranslated = kept.filter { !looksChinese(it.textZh) }
        if (untranslated.isNotEmpty()) notes += "${untranslated.size} 段没有翻成中文，已去掉"
        val good = (kept - untranslated.toSet()).take(NewsCleanup.MAX_KEPT)
        if (kept.size - untranslated.size > NewsCleanup.MAX_KEPT) notes += "原文段落较多，只保留前 ${NewsCleanup.MAX_KEPT} 段"

        val sourceChars = good.sumOf { source[it.index].length }
        val zhChars = good.sumOf { it.textZh.trim().length }
        val failure = when {
            good.isEmpty() -> "没有拿到可用的中文译文"
            sourceChars > 0 && zhChars < sourceChars * minRatio(language) ->
                "译文比原文短太多（$zhChars 字 / 原文 $sourceChars 字符），像是写成了摘要"
            else -> null
        }
        return Outcome(good.map { it.textZh.trim() }, notes, failure)
    }

    /**
     * 是不是中文：假名、韩文几乎没有（人名地名偶尔夹一点），拉丁字母不过半（缩写、英文人名会有）。
     * 日文汉字也是汉字，所以必须看假名，不能只看有没有汉字。
     */
    internal fun looksChinese(text: String): Boolean {
        var han = 0
        var kana = 0
        var hangul = 0
        var latin = 0
        text.forEach { c ->
            when {
                c in '぀'..'ヿ' -> kana++
                c in '가'..'힯' || c in 'ᄀ'..'ᇿ' -> hangul++
                Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HAN -> han++
                c in 'a'..'z' || c in 'A'..'Z' -> latin++
            }
        }
        val letters = han + kana + hangul + latin
        if (letters == 0 || han == 0) return false
        return (kana + hangul).toFloat() / letters < 0.05f && latin.toFloat() / letters < 0.5f
    }
}
