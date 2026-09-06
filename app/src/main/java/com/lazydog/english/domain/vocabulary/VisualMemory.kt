package com.lazydog.english.domain.vocabulary

import kotlinx.serialization.Serializable

/**
 * 单词视觉记忆图片的领域模型（`单词视觉记忆图片DESIGN.md`）。
 *
 * 这个模块最容易做错成「拿单词去搜一下，取第一张」。这里所有类型都在拦这件事：
 * 图片绑的是 [SenseKey]（一个词义）而不是词形，检索词是单独生成的产物
 * （[VisualSearchPlan]），候选要先过一遍本地过滤再排（[VisualCandidateFilter]），
 * 而「这个词义不该有图」（[ImageStrategy.None]）是合法结果，不是失败。
 */

/**
 * 图片绑定的那个词义。
 *
 * 已入库的词用 `item:<itemId>`——知识库里一个 itemId 就是一个词义
 * （`单词记忆DESIGN.md` §3：身份键是 lemma+pos，一个词条下每个词义各成一条）。
 * 还没添加的草稿卡没有 itemId，用词形、词性和释义拼一个稳定键，这样"预览时找到的图"
 * 在用户按下「添加」之后不用重搜。
 */
@JvmInline
value class SenseKey(val value: String) {
    companion object {
        fun of(itemId: Long): SenseKey = SenseKey("item:$itemId")

        fun ofDraft(term: String, pos: String, meaningZh: String): SenseKey = SenseKey(
            "draft:" + listOf(term, pos, meaningZh).joinToString("|") {
                it.trim().lowercase().replace(Regex("\\s+"), " ")
            },
        )
    }
}

/** 这个词义该用什么形式的图（设计文档 §6）。MVP 只真正走 [ObjectPhoto] 到 [Diagram] 这几条。 */
@Serializable
enum class ImageStrategy {
    ObjectPhoto,
    ActionScene,
    StateScene,
    SpatialRelation,
    Comparison,
    Diagram,
    VisualMetaphor,
    /** 没有能说清这个词义的画面。这是合法结论，不是"没搜到"。 */
    None,
    ;

    companion object {
        /** 模型给的是 snake_case；认不出的一律当作最保守的那种，不硬凑一个策略。 */
        fun normalize(raw: String): ImageStrategy {
            val key = raw.trim().lowercase().replace("_", "").replace(" ", "")
            return entries.firstOrNull { it.name.lowercase() == key } ?: ObjectPhoto
        }
    }
}

/** 为什么这个词义现在没有图（设计文档 §30）。用户看到的是这些原因翻出来的一句话。 */
@Serializable
enum class ImageFailureReason(val messageZh: String) {
    NotVisualizable("这个词不太适合用图片说清楚"),
    LowSemanticMatch("这次没找到能说清这个意思的图"),
    NoResults("这次没搜到图"),
    ApiFailure("图片搜索没能连上"),
    NotConfigured("还没填 Brave 搜索密钥"),
    HiddenByUser("你选择了这个词不用配图"),
}

/**
 * 一次视觉检索的计划：模型判断完之后产出的东西（设计文档 §12、§26）。
 *
 * [visualizable] 为 false 时后面整条链路都不走——不搜、不存候选、界面上连图片区都不渲染。
 */
@Serializable
data class VisualSearchPlan(
    val visualizable: Boolean,
    /** 0..1，设计文档 §5 的分档。低于 [VisualQueryValidation.MIN_VISUALIZABILITY] 直接放弃。 */
    val visualizability: Double = 0.0,
    val strategy: ImageStrategy = ImageStrategy.None,
    val primaryQuery: String = "",
    /** 主查询没搜到好图时依次退到这里，最多两条（设计文档 §29：不许无限扩大搜索）。 */
    val fallbackQueries: List<String> = emptyList(),
    /** 这张图上应该看得见什么。也用来生成 contentDescription——比念网页标题有信息得多。 */
    val visualTarget: String = "",
    val mustShow: List<String> = emptyList(),
    val avoid: List<String> = emptyList(),
    val reasonZh: String = "",
) {
    /** 主查询在前、备用在后，去掉空的和重复的。 */
    val queries: List<String>
        get() = (listOf(primaryQuery) + fallbackQueries)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
}

/**
 * 让模型判断"这个词义能不能画出来、该怎么搜"的请求（设计文档 §13）。
 *
 * 必须带 [meaningZh]：只发词形的话，模型对 `charge` 只能瞎猜是充电还是指控，
 * 这正是整个模块存在的理由（§1）。
 */
data class VisualSearchRequest(
    val term: String,
    val meaningZh: String,
    val pos: String = "",
    val exampleEn: String = "",
)

/** 一个候选图（设计文档 §34）。存的是引用，不是图本身——见 [rightsNoteZh]。 */
@Serializable
data class VocabularyImageAsset(
    /** 缩略图地址。Brave 的图片代理出的，约 500px 宽，正是词卡里要的尺寸。 */
    val thumbnailUrl: String,
    /** 原图地址。只在缩略图挂掉时兜底，不下载、不进自己的存储。 */
    val originalUrl: String = "",
    /** 图片所在的网页。来源行点开去这里。 */
    val sourcePageUrl: String = "",
    val publisher: String = "",
    val title: String = "",
    val width: Int = 0,
    val height: Int = 0,
    /** 本地排序分，0..1。MVP 不跑 Vision，这个分只由标题、来源和尺寸推出来。 */
    val score: Double = 0.0,
) {
    /**
     * Brave 负责发现，不等于拿到第三方图片的版权（设计文档 §33）。
     * 界面上必须常驻这句话和来源，别让人以为这是 App 自己的素材。
     */
    val rightsNoteZh: String get() = "图片版权归原站所有 · 点开看出处"

    val hostLabel: String
        get() = publisher.ifBlank {
            runCatching { java.net.URI(sourcePageUrl.ifBlank { thumbnailUrl }).host.orEmpty() }
                .getOrDefault("")
                .removePrefix("www.")
        }
}

/** 一个词义当前的图片状态，界面直接按它渲染（设计文档 §63）。 */
@Serializable
data class SenseVisualState(
    val senseKey: String,
    val term: String = "",
    val meaningZh: String = "",
    val strategy: ImageStrategy = ImageStrategy.None,
    val query: String = "",
    val visualTarget: String = "",
    /** 排好序的候选，最多 [VisualCandidateFilter.KEEP] 张：默认用第 0 张，「换一张」在这里挑。 */
    val assets: List<VocabularyImageAsset> = emptyList(),
    val selectedIndex: Int = 0,
    val failure: ImageFailureReason? = null,
    /** 用户说过「这个词不用配图」。按词义记，不影响别的词。 */
    val hiddenByUser: Boolean = false,
) {
    val selected: VocabularyImageAsset?
        get() = assets.getOrNull(selectedIndex.coerceAtLeast(0))

    /** 有没有东西可显示。没有的话界面收成一行，不留空框。 */
    val hasImage: Boolean get() = !hiddenByUser && selected != null

    /**
     * 整块（含标题）都不渲染的情况：这个词义本来就不该有图，或者用户关掉了。
     * 和「搜过但没搜到」不同——后者要留一个「再找一次」。
     */
    val silent: Boolean
        get() = hiddenByUser || failure == ImageFailureReason.NotVisualizable ||
            failure == ImageFailureReason.NotConfigured

    /** 无障碍朗读用的描述：说图上有什么，不念网页标题（网页标题经常是广告文案）。 */
    fun contentDescriptionZh(): String =
        visualTarget.ifBlank { if (meaningZh.isBlank()) "这个词的记忆图片" else "$meaningZh 的记忆图片" }
}

/**
 * 调用 Brave 之前先检查这条检索词值不值得发出去（设计文档 §56、§57）。
 *
 * 不做语义判断——那是模型的活。这里只拦几种一眼可见的坏查询：
 * 只有单词本身、太短或太长、带着"concept/meaning"这类必然搜出商业图库的词。
 */
object VisualQueryValidation {

    /** 低于这个可视觉化程度就不搜（设计文档 §5.4：功能词不建议普通图片搜索）。 */
    const val MIN_VISUALIZABILITY = 0.40

    /** 设计文档 §14：4~10 个英文词。少于 2 个词的查询等于没写检索词。 */
    const val MIN_WORDS = 2
    const val MAX_WORDS = 12

    /** 这些词一进查询，回来的基本是 stock photo 和大字海报（设计文档 §10）。 */
    private val STOCK_WORDS = listOf(
        "concept", "abstract", "symbol", "icon", "logo", "banner",
        "typography", "quote", "poster", "wallpaper", "clipart",
    )

    /** @return 不合格的原因，null 表示可以发出去。 */
    fun problem(query: String, term: String): String? {
        val text = query.trim()
        val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        return when {
            text.isEmpty() -> "检索词是空的"
            words.size < MIN_WORDS -> "检索词只有一个词，等于直接拿单词去搜"
            words.size > MAX_WORDS -> "检索词过长，搜索引擎会当成一句话"
            term.isNotBlank() && text.equals(term.trim(), ignoreCase = true) -> "检索词就是这个单词本身"
            STOCK_WORDS.any { stock -> words.any { it.lowercase().trim(',', '.') == stock } } ->
                "检索词里有会招来商业图库的词"
            else -> null
        }
    }

    /** 计划整体能不能用；不能用时后面一步都不做。 */
    fun problem(plan: VisualSearchPlan, term: String): String? = when {
        !plan.visualizable -> null // 合法结论，不是问题
        plan.visualizability < MIN_VISUALIZABILITY -> "这个词义的可视觉化程度太低"
        plan.queries.isEmpty() -> "没有生成任何检索词"
        problem(plan.primaryQuery, term) != null -> problem(plan.primaryQuery, term)
        else -> null
    }
}

/**
 * 候选的本地硬过滤和排序（设计文档 §19、§21）。
 *
 * MVP 不跑 Vision（§28）：这里能用的只有尺寸、来源和标题描述，所以判断也只做这几件事，
 * 不假装自己在做语义匹配。真正的排序质量靠"用户换了几次图"来暴露。
 */
object VisualCandidateFilter {

    /** 每个词义留几张：默认第一张，其余给「换一张」（设计文档 §18、§37）。 */
    const val KEEP = 3

    const val MIN_WIDTH = 200
    const val MIN_HEIGHT = 150

    /** 长条形的图在 16:9 里裁完基本看不出内容。 */
    private const val MAX_ASPECT = 3.0

    /**
     * 明显不适合出现在学习产品里的来源。这张表短是故意的：
     * 长黑名单维护不动，也挡不住真正的问题，主力仍然是 `safesearch=strict`。
     */
    private val BLOCKED_HOSTS = listOf(
        "pinterest.", "quizlet.com", "slideshare.net", "123rf.com", "dreamstime.com",
        "shutterstock.com", "istockphoto.com", "alamy.com", "gettyimages.",
    )

    /** 标题里出现这些，多半是"写着这个单词的图"或者商品页（设计文档 §19 text-heavy）。 */
    private val TEXT_HEAVY_HINTS = listOf(
        "meaning", "definition", "vocabulary", "flashcard", "worksheet", "quiz",
        "clipart", "font", "typography", "poster", "buy", "sale", "price", "shop",
        "stock photo", "royalty", "logo",
    )

    /**
     * 过滤 + 排序 + 截断。
     *
     * [mustShow] 里的词命中标题会加分，但**不做硬性要求**：Brave 的标题是网页标题，
     * 不是图片内容描述，拿它当准入条件会把大量好图误杀。
     */
    fun rank(
        candidates: List<VocabularyImageAsset>,
        term: String,
        mustShow: List<String> = emptyList(),
        avoid: List<String> = emptyList(),
    ): List<VocabularyImageAsset> {
        val seenHosts = mutableSetOf<String>()
        val seenUrls = mutableSetOf<String>()
        return candidates
            .asSequence()
            .filter { it.thumbnailUrl.isNotBlank() }
            .filter { usableSize(it) }
            .filter { asset -> BLOCKED_HOSTS.none { it in asset.hostLabel.lowercase() } }
            .filter { seenUrls.add(it.thumbnailUrl) }
            .map { it.copy(score = score(it, term, mustShow, avoid)) }
            .filter { it.score > 0 }
            .sortedByDescending { it.score }
            // 同一个站最多留一张：三张候选全来自同一个页面，等于只有一张
            // （设计文档 §19 的 source dedupe）。
            .filter { seenHosts.add(it.hostLabel.lowercase().ifBlank { it.thumbnailUrl }) }
            .take(KEEP)
            .toList()
    }

    /** 尺寸信息缺失时按可用处理：Brave 不保证每条都带 width/height，缺了不该等于淘汰。 */
    private fun usableSize(asset: VocabularyImageAsset): Boolean {
        if (asset.width <= 0 || asset.height <= 0) return true
        if (asset.width < MIN_WIDTH || asset.height < MIN_HEIGHT) return false
        val ratio = asset.width.toDouble() / asset.height
        return ratio <= MAX_ASPECT && ratio >= 1 / MAX_ASPECT
    }

    private fun score(
        asset: VocabularyImageAsset,
        term: String,
        mustShow: List<String>,
        avoid: List<String>,
    ): Double {
        val haystack = (asset.title + " " + asset.publisher).lowercase()
        var score = 0.55

        // 该看得见的东西出现在标题里，多半真拍了这个东西。
        val hits = mustShow.count { it.isNotBlank() && it.lowercase() in haystack }
        score += 0.10 * hits.coerceAtMost(3)

        // 标题只是把目标词重复一遍，通常是词典页、单词卡、字体图。
        if (term.isNotBlank() && haystack.trim() == term.lowercase()) score -= 0.20
        if (TEXT_HEAVY_HINTS.any { it in haystack }) score -= 0.30
        if (avoid.any { it.isNotBlank() && it.lowercase() in haystack }) score -= 0.25

        // 大图通常是照片，小图通常是图标和缩略拼贴。
        if (asset.width >= 800) score += 0.08
        if (asset.width in 1..399) score -= 0.10

        return score.coerceIn(0.0, 1.0)
    }
}
