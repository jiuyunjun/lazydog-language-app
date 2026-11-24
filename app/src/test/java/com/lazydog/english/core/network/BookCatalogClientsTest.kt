package com.lazydog.english.core.network

import com.lazydog.english.domain.books.CatalogSource
import com.lazydog.english.domain.books.TraditionalToSimplified
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BookCatalogClientsTest {

    @Test
    fun `gutendex results become entries with readable authors and epub urls`() {
        val body = """{"count":2,"next":null,"results":[
            {"id":29,"title":"The Open Boat","authors":[{"name":"Crane, Stephen"}],"copyright":false,
             "formats":{"application/epub+zip":"https://www.gutenberg.org/ebooks/29.epub3.images","text/plain":"x"},"download_count":1234},
            {"id":99,"title":"Still under copyright","authors":[],"copyright":true,"formats":{},"download_count":5}
        ]}"""
        val urls = mutableMapOf<String, String>()
        val entries = GutendexClient.parseSearch(body, urls)!!
        assertEquals(1, entries.size)
        assertEquals("Stephen Crane", entries.single().author)
        assertEquals(CatalogSource.Gutenberg, entries.single().source)
        assertEquals("https://www.gutenberg.org/ebooks/29.epub3.images", urls["29"])
        assertEquals("https://www.gutenberg.org/cache/epub/29/pg29.epub", GutendexClient.fallbackEpubUrl("29"))
        assertNull(GutendexClient.parseSearch("<html>down</html>"))
    }

    @Test
    fun `wikisource search keeps works not subpages`() {
        val body = """{"query":{"search":[
            {"title":"紅樓夢","snippet":"<span class=\"searchmatch\">紅樓夢</span>，清代小說","wordcount":126},
            {"title":"紅樓夢/第001回","snippet":"x"}
        ]}}"""
        val entries = WikisourceClient.parseSearch(body)!!
        assertEquals(listOf("紅樓夢"), entries.map { it.id })
        assertEquals("紅樓夢，清代小說", entries.single().summary)
    }

    @Test
    fun `wikisource subpage listing returns continuation`() {
        val body = """{"continue":{"apcontinue":"紅樓夢/第006回"},"query":{"allpages":[{"title":"紅樓夢/第001回"},{"title":"紅樓夢/第002回"}]}}"""
        val (pages, next) = WikisourceClient.parseAllPages(body)!!
        assertEquals(listOf("紅樓夢/第001回", "紅樓夢/第002回"), pages)
        assertEquals("紅樓夢/第006回", next)
    }

    @Test
    fun `wikisource chapter drops navigation footnotes and license`() {
        val html = """<div class="mw-parser-output"><style>.x{}</style>
            <p><a href="/wiki/x">回目录</a>　<a href="/wiki/y">下一回</a></p><hr />
            <div class="center"><b>第一回　甄士隱夢幻識通靈</b></div>
            <p>列位看官，你道此書從何而來？<sup id="cite_ref-1" class="reference"><a href="#c">[1]</a></sup></p>
            <p>原來女媧氏煉石補天之時。</p>
            <h2>註釋</h2>
            <ol class="references"><li>注釋一</li></ol>
            <div class="licenseContainer licenseBanner"><p>此作品屬於公有領域</p></div></div>"""
        val body = """{"parse":{"title":"紅樓夢/第001回","text":${kotlinx.serialization.json.JsonPrimitive(html)}}}"""
        val chapter = WikisourceClient.parseChapter("紅樓夢/第001回", body)!!
        assertEquals("第一回 甄士隱夢幻識通靈", chapter.title)
        assertEquals(listOf("列位看官，你道此書從何而來？", "原來女媧氏煉石補天之時。"), chapter.paragraphs)
    }

    @Test
    fun `ao3 blurbs become entries`() {
        val html = """<ol class="work index group">
            <li id="work_304382" class="work blurb group work-304382" role="article">
              <div class="header module"><h4 class="heading"><a href="/works/304382">The Internet &amp; Me</a> by
                <a rel="author" href="/users/a/pseuds/a">cyerus</a></h4></div>
              <blockquote class="userstuff summary"><p>John is real.</p><p>No one believes it.</p></blockquote>
              <dl class="stats"><dd class="language">English</dd><dd class="words">1,911</dd><dd class="chapters">1/1</dd></dl>
            </li>
            <li id="work_42" class="work blurb group work-42" role="article"><h4 class="heading">no link</h4></li>
            </ol>"""
        val entries = Ao3Client.parseSearch(html)
        assertEquals(1, entries.size)
        val entry = entries.single()
        assertEquals("The Internet & Me", entry.title)
        assertEquals("cyerus", entry.author)
        assertEquals("John is real. No one believes it.", entry.summary)
        assertEquals("1,911 词 · 1/1 章", entry.meta)
        assertEquals("https://archiveofourown.org/works/304382", entry.webUrl)
    }

    @Test
    fun `traditional characters convert and others stay`() {
        val t2s = TraditionalToSimplified.parse(sequenceOf("# header", "樓 楼", "夢 梦", "紅 红", "bad line here"))
        assertEquals("红楼梦 Chapter 1", t2s.convert("紅樓夢 Chapter 1"))
        assertTrue(TraditionalToSimplified(emptyMap()).convert("紅").isNotEmpty())
    }
}
