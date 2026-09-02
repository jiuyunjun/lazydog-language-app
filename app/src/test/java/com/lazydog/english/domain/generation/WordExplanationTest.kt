package com.lazydog.english.domain.generation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WordExplanationTest {

    @Test
    fun `the headword is the lemma, and the tapped form is kept`() {
        val went = explanation(term = "went", lemma = "go")
        assertEquals("go", went.headword)
        assertTrue(went.inflected)
    }

    @Test
    fun `a word already in dictionary form is not treated as inflected`() {
        assertFalse(explanation(term = "go", lemma = "go").inflected)
        // 大小写不算变形：句首的 Go 和词条 go 是同一个词。
        assertFalse(explanation(term = "Go", lemma = "go").inflected)
    }

    @Test
    fun `no lemma from the model falls back to the tapped form`() {
        // 判不出来就按原词走，绝不本地猜——saw / left / found 离开句子根本判不了。
        val unknown = explanation(term = "Cheugy", lemma = "")
        assertEquals("Cheugy", unknown.headword)
        assertFalse(unknown.inflected)
    }

    private fun explanation(term: String, lemma: String) = WordExplanation(
        term = term,
        lemma = lemma,
        ipa = "",
        meaningZh = "去",
        usageNoteZh = "",
    )
}
