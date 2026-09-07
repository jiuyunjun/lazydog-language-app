package com.lazydog.english.domain.pronunciation

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * 守住这个模块最容易被改坏的三条口径：证据不够不下结论、一次异常不降级、
 * 两条能力线不合并（`发音与音标学习DESIGN.md` §14～§15、§26）。
 */
class PronunciationScoringTest {

    private var clock = 0L

    private fun perception(
        correct: Boolean,
        replay: Int = 0,
        hint: Int = 0,
    ) = PerceptionAttempt(
        targetId = TARGET,
        exercise = PerceptionExercise.MinimalPairTwo,
        correct = correct,
        replayCount = replay,
        hintLevel = hint,
        occurredAt = ++clock,
    )

    private fun production(
        score: Int?,
        level: ProductionLevel = ProductionLevel.Word,
        text: String = "think",
        quality: RecordingQuality = RecordingQuality.Ok,
    ) = ProductionAttempt(
        targetId = TARGET,
        level = level,
        text = text,
        providerScore = score,
        targetScore = score,
        quality = quality,
        occurredAt = ++clock,
    )

    @Test
    fun `样本不够就不算有把握`() {
        val attempts = List(SkillEstimate.MIN_CONFIDENT_SAMPLES - 1) { perception(correct = true) }
        val estimate = PerceptionScoring.estimate(attempts)

        assertEquals(SkillEstimate.MIN_CONFIDENT_SAMPLES - 1, estimate.sampleCount)
        assertFalse("样本少于 8 条时不该被当成结论", estimate.confident)
        assertTrue(estimate.confidence < 1f)
    }

    @Test
    fun `样本够了才算有把握`() {
        val attempts = List(SkillEstimate.MIN_CONFIDENT_SAMPLES) { perception(correct = true) }

        assertTrue(PerceptionScoring.estimate(attempts).confident)
    }

    @Test
    fun `重放和提示会让这次答对打折，但打不到零`() {
        val clean = PerceptionScoring.credit(perception(correct = true))
        val replayed = PerceptionScoring.credit(perception(correct = true, replay = 2))
        val hinted = PerceptionScoring.credit(perception(correct = true, hint = 4))

        assertEquals(1f, clean, 1e-6f)
        assertTrue("重放两次才听出来，不该和一遍听出来算同一分", replayed < clean)
        assertTrue(hinted < replayed)
        // 折到 0 会让「靠提示答对」和「答错」变成一回事，那是另一种失真。
        assertTrue("答对再怎么打折也不该跌到和答错一样", hinted >= 0.3f)
    }

    @Test
    fun `提示拉到答案那一级的题整题不计入听辨分`() {
        val attempts = List(8) { perception(correct = true) } +
            List(4) { perception(correct = false, hint = PerceptionScoring.ANSWER_HINT_LEVEL) }

        val estimate = PerceptionScoring.estimate(attempts)

        assertEquals("看了答案的那 4 题不该进样本", 8, estimate.sampleCount)
        assertEquals(100, estimate.percent)
    }

    @Test
    fun `连对次数遇到第一个错就停`() {
        val attempts = listOf(
            perception(correct = true),
            perception(correct = false),
            perception(correct = true),
            perception(correct = true),
            perception(correct = true),
        )

        assertEquals(3, PerceptionScoring.currentStreak(attempts))
    }

    @Test
    fun `单次异常低分不构成稳定问题`() {
        val attempts = listOf(82, 79, 81, 61, 80).map { production(it) }

        assertFalse(
            "82/79/81/61/80 里那个 61 是噪声，不该被当成结论",
            ProductionScoring.isStableProblem(attempts),
        )
        assertTrue(ProductionScoring.estimate(attempts).percent >= 75)
    }

    @Test
    fun `连续低分才算稳定问题`() {
        val attempts = listOf(67, 64, 62, 66, 63).map { production(it) }

        assertTrue(ProductionScoring.isStableProblem(attempts))
    }

    @Test
    fun `录音不可用的那次不进发音分`() {
        val attempts = listOf(
            production(85),
            production(null, quality = RecordingQuality.TooQuiet),
            production(83),
        )

        val estimate = ProductionScoring.estimate(attempts)

        assertEquals("没收到声音不代表这个音发不好", 2, estimate.sampleCount)
        assertEquals(84, estimate.percent)
    }

    @Test
    fun `听得出来不等于发得出来`() {
        val perception = SkillEstimate(score = 0.92f, sampleCount = 30)
        val production = SkillEstimate(score = 0.63f, sampleCount = 12)

        val stage = PronunciationStages.stageFor(
            seenCard = true,
            perception = perception,
            production = production,
        )

        assertEquals(PronunciationStage.Discriminating, stage)
        val progress = PronunciationProgress(TARGET, perception, production, stage)
        assertTrue("这一组只该排跟读，耳朵那关已经过了", progress.hearsButCannotSay)
    }

    @Test
    fun `稳了要求换过词、还要在句子里站住`() {
        val perception = SkillEstimate(score = 0.93f, sampleCount = 30)
        val production = SkillEstimate(score = 0.85f, sampleCount = 12)

        val onlyOneWord = PronunciationStages.stageFor(
            seenCard = true,
            perception = perception,
            production = production,
            provenWordCount = 1,
            provenInSentence = true,
        )
        assertEquals("一个词读顺了不算会", PronunciationStage.Producing, onlyOneWord)

        val noSentence = PronunciationStages.stageFor(
            seenCard = true,
            perception = perception,
            production = production,
            provenWordCount = 5,
            provenInSentence = false,
        )
        assertEquals("没进过句子不算稳", PronunciationStage.Producing, noSentence)

        val stable = PronunciationStages.stageFor(
            seenCard = true,
            perception = perception,
            production = production,
            provenWordCount = 5,
            provenInSentence = true,
        )
        assertEquals(PronunciationStage.Stable, stable)
    }

    @Test
    fun `没看过卡也没练过就是还没练过`() {
        assertEquals(
            PronunciationStage.Unseen,
            PronunciationStages.stageFor(
                seenCard = false,
                perception = SkillEstimate.Unknown,
                production = SkillEstimate.Unknown,
            ),
        )
    }

    @Test
    fun `不同词里发对过才算迁移`() {
        val attempts = listOf(
            production(82, text = "three"),
            production(76, text = "think"),
            production(74, text = "nothing"),
            production(61, text = "thought"),
        )

        assertEquals(setOf("three", "think", "nothing"), ProductionScoring.provenWords(attempts))
    }

    private companion object {
        const val TARGET = "ph:th-voiceless"
    }
}
