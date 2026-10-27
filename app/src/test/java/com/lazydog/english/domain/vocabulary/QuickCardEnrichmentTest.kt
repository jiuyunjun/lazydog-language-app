package com.lazydog.english.domain.vocabulary

import com.lazydog.english.domain.generation.Collocation
import com.lazydog.english.domain.generation.GeneratedWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickCardEnrichmentTest {
    private val quick = QuickCard(
        term = "run",
        pos = "VERB",
        meaningZh = "经营",
        ipa = "",
        exampleEn = "She runs a small bakery.",
        exampleZh = "",
        memoryHintZh = "跑起来的生意",
        forms = listOf("ran"),
        collocations = emptyList(),
    )

    private val full = GeneratedWord(
        term = "run",
        ipa = "/rʌn/",
        meaningZh = "经营；管理",
        exampleEn = "He runs the family hotel.",
        exampleZh = "他经营家里的旅馆。",
        pos = "VERB",
        forms = listOf("ran", "run", "Ran"),
        collocations = listOf(Collocation("run a business", "经营生意")),
        memoryHintZh = "模型给的提示",
        chunks = listOf("r", "un"),
        trickyPart = "u",
        misspellings = listOf("runn", "run"),
    )

    private fun merged(q: QuickCard = quick, f: GeneratedWord = full) =
        (QuickCardEnrichment.merge(q, f) as EnrichmentResult.Merged).word

    @Test
    fun `fills what the quick card lacks and keeps what the user saw`() {
        val word = merged()

        assertEquals("/rʌn/", word.ipa)
        // 原句是用户真正读到的语境，留着；模型那句不替换它。
        assertEquals("She runs a small bakery.", word.exampleEn)
        assertEquals("", word.exampleZh)
        assertEquals("跑起来的生意", word.memoryHintZh)
        assertEquals(listOf(Collocation("run a business", "经营生意")), word.collocations)
        assertEquals(listOf("r", "un"), word.chunks)
        assertEquals("u", word.trickyPart)
    }

    @Test
    fun `forms and misspellings are deduplicated and never the word itself`() {
        val word = merged()

        assertEquals(listOf("ran"), word.forms)
        assertEquals(listOf("runn"), word.misspellings)
    }

    @Test
    fun `without an original sentence the full card example is used`() {
        val word = merged(q = quick.copy(exampleEn = "", memoryHintZh = ""))

        assertEquals("He runs the family hotel.", word.exampleEn)
        assertEquals("他经营家里的旅馆。", word.exampleZh)
        assertEquals("模型给的提示", word.memoryHintZh)
    }

    @Test
    fun `spelling facts that do not fit the word are dropped`() {
        val word = merged(f = full.copy(chunks = listOf("ru", "nn"), trickyPart = "xyz"))

        assertTrue(word.chunks.isEmpty())
        assertEquals("", word.trickyPart)
    }

    @Test
    fun `a different word or part of speech rejects the whole card`() {
        assertTrue(QuickCardEnrichment.merge(quick, full.copy(term = "running")) is EnrichmentResult.Rejected)
        assertTrue(QuickCardEnrichment.merge(quick, full.copy(pos = "NOUN")) is EnrichmentResult.Rejected)
    }

    @Test
    fun `an invalid full card is rejected`() {
        assertTrue(QuickCardEnrichment.merge(quick, full.copy(exampleZh = "")) is EnrichmentResult.Rejected)
    }

    @Test
    fun `unknown quick part of speech accepts the model's`() {
        assertTrue(QuickCardEnrichment.merge(quick.copy(pos = ""), full) is EnrichmentResult.Merged)
    }
}
