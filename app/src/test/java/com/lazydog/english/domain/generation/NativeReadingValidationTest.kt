package com.lazydog.english.domain.generation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 母语阅读的本地校验（`母语阅读DESIGN.md` §10、§42）。
 *
 * 这些用例守的是同一条线：**任何一处不对，都只丢掉那一处，不丢整篇**——
 * 因为纯中文永远是可读的兜底，而一篇报错的文章不是。
 */
class NativeReadingValidationTest {

    private val paragraphs = listOf(
        CanonicalParagraph("p1", "日本便利店最厉害的地方，也许不是什么都有，而是它们很少让你感觉某样东西真的缺了。"),
        CanonicalParagraph("p2", "真正有效的系统通常并不追求完美预测，而是给错误留下空间，让每次小偏差都能很快被纠正过来。"),
        CanonicalParagraph("p3", "这也是很多大型软件系统的设计逻辑：可靠性并不来自永远不出错，而来自出错之后还能回得来。"),
    )

    private fun request(
        amount: EnglishAmount = EnglishAmount.Balanced,
        allowGrammar: Boolean = true,
    ) = NativeSpanPlanRequest(
        learnerLevel = "B1",
        paragraphs = paragraphs,
        englishAmount = amount,
        newWordAmount = NewWordAmount.Normal,
        allowGrammar = allowGrammar,
    )

    private fun span(
        id: String,
        paragraphId: String,
        sourceZh: String,
        renderedEn: String,
        mastery: String = MasteryClass.Review,
        kind: String = SpanKind.Phrase,
    ) = LearningSpan(
        id = id,
        paragraphId = paragraphId,
        sourceZh = sourceZh,
        renderedEn = renderedEn,
        kind = kind,
        masteryClass = mastery,
        meaningZh = "意思",
    )

    @Test
    fun `定不到位的片段被丢掉，其余照常保留`() {
        val outcome = NativeReadingValidation.validatePlan(
            paragraphs = paragraphs,
            spans = listOf(
                span("s1", "p1", "什么都有", "have everything"),
                span("s2", "p2", "完美预测", "perfect prediction"),
                span("s3", "p3", "可靠性", "reliability"),
                // 原文里根本没有这句话：模型顺手改写了中文，这条留着就会变成点不开的记录。
                span("s4", "p2", "库存周转率", "inventory turnover"),
            ),
            request = request(),
        )
        assertNull(outcome.failure)
        assertEquals(listOf("s1", "s2", "s3"), outcome.spans.map { it.id })
        assertTrue(outcome.warnings.any { it.contains("inventory turnover") })
    }

    @Test
    fun `重叠的片段后来的让位`() {
        val outcome = NativeReadingValidation.validatePlan(
            paragraphs = paragraphs,
            spans = listOf(
                span("s1", "p2", "给错误留下空间", "leave room for error"),
                span("s2", "p2", "错误", "error"),
                span("s3", "p1", "什么都有", "have everything"),
                span("s4", "p3", "可靠性", "reliability"),
            ),
            request = request(),
        )
        assertNull(outcome.failure)
        assertTrue(outcome.spans.none { it.id == "s2" })
    }

    @Test
    fun `语法片段最多一个，超出的退回中文`() {
        val outcome = NativeReadingValidation.validatePlan(
            paragraphs = paragraphs,
            spans = listOf(
                span("s1", "p2", "给错误留下空间", "leave room for error", kind = SpanKind.Grammar),
                span("s2", "p3", "出错之后还能回得来", "as long as it can recover", kind = SpanKind.Grammar),
                span("s3", "p1", "什么都有", "have everything"),
                span("s4", "p3", "可靠性", "reliability"),
            ),
            request = request(),
        )
        assertNull(outcome.failure)
        assertEquals(1, outcome.spans.count { it.isGrammar })
    }

    @Test
    fun `两个新表达挨在一起时后一个退回中文`() {
        val outcome = NativeReadingValidation.validatePlan(
            paragraphs = paragraphs,
            spans = listOf(
                span("s1", "p2", "完美预测", "perfect prediction", mastery = MasteryClass.Target),
                // 紧挨着上一个，中间只隔了「，而是」
                span("s2", "p2", "给错误留下空间", "leave room for error", mastery = MasteryClass.Target),
                span("s3", "p1", "什么都有", "have everything"),
                span("s4", "p3", "可靠性", "reliability"),
            ),
            request = request(),
        )
        assertNull(outcome.failure)
        assertTrue(outcome.spans.none { it.id == "s2" })
    }

    @Test
    fun `可用片段太少时整篇不通过`() {
        val outcome = NativeReadingValidation.validatePlan(
            paragraphs = paragraphs,
            spans = listOf(span("s1", "p1", "什么都有", "have everything")),
            request = request(),
        )
        assertNotNull(outcome.failure)
    }

    @Test
    fun `渲染把段落切成中文段和可点英语段`() {
        val spans = listOf(span("s1", "p2", "完美预测", "perfect prediction"))
        val rendered = NativeReadingValidation.render(paragraphs, spans)
        val p2 = rendered.first { it.id == "p2" }
        assertEquals(3, p2.segments.size)
        assertTrue(p2.segments[0] is ReadingSegment.Zh)
        assertEquals("perfect prediction", (p2.segments[1] as ReadingSegment.Learning).text)
        assertEquals("s1", (p2.segments[1] as ReadingSegment.Learning).spanId)
        // 没有 span 的段落原样一段中文，不被切碎。
        assertEquals(1, rendered.first { it.id == "p1" }.segments.size)
    }

    @Test
    fun `中文母版太短或段落太少不通过`() {
        val outcome = NativeReadingValidation.validateCanonical(
            NativeCanonicalArticle(
                title = "便利店",
                teaser = "",
                category = "",
                readerPayoff = "",
                paragraphs = paragraphs.take(2),
                comprehension = null,
            ),
        )
        assertNotNull(outcome.failure)
    }

    @Test
    fun `标题里出现英文单词只是警告，不拦下整篇`() {
        val outcome = NativeReadingValidation.validateCanonical(
            NativeCanonicalArticle(
                title = "便利店的 inventory 是怎么算的",
                teaser = "t",
                category = "c",
                readerPayoff = "p",
                paragraphs = paragraphs + CanonicalParagraph(
                    "p4",
                    "门店会根据时间、销量和配送频率不断做小幅修正，一天补几次货，" +
                        "让每一次判断失误都只影响很短的一段时间，而不是整整一天的货架。" +
                        "这套做法的代价是配送成本更高，但换来的是货架上几乎不会长时间空着，" +
                        "顾客也就很少有机会发现系统其实一直在出错。",
                ),
                comprehension = null,
            ),
        )
        assertNull(outcome.failure)
        assertTrue(outcome.warnings.any { it.contains("标题") })
    }

    @Test
    fun `选项重复的理解题被判为不可用`() {
        val bad = NativeComprehensionQuestion(
            promptZh = "为什么？",
            options = listOf("因为快", "因为快"),
            answerIndex = 0,
            explanationZh = "",
        )
        assertNull(NativeReadingValidation.usableQuestion(bad))
        val good = bad.copy(options = listOf("因为快", "因为准"))
        assertNotNull(NativeReadingValidation.usableQuestion(good))
    }

    @Test
    fun `回忆优先给新表达`() {
        val spans = listOf(
            span("s1", "p1", "什么都有", "have everything", mastery = MasteryClass.Mastered),
            span("s2", "p2", "完美预测", "perfect prediction", mastery = MasteryClass.Review),
            span("s3", "p3", "可靠性", "reliability", mastery = MasteryClass.Target),
        )
        val recall = NativeReadingValidation.recallCandidates(spans, limit = 2)
        assertEquals(listOf("s3", "s2"), recall.map { it.id })
    }
}
