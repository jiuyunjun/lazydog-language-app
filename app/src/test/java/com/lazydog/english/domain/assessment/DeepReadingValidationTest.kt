package com.lazydog.english.domain.assessment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DeepReadingValidationTest {

    private fun question(tag: String) =
        DeepReadingQuestion(tag, "prompt", listOf("a", "b", "c"), 0, "e")

    private fun passage(words: Int) = (1..words).joinToString(" ") { "word" }

    @Test
    fun `valid task with four distinct tags passes`() {
        val task = DeepReadingTask(
            passage(250),
            listOf(
                question(ReadingTag.MainIdea),
                question(ReadingTag.Detail),
                question(ReadingTag.Inference),
                question(ReadingTag.VocabReference),
            ),
        )
        assertNull(DeepReadingValidation.validate(task, "B1"))
    }

    @Test
    fun `duplicate tags are rejected`() {
        val task = DeepReadingTask(
            passage(250),
            listOf(
                question(ReadingTag.MainIdea),
                question(ReadingTag.MainIdea),
                question(ReadingTag.Inference),
                question(ReadingTag.VocabReference),
            ),
        )
        assertNotNull(DeepReadingValidation.validate(task, "B1"))
    }

    @Test
    fun `wrong question count is rejected`() {
        val task = DeepReadingTask(passage(250), listOf(question(ReadingTag.MainIdea)))
        assertNotNull(DeepReadingValidation.validate(task, "B1"))
    }

    @Test
    fun `passage length out of range for the level is rejected`() {
        val task = DeepReadingTask(
            passage(10), // 太短，不在 B1 的 160~350 范围内
            listOf(
                question(ReadingTag.MainIdea),
                question(ReadingTag.Detail),
                question(ReadingTag.Inference),
                question(ReadingTag.VocabReference),
            ),
        )
        assertNotNull(DeepReadingValidation.validate(task, "B1"))
    }

    @Test
    fun `scoring applies the 3-2-3-2 weights`() {
        val questions = listOf(
            question(ReadingTag.MainIdea),
            question(ReadingTag.Detail),
            question(ReadingTag.Inference),
            question(ReadingTag.VocabReference),
        )
        // 主旨对(3)、细节错(0)、推断对(3)、词义错(0) => 6/10 = 60%
        val answers = listOf(
            DeepReadingAnswer(questions[0], selected = 0),
            DeepReadingAnswer(questions[1], selected = 1),
            DeepReadingAnswer(questions[2], selected = 0),
            DeepReadingAnswer(questions[3], selected = 1),
        )
        val outcome = DeepReadingValidation.score(answers)
        assertEquals(60, outcome.pct)
        assertEquals(6, outcome.correctWeight)
    }
}
