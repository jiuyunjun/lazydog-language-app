package com.lazydog.english.core.network

import com.lazydog.english.domain.generation.NewsFeed
import com.lazydog.english.domain.generation.NewsFeedResult
import com.lazydog.english.domain.generation.NewsQuery
import com.lazydog.english.domain.generation.NewsStory
import com.lazydog.english.domain.generation.NewsText
import com.lazydog.english.domain.generation.WebSearchHit
import com.lazydog.english.domain.generation.WebSearchProvider
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.delay

/**
 * Google 新闻为主、Brave 新闻检索兜底（D-088）。
 *
 * 兜底只在 Google 那边**失败**时启用（大陆没开代理是最常见的情况），并且要用户配了 Brave 密钥。
 * Brave 没有分区和城市，只能按国家、语言加"地区 + 分类词"去搜，质量差一截，所以结果要标出来。
 */
class FallbackNewsFeed(
    private val primary: NewsFeed,
    private val brave: WebSearchProvider,
) : NewsFeed {

    override suspend fun headlines(query: NewsQuery): NewsFeedResult {
        val first = primary.headlines(query)
        if (first.failure == null || !brave.isConfigured()) return first

        val q = listOf(query.location.trim(), query.category.keyword(query.edition.language)).filter { it.isNotBlank() }.joinToString(" ")
        val edition = query.edition
        var result = brave.news(q, freshness = WebSearchProvider.FRESH_DAY, country = edition.braveCountry, searchLang = edition.braveLang)
        if (result.failure == null && result.hits.size < MIN_STORIES) {
            // 免费档每秒一次请求，隔开再发。
            delay(RATE_GAP_MILLIS)
            val week = brave.news(q, freshness = WebSearchProvider.FRESH_WEEK, country = edition.braveCountry, searchLang = edition.braveLang)
            if (week.failure == null) result = week
        }
        if (result.failure != null) {
            return NewsFeedResult(failure = "${first.failure}；Brave 兜底也没拿到：${result.failure}")
        }
        val stories = NewsText.distinct(result.hits.map(::toStory)).take(GoogleNewsRssClient.MAX_STORIES)
        return NewsFeedResult(stories = stories, fromFallback = true)
    }

    private fun toStory(hit: WebSearchHit) = NewsStory(
        title = hit.title,
        source = hit.source,
        sourceSite = if (hit.source.isBlank()) "" else "https://${hit.source}",
        link = hit.url,
        direct = true,
        publishedAt = parseIso(hit.age),
    )

    companion object {
        private const val MIN_STORIES = 4
        private const val RATE_GAP_MILLIS = 1_100L

        /** Brave 的 page_age 是不带时区的 ISO 时间，按 UTC 算；带时区的也认。 */
        internal fun parseIso(raw: String): Long? {
            val text = raw.trim()
            if (text.isEmpty()) return null
            return runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
                ?: runCatching { LocalDateTime.parse(text).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
        }
    }
}
