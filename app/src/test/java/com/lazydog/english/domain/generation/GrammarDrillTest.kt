package com.lazydog.english.domain.generation

import com.lazydog.english.core.model.ReviewGrade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GrammarDrillValidationTest {

    private fun item(
        sentence: String = "She ___ Japanese since last winter.",
        options: List<String> = listOf("is learning", "has been learning", "learns"),
        answerIndex: Int = 1,
        explanation: String = "since + 时间点要求延续到现在。",
    ) = GrammarDrillItem(sentence, options, answerIndex, explanation)

    @Test
    fun `合格的题通过校验`() {
        assertNull(GrammarDrillValidation.problem(item()))
    }

    @Test
    fun `下划线长短不一律归一`() {
        val normalized = GrammarDrillValidation.normalize(item(sentence = "She _____ Japanese."))!!
        assertTrue(normalized.sentenceEn.contains(GrammarDrillValidation.BLANK))
        assertNull(GrammarDrillValidation.problem(normalized))
    }

    @Test
    fun `没挖空或挖了两个空都不合格`() {
        assertNotNull(GrammarDrillValidation.problem(item(sentence = "She learns Japanese.")))
        assertNotNull(GrammarDrillValidation.problem(item(sentence = "She ___ Japanese ___ winter.")))
    }

    @Test
    fun `选项数量、重复、越界、整句作选项都不合格`() {
        assertNotNull(GrammarDrillValidation.problem(item(options = listOf("a", "b"))))
        assertNotNull(GrammarDrillValidation.problem(item(options = listOf("learns", "Learns", "learned"))))
        assertNotNull(GrammarDrillValidation.problem(item(answerIndex = 5)))
        assertNotNull(
            GrammarDrillValidation.problem(
                item(options = listOf("has been learning Japanese since last winter for a while", "b", "c")),
            ),
        )
    }

    @Test
    fun `缺解析不合格`() {
        assertNotNull(GrammarDrillValidation.problem(item(explanation = "  ")))
    }

    @Test
    fun `坏题被丢掉，好题保留`() {
        val valid = GrammarDrillValidation.validate(
            listOf(item(), item(sentence = "没有空的句子"), item(sentence = "He ____ here since May.")),
            maxCount = 4,
        )
        assertEquals(2, valid.size)
    }

    @Test
    fun `好题不够两道时整批判失败`() {
        val valid = GrammarDrillValidation.validate(listOf(item(), item(options = listOf("a", "b"))), maxCount = 4)
        assertTrue(valid.isEmpty())
    }

    @Test
    fun `填空后能拼出完整句子`() {
        assertEquals(
            "She has been learning Japanese since last winter.",
            item().filledWith("has been learning"),
        )
    }
}

class GrammarDrillGradingTest {

    @Test
    fun `全对给很熟，全错给忘了`() {
        assertEquals(ReviewGrade.Easy, GrammarDrillGrading.gradeFor(4, 4))
        assertEquals(ReviewGrade.Forgot, GrammarDrillGrading.gradeFor(0, 4))
    }

    @Test
    fun `错一道降一档，错一半算有点印象`() {
        assertEquals(ReviewGrade.Good, GrammarDrillGrading.gradeFor(3, 4))
        assertEquals(ReviewGrade.Hard, GrammarDrillGrading.gradeFor(2, 4))
    }

    @Test
    fun `没有题时不当成掌握`() {
        assertEquals(ReviewGrade.Forgot, GrammarDrillGrading.gradeFor(0, 0))
    }
}
