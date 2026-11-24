package com.lazydog.english.core.network

import com.lazydog.english.domain.reading.VoaChannel
import com.lazydog.english.domain.reading.WebReadingSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebReadingSourcesTest {

    private val voaFeed = """<?xml version="1.0" encoding="UTF-8"?><rss version="2.0"><channel>
        <title>American Stories - Voice of America</title>
        <item>
            <title>&apos;The Open Boat&apos; by Stephen Crane, Part Two</title>
            <description>We continue the story of “The Open Boat” by Stephen Crane.</description>
            <link>https://learningenglish.voanews.com/a/open-boat/7504521.html</link>
            <pubDate>Fri, 14 Mar 2025 22:00:46 +0000</pubDate>
        </item>
        <item><title></title><link>https://x/a.html</link></item>
        <item><title>Duplicate</title><link>https://learningenglish.voanews.com/a/open-boat/7504521.html</link></item>
        </channel></rss>"""

    @Test
    fun `voa feed keeps titled items once`() {
        val cards = VoaLearningEnglishClient.parseFeed(voaFeed, VoaChannel.AmericanStories)!!
        assertEquals(1, cards.size)
        val card = cards.single()
        assertEquals("'The Open Boat' by Stephen Crane, Part Two", card.title)
        assertEquals(WebReadingSource.Voa, card.source)
        assertEquals(VoaChannel.AmericanStories.labelZh, card.channel)
        assertEquals(1741989646000L, card.publishedAt)
    }

    @Test
    fun `voa feed that is not xml is rejected`() {
        assertNull(VoaLearningEnglishClient.parseFeed("<html>blocked</html", VoaChannel.AsItIs))
    }

    @Test
    fun `voa body stops before the glossary and drops player text`() {
        val html = """
            <div class="media"><p>&#x27;The Open Boat&#x27; by Stephen Crane</p></div>
            <div class="wsw">
              <p>No media source currently available</p>
              <p>We continue the story of “The Open Boat”.</p>
              <p>Three men got into the boat&nbsp;with him.<br/>They rowed.</p>
              <p>______________________________________________</p>
              <h2>Words in This Story</h2>
              <p><b>afloat</b> - adj. floating on water</p>
            </div>
            <div id="comments"><p>See comments</p></div>
        """.trimIndent()
        assertEquals(
            listOf("We continue the story of “The Open Boat”.", "Three men got into the boat with him. They rowed."),
            VoaLearningEnglishClient.extractBody(html),
        )
        assertTrue(VoaLearningEnglishClient.extractBody("<p>no body container</p>").isEmpty())
    }

    @Test
    fun `wiki list skips disambiguation lists and stubs`() {
        val body = """{"query":{"pages":[
            {"title":"Volcano","index":1,"extract":"A volcano is a mountain that has lava coming out from a magma chamber under the ground."},
            {"title":"List of extinct volcanoes","index":2,"extract":"An extinct volcano is a volcano which scientists consider unlikely to erupt again."},
            {"title":"Mercury","index":3,"extract":"Mercury may refer to: a planet, a chemical element, a Roman god, and other things too."},
            {"title":"Tiny","index":0,"extract":"Too short."}
        ]}}"""
        val cards = SimpleWikipediaClient.parseList(body)!!
        assertEquals(listOf("Volcano"), cards.map { it.title })
        assertEquals("https://simple.wikipedia.org/wiki/Volcano", cards.single().link)
    }

    @Test
    fun `wiki article drops headings and cuts tail sections`() {
        val body = """{"query":{"pages":[{"title":"Volcano","extract":"A volcano is a mountain.\nMost volcanoes have a crater.\n\n\n== Types ==\nShield volcanoes are wide.\n\n== Related pages ==\nList of volcanoes\n\n== References ==\n"}]}}"""
        assertEquals(
            listOf("A volcano is a mountain.", "Most volcanoes have a crater.", "Shield volcanoes are wide."),
            SimpleWikipediaClient.parseArticle(body),
        )
        assertNull(SimpleWikipediaClient.parseArticle("""{"query":{"pages":[{"title":"X","missing":true}]}}"""))
    }
}
