package com.lazydog.english.domain.assessment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssessmentReportTest {

    private fun state(vararg items: Triple<String, Double, Boolean>): AssessmentState =
        AssessmentState(score = 3.0, answered = items.map { AnsweredItem(it.first, it.second, it.third) })

    @Test
    fun `missing modules are reported as thin samples, not zero`() {
        val s = state(
            Triple(AssessmentSkill.Vocab, 3.0, true),
            Triple(AssessmentSkill.Grammar, 3.0, true),
            Triple(AssessmentSkill.Reading, 3.0, true),
            Triple(AssessmentSkill.Pragmatics, 3.0, true),
        )
        val outcome = AssessmentReport.build(s, deepReading = null, expression = null)

        val expressionRow = outcome.profile.first { it.name.startsWith("开放表达") }
        assertEquals("样本不足", expressionRow.label)
        assertTrue(outcome.watchNoteZh.contains("开放表达"))
    }

    @Test
    fun `deep reading blends with ladder reading accuracy when both present`() {
        val s = state(
            Triple(AssessmentSkill.Vocab, 3.0, true),
            Triple(AssessmentSkill.Grammar, 3.0, true),
            Triple(AssessmentSkill.Reading, 3.0, true), // 100% 梯度阅读正确率
            Triple(AssessmentSkill.Pragmatics, 3.0, true),
        )
        val deepReading = DeepReadingOutcome(pct = 50, correctWeight = 5)
        val outcome = AssessmentReport.build(s, deepReading, expression = null)

        val readingRow = outcome.profile.first { it.name.startsWith("阅读理解") }
        // 0.7*50 + 0.3*100 = 65
        assertEquals(65, readingRow.pct)
    }

    @Test
    fun `large reading-over-expression gap produces a gap note`() {
        val s = state(
            Triple(AssessmentSkill.Vocab, 3.0, true),
            Triple(AssessmentSkill.Grammar, 3.0, true),
            Triple(AssessmentSkill.Reading, 3.0, true),
            Triple(AssessmentSkill.Pragmatics, 3.0, true),
        )
        val deepReading = DeepReadingOutcome(pct = 100, correctWeight = 10)
        val weakExpression = ExpressionAssessment(
            firstPass = ExpressionRubric(ExpressionDimension.all.map { DimensionScore(it, 0) }),
            secondPass = ExpressionRubric(ExpressionDimension.all.map { DimensionScore(it, 0) }),
            needsReview = false,
        )
        val outcome = AssessmentReport.build(s, deepReading, weakExpression)

        assertTrue(outcome.gapNoteZh != null)
    }

    @Test
    fun `small gap produces no gap note`() {
        val s = state(
            Triple(AssessmentSkill.Vocab, 3.0, true),
            Triple(AssessmentSkill.Grammar, 3.0, true),
            Triple(AssessmentSkill.Reading, 3.0, true),
            Triple(AssessmentSkill.Pragmatics, 3.0, true),
        )
        val deepReading = DeepReadingOutcome(pct = 70, correctWeight = 7)
        val evenExpression = ExpressionAssessment(
            firstPass = ExpressionRubric(ExpressionDimension.all.map { DimensionScore(it, 3) }),
            secondPass = ExpressionRubric(ExpressionDimension.all.map { DimensionScore(it, 3) }),
            needsReview = false,
        )
        val outcome = AssessmentReport.build(s, deepReading, evenExpression)

        assertNull(outcome.gapNoteZh)
    }

    @Test
    fun `expression review flag propagates to the outcome`() {
        val s = state(Triple(AssessmentSkill.Vocab, 3.0, true))
        val flagged = ExpressionAssessment(
            firstPass = ExpressionRubric(ExpressionDimension.all.map { DimensionScore(it, 0) }),
            secondPass = ExpressionRubric(ExpressionDimension.all.map { DimensionScore(it, 4) }),
            needsReview = true,
        )
        val outcome = AssessmentReport.build(s, deepReading = null, expression = flagged)
        assertTrue(outcome.expressionNeedsReview)
    }
}
