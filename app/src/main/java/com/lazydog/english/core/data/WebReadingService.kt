package com.lazydog.english.core.data

import com.lazydog.english.core.network.SimpleWikipediaClient
import com.lazydog.english.core.network.VoaLearningEnglishClient
import com.lazydog.english.domain.generation.NewsArticleLoader
import com.lazydog.english.domain.generation.NewsCategory
import com.lazydog.english.domain.generation.NewsEdition
import com.lazydog.english.domain.generation.NewsFeed
import com.lazydog.english.domain.generation.NewsQuery
import com.lazydog.english.domain.generation.NewsStory
import com.lazydog.english.domain.reading.ReadingDifficulty
import com.lazydog.english.domain.reading.VoaChannel
import com.lazydog.english.domain.reading.WebReadingArticle
import com.lazydog.english.domain.reading.WebReadingCard
import com.lazydog.english.domain.reading.WebReadingFeed
import com.lazydog.english.domain.reading.WebReadingRules
import com.lazydog.english.domain.reading.WebReadingSource
import com.lazydog.english.domain.vocabulary.DictionaryLookup
import com.lazydog.english.domain.vocabulary.VocabularyCandidates
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * 日常阅读的网上材料（D-098 R1，`读书与网络阅读DESIGN.md` §2）：列表、打开、估难度、存库。
 *
 * 三个来源各自失败互不影响：VOA、维基在中国大陆通常要代理，新闻原文要搜索服务的密钥。
 * 页面只管状态，所有"去哪抓、抓到怎么存"都在这里。
 */
class WebReadingService(
    private val voa: VoaLearningEnglishClient,
    private val wiki: SimpleWikipediaClient,
    private val newsFeed: NewsFeed,
    private val newsLoader: NewsArticleLoader,
    private val dictionary: DictionaryLookup,
    private val readings: ReadingRepository,
    private val knowledge: KnowledgeRepository,
    private val preferences: UserPreferences,
) {

    /** 新闻列表里的条目要靠原始 [NewsStory] 找原文（来源站点、是否直链），按卡片键留一份。 */
    private val stories = ConcurrentHashMap<String, NewsStory>()

    sealed interface Opened {
        data class Success(val materialId: Long) : Opened
        data class Failure(val reason: String) : Opened
    }

    suspend fun voa(channel: VoaChannel): WebReadingFeed = voa.list(channel)

    suspend fun wiki(query: String): WebReadingFeed = wiki.list(query)

    /** 英文新闻：美国版 Google 新闻按分类取。原文要经网页搜索找回，见 [NewsArticleLoader]。 */
    suspend fun news(category: NewsCategory, edition: NewsEdition = NewsEdition.UnitedStates): WebReadingFeed {
        val result = newsFeed.headlines(NewsQuery(edition = edition, category = category))
        if (result.stories.isEmpty()) return WebReadingFeed(failure = result.failure ?: "暂时没有新闻")
        val cards = result.stories.map { story ->
            val key = story.link.ifBlank { story.title }
            stories[key] = story
            WebReadingCard(
                source = WebReadingSource.News,
                key = key,
                title = story.title,
                channel = story.source,
                link = story.link,
                publishedAt = story.publishedAt,
            )
        }
        return WebReadingFeed(cards)
    }

    /** 抓正文、估难度、存库。已经存过的直接打开那一篇。 */
    suspend fun open(card: WebReadingCard): Opened {
        if (card.source != WebReadingSource.News && card.link.isNotBlank()) {
            readings.findBySourceUrl(card.link)?.let { return Opened.Success(it.id) }
        }
        val article = when (card.source) {
            WebReadingSource.Voa -> voa.load(card)
                ?: return Opened.Failure("没取到这篇的正文（可能是视频页，或者连不上 VOA）")
            WebReadingSource.Wiki -> wiki.load(card)
                ?: return Opened.Failure("这个条目太短，或者连不上维基")
            WebReadingSource.News -> when (val loaded = loadNews(card)) {
                is NewsLoad.Failure -> return Opened.Failure(loaded.reason)
                is NewsLoad.Success -> loaded.article
            }
        }
        val difficulty = estimate(article.paragraphs.joinToString("\n"))
        return Opened.Success(readings.saveWeb(article, difficulty))
    }

    private sealed interface NewsLoad {
        data class Success(val article: WebReadingArticle) : NewsLoad
        data class Failure(val reason: String) : NewsLoad
    }

    private suspend fun loadNews(card: WebReadingCard): NewsLoad {
        val story = stories[card.key] ?: NewsStory(title = card.title, source = card.channel, link = card.link)
        return when (val loaded = newsLoader.load(story)) {
            is NewsArticleLoader.Result.Failure -> NewsLoad.Failure(loaded.reason)
            is NewsArticleLoader.Result.Success -> {
                val paragraphs = loaded.article.paragraphs
                if (!WebReadingRules.usable(paragraphs)) return NewsLoad.Failure("原文太短，像是快讯或视频页")
                NewsLoad.Success(
                    WebReadingArticle(
                        card = card,
                        paragraphs = WebReadingRules.trim(paragraphs),
                        url = loaded.article.url,
                        attribution = card.channel,
                    ),
                )
            }
        }
    }

    /**
     * 本地估难度（§2.2）。舒适区取他取词窗口的中点：窗口下界以内早该会，上界以外还轮不到，
     * 中间一半按"大概认识"算。词典的排名是含功能词的原始语料排名，比实词排名略大，估计偏宽松一点。
     */
    suspend fun estimate(text: String): ReadingDifficulty.Estimate? = withContext(Dispatchers.IO) {
        val window = VocabularyCandidates.rankWindow(preferences.vocabLevelScore.first())
        val comfortable = (window.first + window.last) / 2
        val known = knowledge.vocabulary.first().mapTo(HashSet()) { it.detail.term.trim().lowercase() } + knowledge.knownLemmas()
        val lemmaCache = HashMap<String, String?>()
        val rankCache = HashMap<String, Int?>()
        ReadingDifficulty.estimate(
            text = text,
            lemmaOf = { form -> lemmaCache.getOrPut(form) { dictionary.lemmaCandidates(form).firstOrNull() } },
            rankOf = { lemma ->
                rankCache.getOrPut(lemma) { dictionary.entry(lemma)?.frequencyRank?.takeIf { it > 0 } }
            },
            knownTerms = known,
            comfortableRank = comfortable,
        )
    }
}
