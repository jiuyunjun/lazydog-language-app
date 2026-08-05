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
        state = AssessmentEngine.record(state, AssessmentSkill.Vocab, correct = true)
        assertEquals(CefrLevel.A2, state.currentLevel)
        assertEquals(1, state.streak)
        state = AssessmentEngine.record(state, AssessmentSkill.Vocab, correct = true)
        assertEquals(CefrLevel.B1, state.currentLevel)
        assertEquals(0, state.streak)
    }

    @Test
    fun `wrong answer levels down immediately`() {
        var state = AssessmentEngine.initial()
        state = AssessmentEngine.record(state, AssessmentSkill.Grammar, correct = false)
        assertEquals(CefrLevel.A1, state.currentLevel)
    }

    @Test
    fun `levels clamp at both ends`() {
        var state = AssessmentEngine.initial()
        repeat(5) { state = AssessmentEngine.record(state, AssessmentSkill.Vocab, correct = false) }
        assertEquals(CefrLevel.A1, state.currentLevel)

        var up = AssessmentEngine.initial()
        repeat(12) { up = AssessmentEngine.record(up, AssessmentSkill.Vocab, correct = true) }
        assertEquals(CefrLevel.C1, up.currentLevel)
    }

    @Test
    fun `does not complete before MIN_QUESTIONS even if very consistent`() {
        var state = AssessmentEngine.initial()
        // 前 7 题全对（会不断升级），还没到 MIN_QUESTIONS，不该提前结束。
        repeat(AssessmentEngine.MIN_QUESTIONS - 1) {
            state = AssessmentEngine.record(state, AssessmentSkill.Vocab, correct = true)
            assertFalse(AssessmentEngine.isComplete(state))
        }
    }

    @Test
    fun `stops early once past MIN_QUESTIONS and recent answers are consistent`() {
        var state = AssessmentEngine.initial()
        // 冲到顶再稳定作答：一旦过了 MIN_QUESTIONS，最近几题的一致度应该已经很高，可以提前结束。
        while (!AssessmentEngine.isComplete(state) && state.answered.size < 20) {
            state = AssessmentEngine.record(state, AssessmentSkill.Vocab, correct = true)
        }
        assertTrue(AssessmentEngine.isComplete(state))
        assertTrue(state.answered.size in AssessmentEngine.MIN_QUESTIONS..AssessmentEngine.MAX_QUESTIONS)
        assertTrue("expected early stop before MAX_QUESTIONS", state.answered.size < AssessmentEngine.MAX_QUESTIONS)
    }

    @Test
    fun `always completes by MAX_QUESTIONS even with erratic answers`() {
        var state = AssessmentEngine.initial()
        repeat(AssessmentEngine.MAX_QUESTIONS) {
            state = AssessmentEngine.record(state, AssessmentSkill.Grammar, correct = it % 2 == 0)
        }
        assertTrue(AssessmentEngine.isComplete(state))
        assertEquals(AssessmentEngine.MAX_QUESTIONS, state.answered.size)
    }

    @Test
    fun `steady performance yields high confidence`() {
        // 在 B1 上下稳定：升到 B1 后一直对错交替会抖动；改为总是答对到 C1 顶层再全对。
        var state = AssessmentEngine.initial()
        repeat(12) { state = AssessmentEngine.record(state, AssessmentSkill.Vocab, correct = true) }
        val outcome = AssessmentEngine.result(state, expression = null)
        assertEquals(CefrLevel.C1, outcome.level)
        assertTrue(outcome.confidencePercent >= 75)
        assertEquals(12, outcome.correctCount)
    }

    @Test
    fun `erratic answers yield lower confidence than steady ones`() {
        var erratic = AssessmentEngine.initial()
        repeat(12) { erratic = AssessmentEngine.record(erratic, AssessmentSkill.Vocab, correct = it % 2 == 0) }
        var steady = AssessmentEngine.initial()
        repeat(12) { steady = AssessmentEngine.record(steady, AssessmentSkill.Vocab, correct = true) }
        assertTrue(
            AssessmentEngine.result(erratic, null).confidencePercent <
                AssessmentEngine.result(steady, null).confidencePercent,
        )
    }

    @Test
    fun `profile rows report thin sample as unmeasured`() {
        var state = AssessmentEngine.initial()
        // 只答语法题，词汇 / 阅读没有样本。
        repeat(AssessmentEngine.MIN_QUESTIONS) {
            state = AssessmentEngine.record(state, AssessmentSkill.Grammar, correct = true)
        }
        val outcome = AssessmentEngine.result(state, expression = null)
        val vocabRow = outcome.profile.first { it.name == "词汇广度" }
        val grammarRow = outcome.profile.first { it.name.startsWith("语法") }
        val expressionRow = outcome.profile.first { it.name == "表达" }
        assertEquals("样本不足", vocabRow.label)
        assertEquals(0, vocabRow.pct)
        assertTrue(grammarRow.pct > 0)
        assertEquals("样本不足", expressionRow.label)
        assertTrue(outcome.watchNoteZh.contains("词汇广度"))
    }

    @Test
    fun `expression feedback feeds the expression profile row`() {
        var state = AssessmentEngine.initial()
        repeat(AssessmentEngine.MIN_QUESTIONS) {
            state = AssessmentEngine.record(state, AssessmentSkill.Vocab, correct = true)
        }
        val good = AssessmentEngine.result(
            state,
            ExpressionFeedback("suggestion", "没有明显问题", "写得不错", ExpressionRating.Good),
        )
        assertEquals("基本达标", good.profile.first { it.name == "表达" }.label)

        val needsWork = AssessmentEngine.result(
            state,
            ExpressionFeedback("suggestion", "时态不对", "再看看时态", ExpressionRating.NeedsWork),
        )
        assertEquals("还需要多练", needsWork.profile.first { it.name == "表达" }.label)
    }

    @Test
    fun `vocab range grows with level`() {
        assertEquals("500～1000", AssessmentEngine.vocabRangeText(CefrLevel.A1))
        assertEquals("5000～8000", AssessmentEngine.vocabRangeText(CefrLevel.C1))
    }

    @Test
    fun `saved assessment survives json round trip`() {
        var state = AssessmentEngine.initial()
        state = AssessmentEngine.record(state, AssessmentSkill.Vocab, correct = true)
        val saved = SavedAssessment(
            state = state,
            queue = listOf(AssessmentQuestion("vocab", "Pick one ___.", listOf("a", "b", "c"), 1, "解析")),
            expressionTaskZh = "用两三句英文写写你今天做了什么。",
            expressionDone = false,
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
