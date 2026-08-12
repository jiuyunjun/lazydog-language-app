package com.lazydog.english.domain.practice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GrammarErrorTagTest {

    @Test
    fun `认识的标签原样保留，大小写和连字符都能归一`() {
        assertEquals(GrammarErrorTag.Tense, GrammarErrorTag.normalize("tense"))
        assertEquals(GrammarErrorTag.NonFinite, GrammarErrorTag.normalize("Non-Finite"))
        assertEquals(GrammarErrorTag.WordOrder, GrammarErrorTag.normalize("word order"))
    }

    @Test
    fun `不认识的标签一律归到 other`() {
        assertEquals(GrammarErrorTag.Other, GrammarErrorTag.normalize("vibes"))
        assertEquals(GrammarErrorTag.Other, GrammarErrorTag.normalize(""))
    }

    @Test
    fun `每个标签都有中文名`() {
        GrammarErrorTag.all.forEach { tag ->
            assertTrue("$tag 缺中文名", GrammarErrorTag.labelZh(tag).isNotBlank())
        }
    }
}

class MistakeProfileTest {

    private val now = 1_700_000_000_000L
    private fun daysAgo(days: Int) = now - days * 24L * 60 * 60 * 1000

    private fun mistake(tag: String, pattern: String = "have/has + pp", at: Long = now) =
        DrillMistake(pattern, tag, at)

    @Test
    fun `错得多的排前面`() {
        val summaries = MistakeProfile.summarize(
            listOf(
                mistake(GrammarErrorTag.Tense),
                mistake(GrammarErrorTag.Agreement),
                mistake(GrammarErrorTag.Agreement),
                mistake(GrammarErrorTag.Agreement),
            ),
            nowMillis = now,
        )
        assertEquals(GrammarErrorTag.Agreement, summaries.first().errorTag)
        assertEquals(3, summaries.first().count)
    }

    @Test
    fun `窗口外的老错题不算`() {
        val summaries = MistakeProfile.summarize(
            listOf(
                mistake(GrammarErrorTag.Article, at = daysAgo(60)),
                mistake(GrammarErrorTag.Tense, at = daysAgo(2)),
            ),
            nowMillis = now,
        )
        assertEquals(1, summaries.size)
        assertEquals(GrammarErrorTag.Tense, summaries.first().errorTag)
    }

    @Test
    fun `次数相同时最近犯的排前面`() {
        val summaries = MistakeProfile.summarize(
            listOf(
                mistake(GrammarErrorTag.Article, at = daysAgo(10)),
                mistake(GrammarErrorTag.Plural, at = daysAgo(1)),
            ),
            nowMillis = now,
        )
        assertEquals(GrammarErrorTag.Plural, summaries.first().errorTag)
    }

    @Test
    fun `同一类里列出出错的语法点，去重且最近的在前`() {
        val summary = MistakeProfile.summarize(
            listOf(
                mistake(GrammarErrorTag.Tense, pattern = "be going to", at = daysAgo(5)),
                mistake(GrammarErrorTag.Tense, pattern = "have been + ing", at = daysAgo(1)),
                mistake(GrammarErrorTag.Tense, pattern = "have been + ing", at = daysAgo(2)),
            ),
            nowMillis = now,
        ).first()
        assertEquals(listOf("have been + ing", "be going to"), summary.patterns)
    }

    @Test
    fun `只取前几类`() {
        val many = GrammarErrorTag.all.map { mistake(it) }
        assertEquals(3, MistakeProfile.summarize(many, nowMillis = now, limit = 3).size)
    }

    @Test
    fun `没有错题时是空的`() {
        assertTrue(MistakeProfile.summarize(emptyList(), nowMillis = now).isEmpty())
        assertNull(MistakeProfile.summaryText(emptyList()))
    }

    @Test
    fun `摘要文本带次数`() {
        val text = MistakeProfile.summaryText(
            MistakeProfile.summarize(
                listOf(mistake(GrammarErrorTag.Agreement), mistake(GrammarErrorTag.Agreement)),
                nowMillis = now,
            ),
        )
        assertEquals("主谓一致 / 三单（错过 2 次）", text)
    }

    @Test
    fun `minCount 可以滤掉偶然错一次的`() {
        val summaries = MistakeProfile.summarize(
            listOf(mistake(GrammarErrorTag.Article), mistake(GrammarErrorTag.Tense), mistake(GrammarErrorTag.Tense)),
            nowMillis = now,
            minCount = 2,
        )
        assertEquals(listOf(GrammarErrorTag.Tense), summaries.map { it.errorTag })
    }
}
