package com.lazydog.english.domain.grammar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GrammarTermsTest {

    @Test
    fun `formula terms are replaced with chinese`() {
        assertEquals("have/has + 过去分词", grammarPatternZh("have/has + past participle"))
        assertEquals("will + 动词原形", grammarPatternZh("will + base verb"))
    }

    @Test
    fun `longer terms win over the words inside them`() {
        // "past participle" 不能被 "past simple" 里的 past 或末尾的 participle 啃掉半截。
        assertEquals(
            "if + 一般过去时, would + 动词原形",
            grammarPatternZh("if + past simple, would + base verb"),
        )
    }

    @Test
    fun `plain formulas get no echo line`() {
        // 公式本来就是大白话时再摆一行一样的东西只是噪音。
        assertEquals("", grammarPatternZh("be going to + X"))
        assertEquals("", grammarPatternZh("used to"))
    }

    @Test
    fun `hyphen and case do not hide a term`() {
        assertEquals("have/has + 过去分词", grammarPatternZh("have/has + Past-Participle"))
    }

    @Test
    fun `terms are collected from the formula and the explanation`() {
        val terms = grammarTermsIn(
            "have/has + past participle",
            "和 past simple 的区别在于是否强调对现在的影响。",
        )
        val zh = terms.map { it.zh }
        assertTrue(zh.contains("过去分词"))
        assertTrue(zh.contains("一般过去时"))
        // 同一个术语在公式和讲解里各出现一次，只解释一次。
        assertEquals(zh.size, zh.distinct().size)
    }

    @Test
    fun `every term carries an example-shaped note`() {
        val terms = grammarTermsIn(
            "past participle base verb gerund modal verb relative clause comparative",
        )
        assertTrue(terms.isNotEmpty())
        assertTrue(terms.all { it.zh.isNotBlank() && it.noteZh.isNotBlank() })
    }
}
