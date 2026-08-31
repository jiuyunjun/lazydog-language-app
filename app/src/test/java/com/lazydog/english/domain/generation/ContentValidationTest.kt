package com.lazydog.english.domain.generation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentValidationTest {

    private fun word(
        term: String = "curb",
        meaning: String = "控制",
        example: String = "The city tried to curb traffic.",
        exampleZh: String = "市政府想控制车流。",
        pos: String = "v.",
        collocations: List<String> = listOf("curb traffic"),
        memoryHint: String = "curb 是路缘石，把车流「圈」在路里，引申成控制、抑制。",
    ) = GeneratedWord(term, "/kɜːb/", meaning, example, exampleZh, pos, collocations, memoryHint)

    @Test
    fun `valid word passes`() {
        val result = ContentValidation.validateNewWords(listOf(word()), maxCount = 5, knownTerms = emptySet())
        assertEquals(1, result.valid.size)
        assertTrue(result.droppedNotes.isEmpty())
    }

    @Test
    fun `drops word missing from example`() {
        val result = ContentValidation.validateNewWords(
            listOf(word(example = "A totally unrelated sentence.")),
            maxCount = 5,
            knownTerms = emptySet(),
        )
        assertTrue(result.valid.isEmpty())
        assertEquals(1, result.droppedNotes.size)
    }

    @Test
    fun `drops known and duplicate terms`() {
        val result = ContentValidation.validateNewWords(
            listOf(word(), word(), word(term = "linger", example = "The smell lingered.")),
            maxCount = 5,
            knownTerms = setOf("Linger"),
        )
        assertEquals(listOf("curb"), result.valid.map { it.term })
        assertEquals(2, result.droppedNotes.size)
    }

    @Test
    fun `accepts inflected forms in example`() {
        assertTrue(ContentValidation.exampleContainsTerm("She curbs her anger.", "curb"))
        assertTrue(ContentValidation.exampleContainsTerm("The smell lingered here.", "linger"))
        assertTrue(ContentValidation.exampleContainsTerm("He is taking notes.", "take"))
        assertTrue(ContentValidation.exampleContainsTerm("Two babies were crying.", "baby"))
        assertFalse(ContentValidation.exampleContainsTerm("Nothing here.", "curb"))
    }

    @Test
    fun `respects max count`() {
        val many = listOf(
            word(),
            word(term = "linger", example = "The smell lingered."),
            word(term = "draft", example = "Send me the draft."),
        )
        val result = ContentValidation.validateNewWords(many, maxCount = 2, knownTerms = emptySet())
        assertEquals(2, result.valid.size)
    }

    @Test
    fun `rejects malformed terms and long fields`() {
        val bad = listOf(
            word(term = "curb123"),
            word(term = ""),
            word(meaning = "长".repeat(121)),
        )
        val result = ContentValidation.validateNewWords(bad, maxCount = 5, knownTerms = emptySet())
        assertTrue(result.valid.isEmpty())
        assertEquals(3, result.droppedNotes.size)
    }

    @Test
    fun `drops words missing part of speech or collocations`() {
        val result = ContentValidation.validateNewWords(
            listOf(word(pos = ""), word(collocations = emptyList()), word(collocations = List(3) { "x $it" })),
            maxCount = 5,
            knownTerms = emptySet(),
        )
        assertTrue(result.valid.isEmpty())
        assertEquals(3, result.droppedNotes.size)
    }

    @Test
    fun `drops words missing memory hint`() {
        val result = ContentValidation.validateNewWords(
            listOf(word(memoryHint = ""), word(term = "linger", example = "The smell lingered.", memoryHint = "记".repeat(161))),
            maxCount = 5,
            knownTerms = emptySet(),
        )
        assertTrue(result.valid.isEmpty())
        assertEquals(2, result.droppedNotes.size)
        assertTrue(result.droppedNotes.all { it.contains("记忆方法") })
    }

    @Test
    fun `grammar lesson validation catches gaps`() {
        val lesson = GeneratedGrammarLesson(
            patternEn = "have/has been + verb-ing",
            labelZh = "现在完成进行时",
            summaryZh = "表示过去开始并持续至今的动作",
            explanationZh = "一直在做的事。",
            goodExampleEn = "I have been waiting.",
            goodExampleZh = "我一直在等。",
            badExampleEn = "",
            badExampleNoteZh = "",
            tipZh = "",
        )
        assertEquals(null, ContentValidation.validateGrammarLesson(lesson, emptySet()))
        assertTrue(
            ContentValidation.validateGrammarLesson(lesson, setOf("have/has been + verb-ing")) != null,
        )
        assertTrue(
            ContentValidation.validateGrammarLesson(lesson.copy(goodExampleEn = ""), emptySet()) != null,
        )
        assertTrue(
            ContentValidation.validateGrammarLesson(lesson.copy(patternEn = "have been doing 表示持续"), emptySet()) != null,
        )
    }
}
