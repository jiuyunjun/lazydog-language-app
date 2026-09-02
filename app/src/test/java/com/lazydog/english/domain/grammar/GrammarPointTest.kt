package com.lazydog.english.domain.grammar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GrammarPointTest {

    @Test
    fun `the same point written differently gets the same key`() {
        // 这是原来那套精确串匹配完全挡不住的：三种写法各存一条，各排一遍复习。
        val forms = listOf(
            "have/has + past participle",
            "has/have + past participle",
            "have / has + p.p.",
            "Have/has + Past Participle",
            "present perfect",
            "现在完成时",
        )
        val keys = forms.map { grammarPointKey(GrammarCategory.Present, it) }.distinct()
        assertEquals(keys.toString(), 1, keys.size)
    }

    @Test
    fun `base verb spellings collapse`() {
        val key = grammarPatternKey("be going to + base verb")
        assertEquals(key, grammarPatternKey("be going to + verb"))
        assertEquals(key, grammarPatternKey("be going to + base form"))
        assertEquals(key, grammarPatternKey("be going to + infinitive without to"))
    }

    @Test
    fun `two different points are not merged`() {
        // 一般将来时是第一条件句的子集，正因为如此才只做等值判断、不做子集判断。
        val future = grammarPointKey(GrammarCategory.Future, "will + base verb")
        val firstConditional = grammarPointKey(
            GrammarCategory.Conditional,
            "if + present simple, will + base verb",
        )
        val secondConditional = grammarPointKey(
            GrammarCategory.Conditional,
            "if + past simple, would + base verb",
        )
        assertNotEquals(future, firstConditional)
        assertNotEquals(firstConditional, secondConditional)
    }

    @Test
    fun `the category separates formulas that normalize the same`() {
        // was/were + verb-ing 和 am/is/are + verb-ing 归一化后一模一样，
        // 靠大类才分得开——这就是身份键要带大类的原因。
        val past = grammarPointKey(GrammarCategory.Past, "was/were + verb-ing")
        val present = grammarPointKey(GrammarCategory.Present, "am/is/are + verb-ing")
        assertNotEquals(past, present)
        assertEquals(past, grammarPointKey(GrammarCategory.Past, "过去进行时"))
        assertEquals(present, grammarPointKey(GrammarCategory.Present, "present continuous"))
    }

    @Test
    fun `word order and filler words do not matter`() {
        assertEquals(
            grammarPatternKey("the present perfect tense"),
            grammarPatternKey("present perfect"),
        )
        assertEquals(
            grammarPatternKey("be + past participle"),
            grammarPatternKey("passive voice"),
        )
    }

    @Test
    fun `an empty pattern has no key`() {
        assertEquals("", grammarPatternKey("   "))
        assertEquals("", grammarPointKey(GrammarCategory.Present, ""))
    }

    @Test
    fun `categories absorb the shapes a model returns`() {
        assertEquals(GrammarCategory.Present, GrammarCategory.parse("present"))
        assertEquals(GrammarCategory.Present, GrammarCategory.parse("PRESENT_TENSE"))
        assertEquals(GrammarCategory.Modality, GrammarCategory.parse("modal verbs"))
        assertEquals(GrammarCategory.Conditional, GrammarCategory.parse("conditionals"))
        assertEquals(GrammarCategory.ReportedSpeech, GrammarCategory.parse("reported speech"))
        assertNull(GrammarCategory.parse("时态"))
        assertNull(GrammarCategory.parse(""))
    }
}
