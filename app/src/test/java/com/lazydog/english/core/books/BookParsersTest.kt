package com.lazydog.english.core.books

import com.lazydog.english.domain.books.BookLanguage
import com.lazydog.english.domain.books.TextBookSplitter
import java.io.File
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BookParsersTest {

    @Test
    fun `chinese and english headings are recognised but long sentences are not`() {
        assertTrue(TextBookSplitter.isHeading("第一章 初入江湖"))
        assertTrue(TextBookSplitter.isHeading("第12回"))
        assertTrue(TextBookSplitter.isHeading("　楔子"))
        assertTrue(TextBookSplitter.isHeading("CHAPTER IV"))
        assertTrue(TextBookSplitter.isHeading("Chapter One: The Boat"))
        assertFalse(TextBookSplitter.isHeading("他翻到第一章，发现那一页早就被人撕掉了，只剩下一行字还看得清楚。"))
        assertFalse(TextBookSplitter.isHeading("Chapters are hard"))
    }

    @Test
    fun `txt splits on headings and keeps a preface`() {
        val text = "某某著\r\n\r\n第一章 开始\r\n　　他来了。\r\n他又走了。\n\n第二章 结束\n　　完。"
        val chapters = TextBookSplitter.split(text)
        assertEquals(listOf("开篇", "第一章 开始", "第二章 结束"), chapters.map { it.title })
        assertEquals(listOf("他来了。", "他又走了。"), chapters[1].paragraphs)
    }

    @Test
    fun `txt without headings is split by size`() {
        val paragraphs = List(30) { "x".repeat(400) }
        val chapters = TextBookSplitter.split(paragraphs.joinToString("\n"))
        assertTrue(chapters.size >= 3)
        assertTrue(chapters.all { it.charCount <= TextBookSplitter.FALLBACK_CHARS })
        assertEquals("第 1 部分", chapters.first().title)
    }

    @Test
    fun `txt encoding falls back to gb18030`() {
        val text = "第一章 开始\n他来了。"
        assertEquals(text, TextBookParser.decode(text.toByteArray(Charset.forName("GBK"))))
        assertEquals(text, TextBookParser.decode(text.toByteArray(Charsets.UTF_8)))
        assertEquals(text, TextBookParser.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text.toByteArray()))
    }

    @Test
    fun `language is detected from text`() {
        assertEquals(BookLanguage.Chinese, BookLanguage.detect("他来了，又走了。Hi"))
        assertEquals(BookLanguage.English, BookLanguage.detect("He came and went. 嗯"))
    }

    private fun epub(entries: Map<String, String>): File {
        val file = File.createTempFile("book", ".epub")
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return file
    }

    private val longText = "Four men sat in a small boat and rowed toward the shore. ".repeat(6)

    @Test
    fun `epub follows spine order with toc titles and skips the cover page`() {
        val file = epub(
            mapOf(
                "META-INF/container.xml" to """<?xml version="1.0"?><container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
                "OEBPS/content.opf" to """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <metadata><dc:title>The Open Boat</dc:title><dc:creator>Stephen Crane</dc:creator><dc:language>en</dc:language></metadata>
                    <manifest>
                      <item id="cover" href="text/cover.xhtml" media-type="application/xhtml+xml"/>
                      <item id="c1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
                      <item id="c2" href="text/ch2.xhtml" media-type="application/xhtml+xml"/>
                      <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                    </manifest>
                    <spine><itemref idref="cover"/><itemref idref="c2"/><itemref idref="c1"/></spine></package>""",
                "OEBPS/nav.xhtml" to """<html><body><nav><ol><li><a href="text/ch1.xhtml">I. The Sea</a></li><li><a href="text/ch2.xhtml#top">II. The Shore</a></li></ol></nav></body></html>""",
                "OEBPS/text/cover.xhtml" to """<html><body><p>Cover</p></body></html>""",
                "OEBPS/text/ch1.xhtml" to """<html><head><title>x</title></head><body><h1>Ignored</h1><p>$longText</p><p>They rowed&nbsp;on.</p></body></html>""",
                "OEBPS/text/ch2.xhtml" to """<html><body><h2>Shore heading</h2><p>$longText</p></body></html>""",
            ),
        )
        val book = EpubParser.parse(file)!!
        assertEquals("The Open Boat", book.title)
        assertEquals("Stephen Crane", book.author)
        assertEquals(BookLanguage.English, book.language)
        assertEquals(listOf("II. The Shore", "I. The Sea"), book.chapters.map { it.title })
        assertEquals("They rowed on.", book.chapters[1].paragraphs.last())
        file.delete()
    }

    @Test
    fun `broken epub returns null`() {
        val file = epub(mapOf("mimetype" to "application/epub+zip"))
        assertNull(EpubParser.parse(file))
        file.delete()
    }

    @Test
    fun `relative paths are resolved`() {
        assertEquals("OEBPS/images/c.jpg", EpubParser.resolve("OEBPS/text/", "../images/c.jpg"))
        assertEquals("a b.xhtml", EpubParser.resolve("", "a%20b.xhtml"))
    }
}
