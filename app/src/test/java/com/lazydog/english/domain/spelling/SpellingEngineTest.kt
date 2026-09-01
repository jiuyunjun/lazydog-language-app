package com.lazydog.english.domain.spelling

import com.lazydog.english.core.model.KnowledgeStage
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
            "一共 11 个字母，可以分成 3 个词块。",
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

    @Test
    fun `no hint below the last one spells the word out`() {
        // 逐级要提示不能是"点四下看答案"：第 5 级之前，任何一级都不该
        // 让完整拼写出现在提示里。
        for (level in 0..4) {
            val withAnswer = SpellingEngine.hintText("environment", "enviroment", level, emptyList())
            val withoutAnswer = SpellingEngine.hintText("environment", "", level, emptyList())
            assertTrue("level $level leaked: $withAnswer", !withAnswer.contains("environment"))
            assertTrue("level $level leaked: $withoutAnswer", !withoutAnswer.contains("environment"))
        }
        assertTrue(SpellingEngine.hintText("environment", "enviroment", 5, emptyList()).contains("environment"))
    }

    @Test
    fun `the error region hint blanks the region instead of showing it`() {
        val hint = SpellingEngine.hintText("environment", "enviroment", 2, emptyList())
        assertTrue(hint, hint.contains("_"))
        assertTrue(hint, !hint.contains("viron"))
    }

    @Test
    fun `the fragment hint is strictly narrower than the weak segment`() {
        val weak = listOf(WeakSegment("viron", 2, 7, 4))
        val hint = SpellingEngine.hintText("environment", "enviroment", 3, weak)
        assertTrue(hint, !hint.contains("viron"))
    }

    @Test
    fun `the chunk skeleton keeps the weak chunk blank`() {
        val hint = SpellingEngine.hintText("environment", "enviroment", 4, emptyList())
        assertTrue(hint, hint.contains("en") && hint.contains("ment"))
        assertTrue(hint, !hint.contains("viron"))
    }

    @Test
    fun `the first hint names the kind of mistake without locating it`() {
        val doubling = SpellingEngine.hintText("necessary", "neccessary", 1, emptyList())
        assertTrue(doubling, doubling.contains("双写"))
        val order = SpellingEngine.hintText("receive", "recieve", 1, emptyList())
        assertTrue(order, order.contains("元音"))
    }

    @Test
    fun `a filled-in blank is judged as the whole word`() {
        val weak = listOf(WeakSegment("viron", 2, 7, 4))
        assertEquals(
            "environment",
            SpellingEngine.fillMasked("environment", "viron", weak, chunk = true),
        )
        assertEquals(
            "enviroment",
            SpellingEngine.fillMasked("environment", "viro", weak, chunk = true),
        )
    }

    @Test
    fun `a word already in rotation does not start back at multiple choice`() {
        // 一律从 Seen 起考的话，每个词都要先答对两轮四选一才轮得到挖空，
        // 结果就是"逐字母下划线"那几屏实际上永远见不到。
        assertEquals(SpellingStage.Seen, SpellingEngine.initialStageFor(KnowledgeStage.Exposed))
        assertEquals(SpellingStage.PartialRecall, SpellingEngine.initialStageFor(KnowledgeStage.Learning))
        assertEquals(SpellingStage.GuidedRecall, SpellingEngine.initialStageFor(KnowledgeStage.Familiar))
        assertEquals(SpellingStage.FreeRecall, SpellingEngine.initialStageFor(KnowledgeStage.Mastered))

        // 学习中的词第一次考的就是挖空题，也就是逐字母格子那一屏。
        assertEquals(
            SpellingQuestionType.PartialCompletion,
            SpellingEngine.questionType(SpellingProgress(stage = SpellingEngine.initialStageFor(KnowledgeStage.Learning))),
        )
    }

    @Test
    fun `chunking splits prefix stem and suffix`() {
        assertEquals(listOf("en", "viron", "ment"), SpellingEngine.chunkWord("environment"))
    }

    @Test
    fun `a fast single letter slip does not demote or count as a failure`() {
        val progress = SpellingProgress(stage = SpellingStage.FreeRecall, failureStreak = 1)
        val result = evaluate(
            progress,
            SpellingQuestionType.FreeRecall,
            answer = "environmentt",
            responseTimeMillis = 900,
        )
        assertTrue(result.likelyTypo)
        // 已经错过一次，这次再错本该降级；手滑不算，阶段和连错次数都不动。
        assertEquals(SpellingStage.FreeRecall, result.nextProgress.stage)
        assertEquals(1, result.nextProgress.failureStreak)
    }

    @Test
    fun `the same slip typed slowly counts as forgetting`() {
        val progress = SpellingProgress(stage = SpellingStage.FreeRecall, failureStreak = 1)
        val result = evaluate(
            progress,
            SpellingQuestionType.FreeRecall,
            answer = "environmentt",
            responseTimeMillis = 30_000,
        )
        assertTrue(!result.likelyTypo)
        assertEquals(SpellingStage.GuidedRecall, result.nextProgress.stage)
    }

    @Test
    fun `a vowel swap that sounds the same is classified as phonetic`() {
        val errors = SpellingEngine.classifyErrors("definitely", "definately")
        assertTrue(SpellingErrorType.Phonetic in errors)
    }

    @Test
    fun `dropping a letter is not phonetic`() {
        val errors = SpellingEngine.classifyErrors("environment", "enviroment")
        assertTrue(SpellingErrorType.Omission in errors)
        assertTrue(SpellingErrorType.Phonetic !in errors)
    }

    @Test
    fun `audio prompts feed the phoneme grapheme dimension only`() {
        val silent = evaluate(SpellingProgress(), SpellingQuestionType.FreeRecall)
        assertEquals(0.0, silent.nextProgress.phonemeGraphemeScore, 0.0001)

        val spoken = evaluate(SpellingProgress(), SpellingQuestionType.FreeRecall, audioPrompted = true)
        assertTrue(spoken.nextProgress.phonemeGraphemeScore > 0.0)
    }

    private fun evaluate(
        progress: SpellingProgress,
        type: SpellingQuestionType,
        answer: String = "environment",
        day: Long = 0,
        responseTimeMillis: Long = 0,
        audioPrompted: Boolean = false,
    ) = SpellingEngine.evaluate(
        progress = progress,
        expected = "environment",
        answer = answer,
        questionType = type,
        hintLevel = 0,
        attemptedAt = Instant.parse("2026-01-01T00:00:00Z").plusSeconds(day * 86_400),
        responseTimeMillis = responseTimeMillis,
        audioPrompted = audioPrompted,
        zoneId = ZoneOffset.UTC,
    )
}
