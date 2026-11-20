package com.lazydog.english.core.data

import com.lazydog.english.domain.vocabulary.PartOfSpeech
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AssetDictionaryTest {

    private fun row(pos: String, order: Int, meaning: String) = AssetDictionary.SenseRow(pos, order, meaning)

    @Test
    fun `keeps dictionary order and parses pos`() {
        val entry = AssetDictionary.assembleEntry(
            lemma = "record",
            ipa = "/ˈrekərd/",
            ipaSource = "cmudict",
            senses = listOf(row("NOUN", 0, "记录"), row("NOUN", 1, "唱片"), row("VERB", 0, "记载")),
            irregularForms = emptyList(),
        )!!
        assertEquals(listOf("记录", "唱片", "记载"), entry.senses.map { it.meaningZh })
        assertEquals(listOf(PartOfSpeech.Verb), entry.sensesOf(PartOfSpeech.Verb).map { it.pos })
        assertEquals("动词 记载", entry.sensesOf(PartOfSpeech.Verb).single().labelZh)
    }

    @Test
    fun `drops unknown pos and blank meanings`() {
        val entry = AssetDictionary.assembleEntry(
            "go", "/ɡoʊ/", "cmudict",
            listOf(row("WHATEVER", 0, "去"), row("VERB", 0, " "), row("VERB", 1, "走")),
            listOf("went", "gone", "Went"),
        )!!
        assertEquals(listOf("走"), entry.senses.map { it.meaningZh })
        assertEquals(listOf("went", "gone"), entry.irregularForms)
    }

    @Test
    fun `entry without usable senses is absent`() {
        assertNull(AssetDictionary.assembleEntry("x", "", "", listOf(row("ABBR", 0, "某")), emptyList()))
    }
}
