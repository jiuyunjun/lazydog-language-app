package com.lazydog.english.domain.assessment

import org.junit.Assert.assertEquals
import org.junit.Test

class CorrectionGradingTest {

    private val item = CorrectionItem(
        incorrectSentence = "She go to school every day.",
        referenceCorrection = "She goes to school every day.",
        explanationZh = "第三人称单数要加 s。",
    )

    @Test
    fun `exact match to reference is fully correct`() {
        assertEquals(AnswerOutcome.Correct, CorrectionGrading.grade(item, "She goes to school every day."))
    }

    @Test
    fun `case and punctuation differences still count as correct`() {
        assertEquals(AnswerOutcome.Correct, CorrectionGrading.grade(item, "she goes to school every day"))
    }

    @Test
    fun `leaving the sentence unchanged is wrong, not partial`() {
        assertEquals(AnswerOutcome.Wrong, CorrectionGrading.grade(item, "She go to school every day."))
    }

    @Test
    fun `blank answer is wrong`() {
        assertEquals(AnswerOutcome.Wrong, CorrectionGrading.grade(item, ""))
    }

    @Test
    fun `close but imperfect attempt earns partial credit`() {
        // 改对了目标错误（go -> goes），但引入了一个新问题（丢了 every）。
        assertEquals(AnswerOutcome.Partial, CorrectionGrading.grade(item, "She goes to school day."))
    }

    @Test
    fun `unrelated rewrite is wrong`() {
        assertEquals(AnswerOutcome.Wrong, CorrectionGrading.grade(item, "I like pizza."))
    }
}
