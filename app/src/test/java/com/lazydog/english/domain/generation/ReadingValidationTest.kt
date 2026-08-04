package com.lazydog.english.domain.generation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingValidationTest {

    private val body =
        "Ming wanted to curb his phone use. Every evening the screen light lingered in his room. " +
            "He made a plan and sent the first draft to his friend. The plan sounded plausible, " +
            "so they tried it together for two weeks. In the end they both slept earlier and felt better. " +
            "It was a small change, but it worked well for them."

    private fun reading(
        title: String = "A Small Plan",
        readingBody: String = body,
        targets: List<ReadingTargetWord> = listOf(
            ReadingTargetWord("curb", "控制", "Ming wanted to curb his phone use.", "review"),
            ReadingTargetWord("plausible", "看似合理的", "The plan sounded plausible", "new"),
        ),
        grammar: List<ReadingTargetGrammar> = emptyList(),
        questions: List<ReadingQuestion> = listOf(
            ReadingQuestion("Ming 想控制什么？", listOf("手机使用", "饮食", "花钱"), 0, "第一句说了。"),
        ),
    ) = GeneratedReading(title, readingBody, "A2", targets, grammar, questions)

    private fun request(
        review: List<String> = listOf("curb", "linger"),
        maxNew: Int = 4,
    ) = ReadingGenerationRequest(
        learnerLevel = "A2-B1",
        topic = "科技",
        targetLength = 100,
        reviewVocabulary = review,
        knownVocabulary = emptyList(),
        reviewGrammar = emptyList(),
        maxNewWords = maxNew,
    )

    @Test
    fun `valid reading passes`() {
        val outcome = ReadingValidation.validate(reading(), request())
        assertNull(outcome.failure)
    }

    @Test
    fun `missing review word is rejected`() {
        val outcome = ReadingValidation.validate(reading(), request(review = listOf("curb", "negotiate")))
        assertNotNull(outcome.failure)
        assertTrue(outcome.failure!!.contains("negotiate"))
    }

    @Test
    fun `too many new words rejected`() {
        val outcome = ReadingValidation.validate(reading(), request(maxNew = 0))
        assertNotNull(outcome.failure)
    }

    @Test
    fun `target word absent from body rejected`() {
        val bad = reading(
            targets = listOf(ReadingTargetWord("negotiate", "协商", "", "review")),
        )
        assertNotNull(ReadingValidation.validate(bad, request()).failure)
    }

    @Test
    fun `grammar example must be substring of body`() {
        val bad = reading(
            grammar = listOf(ReadingTargetGrammar("过去式", "This sentence is not in the body.", "说明")),
        )
        assertNotNull(ReadingValidation.validate(bad, request()).failure)

        val good = reading(
            grammar = listOf(ReadingTargetGrammar("过去式", "He made a plan", "说明")),
        )
        assertNull(ReadingValidation.validate(good, request()).failure)
    }

    @Test
    fun `question integrity checks`() {
        val dupOptions = reading(
            questions = listOf(ReadingQuestion("题干", listOf("A", "A"), 0, "解析")),
        )
        assertNotNull(ReadingValidation.validate(dupOptions, request()).failure)

        val badIndex = reading(
            questions = listOf(ReadingQuestion("题干", listOf("A", "B"), 5, "解析")),
        )
        assertNotNull(ReadingValidation.validate(badIndex, request()).failure)

        val noQuestions = reading(questions = emptyList())
        assertNotNull(ReadingValidation.validate(noQuestions, request()).failure)
    }

    @Test
    fun `short body rejected`() {
        val outcome = ReadingValidation.validate(
            reading(readingBody = "Too short to count."),
            request(review = emptyList()),
        )
        assertNotNull(outcome.failure)
    }

    @Test
    fun `normalized substring tolerates whitespace differences`() {
        assertTrue(ReadingValidation.bodyContainsNormalized("He  made\na plan.", "He made a plan."))
        assertEquals(false, ReadingValidation.bodyContainsNormalized("Body text.", "Missing."))
    }
}
