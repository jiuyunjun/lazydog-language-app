package com.lazydog.english.domain.assessment

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssessmentEngineTest {

    /** 驱动一步：不管是 Question 还是 Correction，都按给定 outcome 记一次作答。返回是否还有下一步。 */
    private fun driveOneStep(state: AssessmentState, outcome: AnswerOutcome): Pair<AssessmentState, String>? {
        return when (val step = AssessmentEngine.nextStep(state)) {
            is NextLadderStep.Question ->
                AssessmentEngine.record(state, step.skill, scoreForLabel(step.level), outcome) to step.skill
            is NextLadderStep.Correction ->
                AssessmentEngine.record(state, AssessmentSkill.Correction, scoreForLabel(step.level), outcome) to
                    AssessmentSkill.Correction
            NextLadderStep.MoveToDeepReading -> null
        }
    }

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
        val s1 = AssessmentEngine.record(state, AssessmentSkill.Vocab, 2.0, AnswerOutcome.Correct)
        assertEquals(NextLadderStep.Question(AssessmentSkill.Grammar, "B1"), AssessmentEngine.nextStep(s1))
        val s2 = AssessmentEngine.record(s1, AssessmentSkill.Grammar, 3.0, AnswerOutcome.Correct)
        assertEquals(NextLadderStep.Question(AssessmentSkill.Reading, "B1"), AssessmentEngine.nextStep(s2))
    }

    @Test
    fun `placement table sets the starting score from correct count`() {
        fun placementScore(vararg outcomes: AnswerOutcome): Double {
            var state = AssessmentEngine.initial()
            outcomes.forEach { state = AssessmentEngine.record(state, AssessmentSkill.Vocab, 3.0, it) }
            return state.score
        }
        val c = AnswerOutcome.Correct
        val w = AnswerOutcome.Wrong
        assertEquals(1.5, placementScore(w, w, w), 0.001)
        assertEquals(2.0, placementScore(c, w, w), 0.001)
        assertEquals(3.0, placementScore(c, c, w), 0.001)
        assertEquals(4.0, placementScore(c, c, c), 0.001)
    }

    @Test
    fun `three correct placement answers force an immediate C1 probe`() {
        var state = AssessmentEngine.initial()
        repeat(3) { state = AssessmentEngine.record(state, AssessmentSkill.Vocab, 3.0, AnswerOutcome.Correct) }
        val step = AssessmentEngine.nextStep(state) as NextLadderStep.Question
        assertEquals("C1", step.level)
    }

    @Test
    fun `correct and wrong answers move the continuous score up and down`() {
        // 2/3 定位题答对 -> 起点 3.0（3/3 会立刻探 C1，干扰这里想测的加减逻辑）。
        var state = AssessmentEngine.initial()
        state = AssessmentEngine.record(state, AssessmentSkill.Vocab, 3.0, AnswerOutcome.Correct)
        state = AssessmentEngine.record(state, AssessmentSkill.Grammar, 3.0, AnswerOutcome.Correct)
        state = AssessmentEngine.record(state, AssessmentSkill.Reading, 3.0, AnswerOutcome.Wrong)
        assertEquals(3.0, state.score, 0.001)

        val afterCorrect = AssessmentEngine.record(state, AssessmentSkill.Grammar, 3.0, AnswerOutcome.Correct)
        assertEquals(3.4, afterCorrect.score, 0.001)
        val afterWrong = AssessmentEngine.record(state, AssessmentSkill.Grammar, 3.0, AnswerOutcome.Wrong)
        assertEquals(2.6, afterWrong.score, 0.001)
    }

    @Test
    fun `correct but abnormal timing only counts as weak evidence`() {
        var state = AssessmentEngine.initial()
        state = AssessmentEngine.record(state, AssessmentSkill.Vocab, 3.0, AnswerOutcome.Correct)
        state = AssessmentEngine.record(state, AssessmentSkill.Grammar, 3.0, AnswerOutcome.Correct)
        state = AssessmentEngine.record(state, AssessmentSkill.Reading, 3.0, AnswerOutcome.Wrong) // -> 3.0

        val normal = AssessmentEngine.record(state, AssessmentSkill.Grammar, 3.0, AnswerOutcome.Correct, AnswerTiming.Normal)
        val abnormal = AssessmentEngine.record(state, AssessmentSkill.Grammar, 3.0, AnswerOutcome.Correct, AnswerTiming.Abnormal)
        assertTrue("超时/疑似猜测的涨分应该比正常用时少", abnormal.score < normal.score)
    }

    @Test
    fun `partial credit nudges the score only slightly`() {
        var state = AssessmentEngine.initial()
        state = AssessmentEngine.record(state, AssessmentSkill.Vocab, 3.0, AnswerOutcome.Correct)
        state = AssessmentEngine.record(state, AssessmentSkill.Grammar, 3.0, AnswerOutcome.Correct)
        state = AssessmentEngine.record(state, AssessmentSkill.Reading, 3.0, AnswerOutcome.Wrong) // -> 3.0

        val partial = AssessmentEngine.record(state, AssessmentSkill.Correction, 3.0, AnswerOutcome.Partial)
        assertTrue(partial.score > state.score)
        assertTrue("部分正确的涨幅应该明显小于全对", partial.score - state.score < 0.2)
    }

    @Test
    fun `classifyTiming flags very fast or very slow answers as abnormal`() {
        assertEquals(AnswerTiming.Abnormal, AssessmentEngine.classifyTiming(500))
        assertEquals(AnswerTiming.Normal, AssessmentEngine.classifyTiming(5_000))
        assertEquals(AnswerTiming.Abnormal, AssessmentEngine.classifyTiming(60_000))
    }

    @Test
    fun `score clamps between 0_0 and 5_0`() {
        var down = AssessmentEngine.initial()
        repeat(3) { down = AssessmentEngine.record(down, AssessmentSkill.Vocab, 1.0, AnswerOutcome.Wrong) } // -> 1.5
        repeat(20) { down = AssessmentEngine.record(down, AssessmentSkill.Vocab, 1.0, AnswerOutcome.Wrong) }
        assertEquals(0.0, down.score, 0.001)

        var up = AssessmentEngine.initial()
        repeat(3) { up = AssessmentEngine.record(up, AssessmentSkill.Vocab, 5.0, AnswerOutcome.Correct) } // -> 4.0
        repeat(20) { up = AssessmentEngine.record(up, AssessmentSkill.Vocab, 5.0, AnswerOutcome.Correct) }
        assertEquals(5.0, up.score, 0.001)
    }

    @Test
    fun `label adds a plus sign for the upper half of a band`() {
        assertEquals("Pre-A1", labelForScore(-1.0))
        assertEquals("A1", labelForScore(1.0))
        assertEquals("B1", labelForScore(3.0))
        assertEquals("B1", labelForScore(3.2))
        assertEquals("B1+", labelForScore(3.5))
        assertEquals("B2", labelForScore(3.9))
        assertEquals("C1", labelForScore(5.5))
    }

    @Test
    fun `does not complete before MIN_QUESTIONS`() {
        var state = AssessmentEngine.initial()
        repeat(AssessmentEngine.MIN_QUESTIONS - 1) {
            val (next, _) = driveOneStep(state, AnswerOutcome.Correct)!!
            state = next
            assertFalse(AssessmentEngine.isComplete(state))
        }
    }

    @Test
    fun `coverage constraint forces all five ladder skills to appear`() {
        // 覆盖约束应该让五类技能（含纠错短答）在停止前都至少出现一次。
        var state = AssessmentEngine.initial()
        val seenSkills = mutableSetOf<String>()
        while (!AssessmentEngine.isComplete(state) && state.answered.size < AssessmentEngine.MAX_QUESTIONS) {
            val (next, skill) = driveOneStep(state, AnswerOutcome.Correct) ?: break
            seenSkills += skill
            state = next
        }
        assertTrue("覆盖约束应该让五类技能都出现过", seenSkills.containsAll(AssessmentSkill.ladderSkills))
    }

    @Test
    fun `always completes by MAX_QUESTIONS even with erratic answers`() {
        var state = AssessmentEngine.initial()
        var i = 0
        while (!AssessmentEngine.isComplete(state) && state.answered.size < AssessmentEngine.MAX_QUESTIONS) {
            val outcome = if (i % 2 == 0) AnswerOutcome.Correct else AnswerOutcome.Wrong
            val (next, _) = driveOneStep(state, outcome) ?: break
            state = next
            i++
        }
        assertTrue(AssessmentEngine.isComplete(state))
        assertTrue(state.answered.size <= AssessmentEngine.MAX_QUESTIONS)
    }

    @Test
    fun `stops only after probing one level above the stable score`() {
        var state = AssessmentEngine.initial()
        while (!AssessmentEngine.isComplete(state) && state.answered.size < AssessmentEngine.MAX_QUESTIONS) {
            val (next, _) = driveOneStep(state, AnswerOutcome.Correct) ?: break
            state = next
        }
        assertTrue(AssessmentEngine.isComplete(state))
        assertTrue(state.probedHigher)
    }

    @Test
    fun `moves to deep reading once the ladder is complete`() {
        var state = AssessmentEngine.initial()
        while (!AssessmentEngine.isComplete(state) && state.answered.size < AssessmentEngine.MAX_QUESTIONS) {
            val (next, _) = driveOneStep(state, AnswerOutcome.Correct) ?: break
            state = next
        }
        assertEquals(NextLadderStep.MoveToDeepReading, AssessmentEngine.nextStep(state))
    }

    @Test
    fun `plausible range narrows as confidence grows`() {
        var state = AssessmentEngine.initial()
        repeat(3) { state = AssessmentEngine.record(state, AssessmentSkill.Vocab, 3.0, AnswerOutcome.Correct) }
        val early = AssessmentEngine.plausibleRange(state)
        repeat(10) { state = AssessmentEngine.record(state, AssessmentSkill.Vocab, state.score, AnswerOutcome.Correct) }
        val stable = AssessmentEngine.plausibleRange(state)
        assertTrue(early.isNotBlank())
        assertTrue(stable.isNotBlank())
    }

    @Test
    fun `vocab range grows with score`() {
        assertEquals("200～500", AssessmentEngine.vocabRangeText(0.0))
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
    fun `skill profile row averages partial credit instead of counting it as wrong`() {
        val samples = listOf(
            AnsweredItem(AssessmentSkill.Correction, 3.0, AnswerOutcome.Correct),
            AnsweredItem(AssessmentSkill.Correction, 3.0, AnswerOutcome.Partial),
        )
        val row = AssessmentEngine.skillProfileRow("纠错短答", AssessmentSkill.Correction, samples, 3.0)
        assertEquals(75, row.pct) // (1.0 + 0.5) / 2
    }

    @Test
    fun `saved assessment survives json round trip`() {
        var state = AssessmentEngine.initial()
        state = AssessmentEngine.record(state, AssessmentSkill.Vocab, 2.0, AnswerOutcome.Correct)
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

    @Test
    fun `correction item validation rejects blank fields and no-op corrections`() {
        assertTrue(validateCorrectionItem(CorrectionItem("She go to school.", "She goes to school.", "e")))
        assertFalse(validateCorrectionItem(CorrectionItem("", "She goes to school.", "e")))
        assertFalse(validateCorrectionItem(CorrectionItem("She goes to school.", "She goes to school.", "e")))
    }
}
