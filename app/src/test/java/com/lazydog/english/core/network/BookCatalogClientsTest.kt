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
    fun `wikisource header container is dropped and gives the chapter title`() {
        val html = """<div class="mw-parser-output"><div id="headerContainer"><table><tbody><tr>
            <td style="width:25%;"> </td>
            <td style="width:50%;"><b>西遊記</b><br />第一回<br />靈根育孕源流出 心性修持大道生<br /><span>作者：</span><a>吳承恩</a></td>
            <td style="width:25%;"><a>第二回<br />悟徹菩提真妙理</a></td><td>→</td></tr></tbody></table>
            <table><tr><td><ul><li>姊妹计划: 数据项</li></ul></td></tr></table></div>
            <p>詩曰：</p><p>混沌未分天地亂。</p>
            <table class="ws-noexport noprint ws-footer"><tbody><tr><td><a href="#top">↑返回頂部</a></td>
            <td><a>第二回﹕悟徹菩提真妙理<br />斷魔歸本合元神</a></td></tr></tbody></table></div>"""
        val body = """{"parse":{"title":"西遊記/第001回","text":${kotlinx.serialization.json.JsonPrimitive(html)}}}"""
        val chapter = WikisourceClient.parseChapter("西遊記/第001回", body)!!
        assertEquals("第一回 靈根育孕源流出 心性修持大道生", chapter.title)
        assertEquals(listOf("詩曰：", "混沌未分天地亂。"), chapter.paragraphs)
    }

    @Test
    fun `ao3 blurbs become entries`() {
        val html = """<ol class="work index group">
            <li id="work_304382" class="work blurb group work-304382" role="article">
              <div class="header module"><h4 class="heading"><a href="/works/304382">The Internet &amp; Me</a> by
                <a rel="author" href="/users/a/pseuds/a">cyerus</a></h4>
                <ul class="required-tags"><li><a><span class="rating-general-audience rating" title="General Audiences"><span class="text">General Audiences</span></span></a></li></ul></div>
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
        assertEquals("全年龄 · 1,911 词 · 1/1 章", entry.meta)
        assertEquals("https://archiveofourown.org/works/304382?view_adult=true", entry.webUrl)
    }

    @Test
    fun `ao3 epub link comes from the official download links`() {
        val html = """<li class="download"><ul class="expandable secondary">
            <li><a href="/downloads/304382/The_Internet_Is_Not_Just.azw3?updated_at=1778035595">AZW3</a></li>
            <li><a href="https://archiveofourown.org/downloads/304382/The_Internet_Is_Not_Just.html?updated_at=1778035595">HTML</a></li>
            </ul></li><a href="/downloads/999/Other.epub?updated_at=1">x</a>"""
        assertEquals(
            "/downloads/304382/The_Internet_Is_Not_Just.epub?updated_at=1778035595",
            Ao3Client.epubPathFrom(html, "304382"),
        )
        assertNull(Ao3Client.epubPathFrom("<p>Adult content warning</p>", "304382"))
    }

    @Test
    fun `traditional characters convert and others stay`() {
        val t2s = TraditionalToSimplified.parse(sequenceOf("# header", "樓 楼", "夢 梦", "紅 红", "bad line here"))
        assertEquals("红楼梦 Chapter 1", t2s.convert("紅樓夢 Chapter 1"))
        assertTrue(TraditionalToSimplified(emptyMap()).convert("紅").isNotEmpty())
    }
}
