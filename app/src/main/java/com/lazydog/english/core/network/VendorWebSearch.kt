package com.lazydog.english.core.network

import com.lazydog.english.core.config.SearchVendor
import com.lazydog.english.domain.generation.WebSearchProvider
import com.lazydog.english.domain.generation.WebSearchResult

/**
 * 按设置里选的服务商转给 Brave 或 DeepSeek（D-090）。
 *
 * 每次调用现取一次 [vendor]，设置页切完下一次检索就生效，不用重启，也不用让
 * `LazyDogApplication` 重建依赖它的对象（热点兜底、找原文都拿着同一个实例）。
 */
class VendorWebSearch(
    private val vendor: suspend () -> SearchVendor,
    private val brave: WebSearchProvider,
    private val deepSeek: WebSearchProvider,
) : WebSearchProvider {

    private suspend fun current(): WebSearchProvider = when (vendor()) {
        SearchVendor.Brave -> brave
        SearchVendor.DeepSeek -> deepSeek
    }

    override suspend fun isConfigured(): Boolean = current().isConfigured()

    override suspend fun search(query: String, count: Int, freshness: String): WebSearchResult =
        current().search(query, count, freshness)

    override suspend fun news(
        query: String,
        count: Int,
        freshness: String,
        country: String,
        searchLang: String,
    ): WebSearchResult = current().news(query, count, freshness, country, searchLang)
}
