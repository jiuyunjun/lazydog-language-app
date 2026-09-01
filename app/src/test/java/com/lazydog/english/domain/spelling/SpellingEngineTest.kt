package com.lazydog.english.domain.spelling

import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpellingEngineTest {
    @Test
    fun `recognition needs two successes before partial recall`() {
        val first = evaluate(SpellingProgress(stage = SpellingStage.Recognition), SpellingQuestionType.Recognition)
        assertEquals(SpellingStage.Recognition, first.nextProgress.stage)

        val second = evaluate(first.nextProgress, SpellingQuestionType.Recognition, day = 1)
        assertEquals(SpellingStage.PartialRecall, second.nextProgress.stage)
    }

    @Test
    fun `free recall only becomes retained across dates and a seven day interval`() {
        var progress = SpellingProgress(stage = SpellingStage.FreeRecall)
        progress = evaluate(progress, SpellingQuestionType.FreeRecall, day = 0).nextProgress
        progress = evaluate(progress, SpellingQuestionType.FreeRecall, day = 1).nextProgress
        assertEquals(SpellingStage.FreeRecall, progress.stage)
        progress = evaluate(progress, SpellingQuestionType.FreeRecall, day = 8).nextProgress
        assertEquals(SpellingStage.Retained, progress.stage)
    }

    @Test
    fun `two free recall failures restore guidance`() {
        var progress = SpellingProgress(stage = SpellingStage.FreeRecall)
        progress = evaluate(progress, SpellingQuestionType.FreeRecall, answer = "enviroment").nextProgress
        assertEquals(SpellingStage.FreeRecall, progress.stage)
        progress = evaluate(progress, SpellingQuestionType.FreeRecall, answer = "enviroment", day = 1).nextProgress
        assertEquals(SpellingStage.GuidedRecall, progress.stage)
    }

    @Test
    fun `errors and weak segment describe an omission`() {
        val result = evaluate(
            SpellingProgress(stage = SpellingStage.FreeRecall),
            SpellingQuestionType.FreeRecall,
            answer = "enviroment",
        )
        assertTrue(SpellingErrorType.Omission in result.errorTypes)
        assertTrue(result.weakSegment!!.segment.contains("ron"))
        assertEquals(0.0, result.masteryCredit, 0.0)
    }

    @Test
    fun `receive typo is a vowel transposition`() {
        val errors = SpellingEngine.classifyErrors("receive", "recieve")
        assertTrue(SpellingErrorType.Transposition in errors)
        assertTrue(SpellingErrorType.VowelOrder in errors)
    }

    @Test
    fun `hints lower mastery credit`() {
        assertEquals(1.0, SpellingEngine.masteryCredit(true, 0), 0.0)
        assertEquals(0.4, SpellingEngine.masteryCredit(true, 3), 0.0)
        assertEquals(0.0, SpellingEngine.masteryCredit(true, 5), 0.0)
    }

    @Test
    fun `asking for a hint without an answer gives stable structural help`() {
        assertEquals(
            "首字母是 e，一共 11 个字母。",
            SpellingEngine.hintText("environment", "", 1, emptyList()),
        )
    }

    @Test
    fun `recognition distractors are deterministic and unique`() {
        val options = SpellingEngine.recognitionOptions("environment")
        assertEquals(4, options.size)
        assertEquals(4, options.distinct().size)
        assertTrue("environment" in options)
        assertEquals(options, SpellingEngine.recognitionOptions("environment"))
    }

    @Test
    fun `mask prioritizes a known weak segment`() {
        val masked = SpellingEngine.maskedWord(
            "environment",
            listOf(WeakSegment("viron", 2, 7, 4)),
            chunk = true,
        )
        assertEquals("en_____ment", masked)
    }

    private fun evaluate(
        progress: SpellingProgress,
        type: SpellingQuestionType,
        answer: String = "environment",
        day: Long = 0,
    ) = SpellingEngine.evaluate(
        progress = progress,
        expected = "environment",
        answer = answer,
        questionType = type,
        hintLevel = 0,
        attemptedAt = Instant.parse("2026-01-01T00:00:00Z").plusSeconds(day * 86_400),
        zoneId = ZoneOffset.UTC,
    )
}
