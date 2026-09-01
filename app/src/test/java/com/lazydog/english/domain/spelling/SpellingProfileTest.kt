package com.lazydog.english.domain.spelling

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpellingProfileTest {

    @Test
    fun `no records means an empty profile, not a zeroed one`() {
        val profile = SpellingProfiles.build(progress = emptyList(), attempts = emptyList())
        assertTrue(profile.isEmpty)
        assertTrue(profile.masteryVector.isEmpty())
        assertNull(SpellingProfiles.trainingAdvice(profile))
    }

    @Test
    fun `error rates are shares of failures, not of all attempts`() {
        val attempts = List(10) { correctAttempt() } +
            List(3) { wrongAttempt(setOf(SpellingErrorType.Doubling)) } +
            wrongAttempt(setOf(SpellingErrorType.Omission))
        val profile = SpellingProfiles.build(progress = emptyList(), attempts = attempts)

        assertEquals(14, profile.attemptCount)
        assertEquals(4, profile.wrongCount)
        assertEquals(0.75, profile.errorRates[SpellingErrorType.Doubling]!!, 0.0001)
        assertEquals(0.25, profile.errorRates[SpellingErrorType.Omission]!!, 0.0001)
    }

    @Test
    fun `types that never came up are left out entirely`() {
        val profile = SpellingProfiles.build(
            progress = emptyList(),
            attempts = listOf(wrongAttempt(setOf(SpellingErrorType.Transposition))),
        )
        assertTrue(SpellingErrorType.Phonetic !in profile.errorRates)
    }

    @Test
    fun `advice waits until there are enough failures to point at`() {
        val few = SpellingProfiles.build(
            progress = emptyList(),
            attempts = List(2) { wrongAttempt(setOf(SpellingErrorType.Doubling)) },
        )
        assertNull(SpellingProfiles.trainingAdvice(few))

        val enough = SpellingProfiles.build(
            progress = emptyList(),
            attempts = List(SpellingProfiles.MIN_WRONG_FOR_ADVICE) { wrongAttempt(setOf(SpellingErrorType.Doubling)) },
        )
        assertTrue(SpellingProfiles.trainingAdvice(enough)!!.contains("双写错误"))
    }

    @Test
    fun `average free recall time ignores hinted and non-recall answers`() {
        val profile = SpellingProfiles.build(
            progress = emptyList(),
            attempts = listOf(
                correctAttempt(responseTimeMillis = 4_000),
                correctAttempt(responseTimeMillis = 6_000),
                // 用了提示的不算——那不是"自己想出来要多久"。
                correctAttempt(responseTimeMillis = 60_000, hintLevel = 3),
                // 选择题也不算，点四个选项之一和默写不是一件事。
                correctAttempt(responseTimeMillis = 60_000, type = SpellingQuestionType.Recognition),
            ),
        )
        assertEquals(5_000L, profile.avgFreeRecallMillis)
    }

    @Test
    fun `the mastery vector averages every word, including the ones at zero`() {
        val profile = SpellingProfiles.build(
            progress = listOf(
                SpellingProgress(recognitionScore = 1.0),
                SpellingProgress(recognitionScore = 0.0),
            ),
            attempts = listOf(correctAttempt()),
        )
        assertEquals(0.5, profile.masteryVector[SpellingDimension.Recognition]!!, 0.0001)
    }

    private fun correctAttempt(
        responseTimeMillis: Long = 3_000,
        hintLevel: Int = 0,
        type: SpellingQuestionType = SpellingQuestionType.FreeRecall,
    ) = SpellingAttemptSummary(
        correct = true,
        questionType = type,
        errorTypes = emptySet(),
        responseTimeMillis = responseTimeMillis,
        hintLevel = hintLevel,
    )

    private fun wrongAttempt(errorTypes: Set<SpellingErrorType>) = SpellingAttemptSummary(
        correct = false,
        questionType = SpellingQuestionType.FreeRecall,
        errorTypes = errorTypes,
        responseTimeMillis = 5_000,
        hintLevel = 0,
    )
}
