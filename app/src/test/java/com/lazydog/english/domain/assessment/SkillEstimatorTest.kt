package com.lazydog.english.domain.assessment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillEstimatorTest {

    private fun item(skill: String, level: Double, outcome: AnswerOutcome) =
        AnsweredItem(skill, level, outcome)

    private fun state(vararg items: AnsweredItem, score: Double = 3.0) =
        AssessmentState(score = score, answered = items.toList())

    @Test
    fun `没有样本的技能返回 null`() {
        assertNull(SkillEstimator.skillScore(emptyList(), overall = 3.0, AssessmentSkill.Grammar))
    }

    @Test
    fun `词汇答对语法答错时两项分开`() {
        val answered = listOf(
            item(AssessmentSkill.Vocab, 3.0, AnswerOutcome.Correct),
            item(AssessmentSkill.Vocab, 3.5, AnswerOutcome.Correct),
            item(AssessmentSkill.Grammar, 3.0, AnswerOutcome.Wrong),
            item(AssessmentSkill.Grammar, 2.0, AnswerOutcome.Wrong),
        )
        val vocab = SkillEstimator.skillScore(answered, 3.0, AssessmentSkill.Vocab)!!
        val grammar = SkillEstimator.skillScore(answered, 3.0, AssessmentSkill.Grammar)!!
        assertTrue("词汇应高于总等级，实际 $vocab", vocab > 3.0)
        assertTrue("语法应低于总等级，实际 $grammar", grammar < 3.0)
        assertTrue("偏科差距应该拉开，实际 ${vocab - grammar}", vocab - grammar > 0.8)
    }

    @Test
    fun `样本越少越向总等级收缩`() {
        val one = listOf(item(AssessmentSkill.Grammar, 1.0, AnswerOutcome.Wrong))
        val four = List(4) { item(AssessmentSkill.Grammar, 1.0, AnswerOutcome.Wrong) }
        val withOne = SkillEstimator.skillScore(one, 3.0, AssessmentSkill.Grammar)!!
        val withFour = SkillEstimator.skillScore(four, 3.0, AssessmentSkill.Grammar)!!
        assertTrue("四题的估计应该比一题更远离总等级", withFour < withOne)
    }

    @Test
    fun `纠错短答并进语法`() {
        val answered = listOf(
            item(AssessmentSkill.Correction, 2.0, AnswerOutcome.Wrong),
            item(AssessmentSkill.Correction, 2.0, AnswerOutcome.Wrong),
        )
        val grammar = SkillEstimator.estimate(state(*answered.toTypedArray()), null, null).grammar
        assertNotNull(grammar)
        assertTrue("纠错全错应该把语法估计压到总等级以下，实际 $grammar", grammar!! < 3.0)
    }

    @Test
    fun `部分正确既不加也不减`() {
        val partial = listOf(item(AssessmentSkill.Correction, 3.0, AnswerOutcome.Partial))
        assertEquals(3.0, SkillEstimator.skillScore(partial, 3.0, AssessmentSkill.Correction)!!, 0.001)
    }

    @Test
    fun `深度阅读正确率高时上调阅读估计`() {
        val answered = arrayOf(item(AssessmentSkill.Reading, 3.0, AnswerOutcome.Correct))
        val without = SkillEstimator.estimate(state(*answered), null, null).reading!!
        val with = SkillEstimator.estimate(state(*answered), DeepReadingOutcome(pct = 90, correctWeight = 4), null).reading!!
        assertEquals(0.3, with - without, 0.001)
    }

    @Test
    fun `只做了深度阅读时阅读估计从总等级起算`() {
        val reading = SkillEstimator.estimate(
            state(item(AssessmentSkill.Vocab, 3.0, AnswerOutcome.Correct)),
            DeepReadingOutcome(pct = 20, correctWeight = 1),
            null,
        ).reading
        assertEquals(2.7, reading!!, 0.001)
    }

    @Test
    fun `开放表达满分映射到 C1，一半分映射到 A2 上下`() {
        assertEquals(5.0, SkillEstimator.expressionScore(20), 0.001)
        assertEquals(2.5, SkillEstimator.expressionScore(10), 0.001)
        assertEquals(0.0, SkillEstimator.expressionScore(0), 0.001)
    }

    @Test
    fun `没写作文时表达为空`() {
        assertNull(SkillEstimator.estimate(state(), null, null).expression)
    }

    @Test
    fun `画像摘要只列测出来的项`() {
        assertNull(SkillLevels().summaryText())
        assertEquals("词汇 B1 · 语法 A2", SkillLevels(vocab = 3.0, grammar = 2.0).summaryText())
    }
}
