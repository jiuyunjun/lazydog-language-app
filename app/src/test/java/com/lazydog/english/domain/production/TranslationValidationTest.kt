package com.lazydog.english.domain.production

import com.lazydog.english.domain.practice.GrammarErrorTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationValidationTest {

    private fun task(
        promptZh: String = "他从去年冬天就一直在学日语。",
        referenceEn: String = "He has been learning Japanese since last winter.",
        hintZh: String = "用完成进行时",
        errorTag: String = GrammarErrorTag.Tense,
    ) = TranslationTask(promptZh, referenceEn, hintZh, errorTag)

    @Test
    fun `完整的题通过校验`() {
        assertNull(TranslationValidation.problem(task()))
    }

    @Test
    fun `缺中文、缺参考答案、参考答案不是英文都不合格`() {
        assertNotNull(TranslationValidation.problem(task(promptZh = " ")))
        assertNotNull(TranslationValidation.problem(task(referenceEn = "")))
        assertNotNull(TranslationValidation.problem(task(referenceEn = "他一直在学日语。")))
    }

    @Test
    fun `中文太长不合格`() {
        assertNotNull(TranslationValidation.problem(task(promptZh = "很".repeat(200))))
    }

    @Test
    fun `坏题被丢掉，标签被归一，数量截到上限`() {
        val valid = TranslationValidation.validateTasks(
            listOf(
                task(errorTag = "Tense"),
                task(referenceEn = ""),
                task(errorTag = "什么鬼"),
                task(),
            ),
            maxCount = 2,
        )
        assertEquals(2, valid.size)
        assertEquals(GrammarErrorTag.Tense, valid.first().errorTag)
        assertEquals(GrammarErrorTag.Other, valid[1].errorTag)
    }

    @Test
    fun `判定必须给改好的句子和说明`() {
        val ok = TranslationFeedback(TranslationVerdict.Minor, "He has been learning.", "时态不对。", listOf("tense"))
        assertNull(TranslationValidation.validateFeedback(ok))
        assertNotNull(TranslationValidation.validateFeedback(ok.copy(correctedEn = "")))
        assertNotNull(TranslationValidation.validateFeedback(ok.copy(noteZh = " ")))
    }

    @Test
    fun `写对了不记错题`() {
        val feedback = TranslationFeedback(TranslationVerdict.Ok, "He has been learning.", "写对了。", listOf("tense"))
        assertTrue(TranslationValidation.mistakeTags(feedback).isEmpty())
    }

    @Test
    fun `错题标签归一、去重、最多两个`() {
        val feedback = TranslationFeedback(
            verdict = TranslationVerdict.Wrong,
            correctedEn = "He has been learning Japanese.",
            noteZh = "时态和一致都有问题。",
            errorTags = listOf("Tense", "tense", "agreement", "plural"),
        )
        assertEquals(
            listOf(GrammarErrorTag.Tense, GrammarErrorTag.Agreement),
            TranslationValidation.mistakeTags(feedback),
        )
    }

    @Test
    fun `判定值不认识时按没写对处理`() {
        assertEquals(TranslationVerdict.Wrong, TranslationVerdict.from("perfect"))
        assertEquals(TranslationVerdict.Ok, TranslationVerdict.from(" OK "))
        assertEquals(TranslationVerdict.Minor, TranslationVerdict.from("minor"))
    }
}
