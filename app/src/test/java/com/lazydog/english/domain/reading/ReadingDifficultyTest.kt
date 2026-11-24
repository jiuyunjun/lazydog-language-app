package com.lazydog.english.domain.reading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReadingDifficultyTest {

    private val ranks = mapOf(
        "the" to 1, "a" to 5, "man" to 100, "go" to 40, "to" to 3, "sea" to 900,
        "boat" to 1500, "shark" to 9000, "afloat" to 20000,
    )
    private val lemmas = mapOf("went" to "go", "sharks" to "shark")

    private fun estimate(text: String, known: Set<String> = emptySet()) = ReadingDifficulty.estimate(
        text = text,
        lemmaOf = { lemmas[it] },
        rankOf = { ranks[it] },
        knownTerms = known,
        comfortableRank = 2000,
    )

    private fun repeat(sentence: String, times: Int) = List(times) { sentence }.joinToString(" ")

    @Test
    fun `easy when every word is inside the comfort zone`() {
        val result = estimate(repeat("The man went to the sea.", 15))!!
        assertEquals(ReadingDifficulty.Level.Easy, result.level)
        assertEquals(0.0, result.unknownRatio, 1e-9)
    }

    @Test
    fun `hard when rare words are frequent and they are listed by count`() {
        val result = estimate(repeat("The sharks went to the boat afloat.", 12))!!
        assertEquals(ReadingDifficulty.Level.Hard, result.level)
        assertEquals(listOf("shark", "afloat"), result.sampleUnknown)
    }

    @Test
    fun `known vocabulary does not count as unknown`() {
        val result = estimate(repeat("The sharks went to the boat afloat.", 12), known = setOf("shark", "afloat"))!!
        assertEquals(ReadingDifficulty.Level.Easy, result.level)
    }

    @Test
    fun `proper nouns mid sentence are skipped but sentence starts are counted`() {
        assertEquals(listOf("the", "man", "met", "in", "the", "sea"), ReadingDifficulty.tokens("The man met Crane in the sea."))
        assertEquals(listOf("sharks", "came"), ReadingDifficulty.tokens("\"Sharks came.\""))
    }

    @Test
    fun `short texts are not estimated`() {
        assertNull(estimate("The man went to the sea."))
    }
}
