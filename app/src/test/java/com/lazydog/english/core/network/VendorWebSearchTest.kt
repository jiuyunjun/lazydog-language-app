package com.lazydog.english.core.network

import com.lazydog.english.core.config.SearchVendor
import com.lazydog.english.domain.generation.WebSearchHit
import com.lazydog.english.domain.generation.WebSearchProvider
import com.lazydog.english.domain.generation.WebSearchResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VendorWebSearchTest {

    private class Fake(val name: String, val configured: Boolean) : WebSearchProvider {
        override suspend fun isConfigured() = configured
        override suspend fun search(query: String, count: Int, freshness: String) =
            WebSearchResult(listOf(WebSearchHit(title = name, summary = "", url = "")))
        override suspend fun news(query: String, count: Int, freshness: String, country: String, searchLang: String) =
            search(query, count, freshness)
    }

    /** 每次调用现取设置：切完下一次检索就换家，不用重建对象。 */
    @Test
    fun `按当前设置转给对应的服务商`() = runBlocking {
        var vendor = SearchVendor.Brave
        val search = VendorWebSearch(
            vendor = { vendor },
            brave = Fake("brave", configured = true),
            deepSeek = Fake("deepseek", configured = false),
        )
        assertEquals("brave", search.search("q").hits.single().title)
        assertTrue(search.isConfigured())

        vendor = SearchVendor.DeepSeek
        assertEquals("deepseek", search.news("q").hits.single().title)
        assertFalse(search.isConfigured())
    }
}
