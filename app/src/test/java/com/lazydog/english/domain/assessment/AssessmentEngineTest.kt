package com.lazydog.english.domain.assessment

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssessmentEngineTest {

    @Test
    fun `starts at A2 with no answers`() {
        val state = AssessmentEngine.initial()
        assertEquals(CefrLevel.A2, state.currentLevel)
        assertTrue(state.answered.isEmpty())
    }

    @Test
    fun `two consecutive correct answers level up and reset streak`() {
        var state = AssessmentEngine.initial()
        state = AssessmentEngine.record(state, correct = true)
        assertEquals(CefrLevel.A2, state.currentLevel)
        assertEquals(1, state.streak)
        state = AssessmentEngine.record(state, correct = true)
        assertEquals(CefrLevel.B1, state.currentLevel)
        assertEquals(0, state.streak)
    }

    @Test
    fun `wrong answer levels down immediately`() {
        var state = AssessmentEngine.initial()
        state = AssessmentEngine.record(state, correct = false)
        assertEquals(CefrLevel.A1, state.currentLevel)
    }

    @Test
    fun `levels clamp at both ends`() {
        var state = AssessmentEngine.initial()
        repeat(5) { state = AssessmentEngine.record(state, correct = false) }
        assertEquals(CefrLevel.A1, state.currentLevel)

        var up = AssessmentEngine.initial()
        repeat(12) { up = AssessmentEngine.record(up, correct = true) }
        assertEquals(CefrLevel.C1, up.currentLevel)
    }

    @Test
    fun `completes after total questions`() {
        var state = AssessmentEngine.initial()
        repeat(AssessmentEngine.TOTAL_QUESTIONS - 1) {
            state = AssessmentEngine.record(state, correct = it % 2 == 0)
            assertFalse(AssessmentEngine.isComplete(state))
        }
        state = AssessmentEngine.record(state, correct = true)
        assertTrue(AssessmentEngine.isComplete(state))
    }

    @Test
    fun `steady performance yields high confidence`() {
        // 在 B1 上下稳定：升到 B1 后一直对错交替会抖动；改为总是答对到 C1 顶层再全对。
        var state = AssessmentEngine.initial()
        repeat(12) { state = AssessmentEngine.record(state, correct = true) }
        val outcome = AssessmentEngine.result(state)
        assertEquals(CefrLevel.C1, outcome.level)
        assertTrue(outcome.confidencePercent >= 75)
        assertEquals(12, outcome.correctCount)
    }

    @Test
    fun `erratic answers yield lower confidence than steady ones`() {
        var erratic = AssessmentEngine.initial()
        repeat(12) { erratic = AssessmentEngine.record(erratic, correct = it % 2 == 0) }
        var steady = AssessmentEngine.initial()
        repeat(12) { steady = AssessmentEngine.record(steady, correct = true) }
        assertTrue(
            AssessmentEngine.result(erratic).confidencePercent <
                AssessmentEngine.result(steady).confidencePercent,
        )
    }

    @Test
    fun `saved assessment survives json round trip`() {
        var state = AssessmentEngine.initial()
        state = AssessmentEngine.record(state, correct = true)
        val saved = SavedAssessment(
            state = state,
            queue = listOf(AssessmentQuestion("vocab", "Pick one ___.", listOf("a", "b", "c"), 1, "解析")),
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
        )
        assertEquals(1, validateAssessmentQuestions(questions).size)
    }
}
