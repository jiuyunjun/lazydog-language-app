package com.lazydog.english.domain.assessment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpressionValidationTest {

    private fun rubric(vararg scores: Int): ExpressionRubric =
        ExpressionRubric(ExpressionDimension.all.zip(scores.toList()).map { (d, s) -> DimensionScore(d, s) })

    @Test
    fun `complete rubric with valid scores passes`() {
        assertNull(ExpressionValidation.validate(rubric(2, 3, 2, 3, 3)))
    }

    @Test
    fun `missing a dimension fails`() {
        val incomplete = ExpressionRubric(listOf(DimensionScore(ExpressionDimension.TaskCompletion, 3)))
        assertNotNull(ExpressionValidation.validate(incomplete))
    }

    @Test
    fun `score out of 0-4 range fails`() {
        assertNotNull(ExpressionValidation.validate(rubric(5, 3, 2, 3, 3)))
        assertNotNull(ExpressionValidation.validate(rubric(-1, 3, 2, 3, 3)))
    }

    @Test
    fun `close two-pass scores do not need review`() {
        val assessment = ExpressionValidation.assess(rubric(2, 2, 2, 2, 2), rubric(3, 2, 2, 2, 3))
        assertFalse(assessment.needsReview)
    }

    @Test
    fun `large total discrepancy needs review`() {
        val assessment = ExpressionValidation.assess(rubric(0, 0, 0, 0, 0), rubric(4, 4, 4, 4, 4))
        assertTrue(assessment.needsReview)
    }

    @Test
    fun `single dimension diverging by more than one step needs review`() {
        // 总分只差 3（刚好没超过总分阈值），但语法这一项差了 3 档。
        val assessment = ExpressionValidation.assess(rubric(2, 2, 0, 2, 2), rubric(2, 2, 3, 2, 0))
        assertTrue(assessment.needsReview)
    }

    @Test
    fun `display uses the rubric-based second pass`() {
        val assessment = ExpressionValidation.assess(rubric(1, 1, 1, 1, 1), rubric(3, 3, 3, 3, 3))
        assertEquals(15, assessment.display.total)
    }
}
