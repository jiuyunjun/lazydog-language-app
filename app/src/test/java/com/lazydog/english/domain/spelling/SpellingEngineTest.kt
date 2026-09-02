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
    fun `stored facts beat the local guesswork`() {
        val facts = SpellingFacts(
            chunks = listOf("nec", "ess", "ary"),
            trickyPart = "cess",
            misspellings = listOf("neccessary", "necesary", "neccesary"),
        )
        // 启发式对 necessary 拆得毫无意义，存下来的说了算。
        assertEquals(listOf("nec", "ess", "ary"), SpellingEngine.chunkWord("necessary", facts))

        // 四选一给的是真人会写的错法，不是本地调字母调出来的怪东西。
        val options = SpellingEngine.recognitionOptions("necessary", facts)
        assertEquals(4, options.size)
        assertTrue("neccessary" in options)
        assertTrue("necessary" in options)
    }

    @Test
    fun `chunks that cannot spell the word are ignored`() {
        val broken = SpellingFacts(chunks = listOf("nec", "ess", "aryy"))
        // 拼不回原词就是坏数据，宁可退回启发式，也不能拿它去挖空。
        assertEquals(SpellingEngine.chunkWord("necessary"), SpellingEngine.chunkWord("necessary", broken))
    }

    @Test
    fun `too few real misspellings fall back to generated ones`() {
        val thin = SpellingFacts(misspellings = listOf("neccessary"))
        assertEquals(
            SpellingEngine.recognitionOptions("necessary"),
            SpellingEngine.recognitionOptions("necessary", thin),
        )
    }

    @Test
    fun `with no user history the mask targets the known tricky part`() {
        val facts = SpellingFacts(trickyPart = "par")
        assertEquals(
            "se___ate",
            SpellingEngine.maskedWord("separate", emptyList(), chunk = false, facts = facts),
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

    @Test
    fun `a first sighting is an exposure card, not a quiz`() {
        // S0 是"接触"：第一次见到一个词就丢四个拼写让人挑，用户是在猜。
        assertEquals(
            SpellingQuestionType.Exposure,
            SpellingEngine.questionType(SpellingProgress(stage = SpellingStage.Seen)),
        )
        val at = Instant.parse("2026-01-01T00:00:00Z")
        val after = SpellingEngine.afterExposure(SpellingProgress(stage = SpellingStage.Seen), at)
        assertEquals(SpellingStage.Recognition, after.stage)
        // 看过就该在本轮末尾再露一面，所以停在阶梯最低那一档。
        assertEquals(SpellingEngine.FIRST_INTERVAL_MINUTES, after.currentIntervalMinutes)
        assertEquals(at.plusSeconds(600), after.nextSpellingAt)
    }

    @Test
    fun `the review ladder climbs one step and falls two`() {
        val ladder = SpellingEngine.INTERVAL_LADDER_MINUTES
        assertEquals(listOf(10, 1_440, 4_320, 10_080, 20_160, 43_200, 86_400), ladder)
        // 干净地答对：上一档。
        assertEquals(1_440, SpellingEngine.nextIntervalMinutes(10, correct = true, credit = 1.0))
        assertEquals(4_320, SpellingEngine.nextIntervalMinutes(1_440, correct = true, credit = 1.0))
        // 靠提示写出来的只保住当前这档，不换一次翻倍。
        assertEquals(4_320, SpellingEngine.nextIntervalMinutes(4_320, correct = true, credit = 0.4))
        // 设计稿 §13 的例子：14 天答错退到 3 天。
        assertEquals(4_320, SpellingEngine.nextIntervalMinutes(20_160, correct = false, credit = 0.0))
        // 退到底就是 10 分钟：这一轮结束前还要再考一次。
        assertEquals(10, SpellingEngine.nextIntervalMinutes(1_440, correct = false, credit = 0.0))
        assertEquals(86_400, SpellingEngine.nextIntervalMinutes(86_400, correct = true, credit = 1.0))
    }

    @Test
    fun `a miss drops the word back into this session`() {
        // 干净答对的词按阶梯往上走，明天才见；答错的退到十分钟档，
        // 也就是本轮结束前还要再考一次，而不是错完就过去了。
        val clean = evaluate(SpellingProgress(stage = SpellingStage.FreeRecall), SpellingQuestionType.FreeRecall)
        assertEquals(1_440, clean.nextProgress.currentIntervalMinutes)

        val missed = evaluate(
            SpellingProgress(stage = SpellingStage.FreeRecall, currentIntervalMinutes = 10),
            SpellingQuestionType.FreeRecall,
            answer = "enviroment",
        )
        assertEquals(10, missed.nextProgress.currentIntervalMinutes)
        assertEquals(
            Instant.parse("2026-01-01T00:10:00Z"),
            missed.nextProgress.nextSpellingAt,
        )
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
