package com.lazydog.english.domain.vocabulary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PartOfSpeechTest {

    @Test
    fun `the closed set absorbs the shapes a model actually returns`() {
        // 同一个词性有 v. / vi / verb / VERB 好几种写法。它们都得归到一个值上，
        // 否则 (lemma, 词性) 这个身份键会把同一个词条拆成好几条。
        val verb = listOf("VERB", "v.", "v", "vi", "vt.", "verb", " Verb ")
        for (value in verb) assertEquals(value, PartOfSpeech.Verb, PartOfSpeech.parse(value))
        assertEquals(PartOfSpeech.Noun, PartOfSpeech.parse("n."))
        assertEquals(PartOfSpeech.Adjective, PartOfSpeech.parse("adj"))
        assertEquals(PartOfSpeech.Adposition, PartOfSpeech.parse("prep."))
    }

    @Test
    fun `expressions keep parsing after the rename`() {
        // 老数据里表达的词性写的是 expression，仓储层靠它把表达和单词分开。
        assertEquals(PartOfSpeech.Phrase, PartOfSpeech.parse("expression"))
        assertEquals(PartOfSpeech.Phrase, PartOfSpeech.parse("phrase"))
        assertEquals("PHRASE", normalizePos("expression"))
    }

    @Test
    fun `an unrecognized part of speech is not guessed at`() {
        // 猜一个默认词性塞进去，身份键就会指向错误的词条——宁可认不出来。
        assertNull(PartOfSpeech.parse(""))
        assertNull(PartOfSpeech.parse("动词性短语"))
        assertEquals("动词性短语", normalizePos("动词性短语"))
        assertEquals("动词性短语", posLabelZh("动词性短语"))
    }

    @Test
    fun `display uses Chinese labels, storage uses the wire value`() {
        assertEquals("VERB", normalizePos("v."))
        assertEquals("动词", posLabelZh("v."))
        assertEquals("动词", posLabelZh("VERB"))
    }

    @Test
    fun `the list handed to the model leaves out the local extension`() {
        // 表达不由生成新词产出，别让模型以为可以给 PHRASE。
        val list = PartOfSpeech.wireList
        assertEquals(false, list.contains("PHRASE"))
        assertEquals(true, list.contains("VERB"))
    }
}
