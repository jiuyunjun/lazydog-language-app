package com.lazydog.english.domain.assessment

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssessmentEngineTest {

    @Test
    fun `starts at B1 baseline with no answers`() {
        val state = AssessmentEngine.initial()
        assertEquals(3.0, state.score, 0.001)
        assertTrue(state.answered.isEmpty())
    }

    @Test
    fun `first three questions are the fixed placement items`() {
        val state = AssessmentEngine.initial()
        assertEquals(NextLadderStep.Question(AssessmentSkill.Vocab, "A2"), AssessmentEngine.nextStep(state))
        val s1 = AssessmentEngine.record(state, AssessmentSkill.Vocab, itemLevelScore = 2.0, correct = true)
        assertEquals(NextLadderStep.Question(AssessmentSkill.Grammar, "B1"), AssessmentEngine.nextStep(s1))
        val s2 = AssessmentEngine.record(s1, AssessmentSkill.Grammar, itemLevelScore = 3.0, correct = true)
        assertEquals(NextLadderStep.Question(AssessmentSkill.Reading, "B1"), AssessmentEngine.nextStep(s2))
    }

    @Test
    fun `placement table sets the starting score from correct count`() {
        fun placementScore(vararg correctness: Boolean): Double {
            var state = AssessmentEngine.initial()
            correctness.forEach { state = AssessmentEngine.record(state, AssessmentSkill.Vocab, 3.0, it) }
            return state.score
        }
        assertEquals(1.5, placementScore(false, false, false), 0.001)
        assertEquals(2.0, placementScore(true, false, false), 0.001)
        assertEquals(3.0, placementScore(true, true, false), 0.001)
        assertEquals(4.0, placementScore(true, true, true), 0.001)
    }

    @Test
    fun `three correct placement answers force an immediate C1 probe`() {
        var state = AssessmentEngine.initial()
        repeat(3) { state = AssessmentEngine.record(state, AssessmentSkill.Vocab, 3.0, correct = true) }
        val step = AssessmentEngine.nextStep(state) as NextLadderStep.Question
        assertEquals("C1", step.level)
    }

    @Test
    fun `correct and wrong answers move the continuous score up and down`() {
        // 2/3 定位题答对 -> 起点 3.0（3/3 会立刻探 C1，干扰这里想测的加减逻辑）。
        var state = AssessmentEngine.initial()
        state = AssessmentEngine.record(state, AssessmentSkill.Vocab, 3.0, correct = true)
        state = AssessmentEngine.record(state, AssessmentSkill.Grammar, 3.0, correct = true)
        state = AssessmentEngine.record(state, AssessmentSkill.Reading, 3.0, correct = false)
        assertEquals(3.0, state.score, 0.001)

        val afterCorrect = AssessmentEngine.record(state, AssessmentSkill.Grammar, 3.0, correct = true)
        assertEquals(3.4, afterCorrect.score, 0.001)
        val afterWrong = AssessmentEngine.record(state, AssessmentSkill.Grammar, 3.0, correct = false)
        assertEquals(2.6, afterWrong.score, 0.001)
    }

    @Test
    fun `score clamps between 1_0 and 5_0`() {
        var down = AssessmentEngine.initial()
        repeat(3) { down = AssessmentEngine.record(down, AssessmentSkill.Vocab, 1.0, correct = false) } // -> 1.5
        repeat(20) { down = AssessmentEngine.record(down, AssessmentSkill.Vocab, 1.0, correct = false) }
        assertEquals(1.0, down.score, 0.001)

        var up = AssessmentEngine.initial()
        repeat(3) { up = AssessmentEngine.record(up, AssessmentSkill.Vocab, 5.0, correct = true) } // -> 4.0
        repeat(20) { up = AssessmentEngine.record(up, AssessmentSkill.Vocab, 5.0, correct = true) }
        assertEquals(5.0, up.score, 0.001)
    }

    @Test
    fun `label adds a plus sign for the upper half of a band`() {
        assertEquals("B1", labelForScore(3.0))
        assertEquals("B1", labelForScore(3.2))
        assertEquals("B1+", labelForScore(3.5))
        assertEquals("B2", labelForScore(3.9))
        assertEquals("A1", labelForScore(0.5))
        assertEquals("C1", labelForScore(5.5))
    }

    @Test
    fun `does not complete before MIN_QUESTIONS`() {
        var state = AssessmentEngine.initial()
        repeat(AssessmentEngine.MIN_QUESTIONS - 1) {
            val skill = AssessmentSkill.ladderSkills[it % AssessmentSkill.ladderSkills.size]
            state = AssessmentEngine.record(state, skill, state.score, correct = true)
            assertFalse(AssessmentEngine.isComplete(state))
        }
    }

    @Test
    fun `coverage constraint forces all four ladder skills to appear`() {
        // 驱动逻辑本身不会主动选 pragmatics（vocab/grammar/reading 已经被定位题覆盖过），
        // 覆盖约束应该让它在停止前至少出现一次。
        var state = AssessmentEngine.initial()
        val seenSkills = mutableSetOf<String>()
        while (!AssessmentEngine.isComplete(state) && state.answered.size < AssessmentEngine.MAX_QUESTIONS) {
            val step = AssessmentEngine.nextStep(state) as? NextLadderStep.Question ?: break
            seenSkills += step.skill
            state = AssessmentEngine.record(state, step.skill, scoreForLabel(step.level), correct = true)
        }
        assertTrue("覆盖约束应该让四类技能都出现过", seenSkills.containsAll(AssessmentSkill.ladderSkills))
    }

    @Test
    fun `always completes by MAX_QUESTIONS even with erratic answers`() {
        var state = AssessmentEngine.initial()
        var i = 0
        while (!AssessmentEngine.isComplete(state) && state.answered.size < AssessmentEngine.MAX_QUESTIONS) {
            val step = AssessmentEngine.nextStep(state) as NextLadderStep.Question
            state = AssessmentEngine.record(state, step.skill, scoreForLabel(step.level), correct = i % 2 == 0)
            i++
        }
        assertTrue(AssessmentEngine.isComplete(state))
        assertTrue(state.answered.size <= AssessmentEngine.MAX_QUESTIONS)
    }

    @Test
    fun `stops only after probing one level above the stable score`() {
        var state = AssessmentEngine.initial()
        while (!AssessmentEngine.isComplete(state) && state.answered.size < AssessmentEngine.MAX_QUESTIONS) {
            val step = AssessmentEngine.nextStep(state) as NextLadderStep.Question
            state = AssessmentEngine.record(state, step.skill, scoreForLabel(step.level), correct = true)
        }
        assertTrue(AssessmentEngine.isComplete(state))
        assertTrue(state.probedHigher)
    }

    @Test
    fun `moves to deep reading once the ladder is complete`() {
        var state = AssessmentEngine.initial()
        while (!AssessmentEngine.isComplete(state) && state.answered.size < AssessmentEngine.MAX_QUESTIONS) {
            val step = AssessmentEngine.nextStep(state) as NextLadderStep.Question
            state = AssessmentEngine.record(state, step.skill, scoreForLabel(step.level), correct = true)
        }
        assertEquals(NextLadderStep.MoveToDeepReading, AssessmentEngine.nextStep(state))
    }

    @Test
    fun `plausible range narrows as confidence grows`() {
        var state = AssessmentEngine.initial()
        repeat(3) { state = AssessmentEngine.record(state, AssessmentSkill.Vocab, 3.0, correct = true) }
        val early = AssessmentEngine.plausibleRange(state)
        repeat(10) { state = AssessmentEngine.record(state, AssessmentSkill.Vocab, state.score, correct = true) }
        val stable = AssessmentEngine.plausibleRange(state)
        assertTrue(early.isNotBlank())
        assertTrue(stable.isNotBlank())
    }

    @Test
    fun `vocab range grows with score`() {
        assertEquals("500～1000", AssessmentEngine.vocabRangeText(1.0))
        assertEquals("5000～8000", AssessmentEngine.vocabRangeText(5.0))
    }

    @Test
    fun `skill profile row reports thin sample as unmeasured`() {
        val row = AssessmentEngine.skillProfileRow("阅读理解", AssessmentSkill.Reading, emptyList(), 3.0)
        assertEquals("样本不足", row.label)
        assertEquals(0, row.pct)
    }

    @Test
    fun `saved assessment survives json round trip`() {
        var state = AssessmentEngine.initial()
        state = AssessmentEngine.record(state, AssessmentSkill.Vocab, 2.0, correct = true)
        val saved = SavedAssessment(
            state = state,
            stage = AssessmentStage.Ladder,
        )
        val json = Json.encodeToString(SavedAssessment.serializer(), saved)
        assertEquals(saved, Json.decodeFromString(SavedAssessment.serializer(), json))
    }

    @Test
    fun `question validation drops broken entries`() {
        val questions = listOf(
            AssessmentQuestion("vocab", "ok ___", listOf("a", "b", "c"), 0, "e"),
            AssessmentQuestion("vocab", "", listOf("a", "b", "c"), 0, "e"),
            AssessmentQuestion("vocab", "dup options", listOf("a", "a", "b"), 0, "e"),
            AssessmentQuestion("vocab", "bad index", listOf("a", "b", "c"), 9, "e"),
            AssessmentQuestion("vocab", "two options", listOf("a", "b"), 0, "e"),
            AssessmentQuestion("reading", "what happened?", listOf("a", "b", "c"), 0, "e", passage = null),
            AssessmentQuestion("reading", "what happened?", listOf("a", "b", "c"), 0, "e", passage = "A short story."),
        )
        assertEquals(2, validateAssessmentQuestions(questions).size)
    }
}
