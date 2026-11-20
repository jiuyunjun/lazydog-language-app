package com.lazydog.english.domain.vocabulary

import com.lazydog.english.domain.vocabulary.DictionaryAlignment.Plan
import org.junit.Assert.assertEquals
import org.junit.Test

class DictionaryAlignmentTest {

    private val run = DictionaryEntry(
        lemma = "run",
        ipa = "/rʌn/",
        ipaSource = "cmudict",
        senses = listOf(
            DictionarySense(PartOfSpeech.Noun, 0, "跑"),
            DictionarySense(PartOfSpeech.Verb, 0, "跑"),
            DictionarySense(PartOfSpeech.Verb, 1, "经营"),
            DictionarySense(PartOfSpeech.Adjective, 0, "熔化的"),
        ),
        irregularForms = listOf("ran"),
    )

    @Test
    fun `plan follows pos`() {
        assertEquals(Plan.Unmatched, DictionaryAlignment.plan("VERB", null))
        assertEquals(Plan.Direct(run.senses[3]), DictionaryAlignment.plan("adj.", run))
        assertEquals(Plan.NeedsChoice(run.senses.subList(1, 3)), DictionaryAlignment.plan("v.", run))
        // 卡标了词性、词典里没有：不跨词性找。
        assertEquals(Plan.Unmatched, DictionaryAlignment.plan("ADV", run))
        // 没标词性：全部义项都是候选。
        assertEquals(Plan.NeedsChoice(run.senses), DictionaryAlignment.plan("", run))
    }

    @Test
    fun `align replaces meaning and us ipa but keeps existing forms`() {
        val aligned = DictionaryAlignment.align("/rʌn/ (uk)", listOf("ran", "run"), run.senses[2], run)
        assertEquals(
            DictionaryAlignment.AlignedFields("VERB", "经营", 1, "/rʌn/", listOf("ran", "run")),
            aligned,
        )
    }

    @Test
    fun `summary groups by pos in dictionary order and truncates`() {
        assertEquals(listOf("名词 跑", "动词 跑…", "形容词 熔化的"), run.summaryLines(perPos = 1))
        assertEquals("动词 跑；经营", run.summaryLines()[1])
    }

    @Test
    fun `ecdict ipa only fills a blank`() {
        val uk = run.copy(ipa = "/rʌn/", ipaSource = "ecdict")
        assertEquals("/rən/", DictionaryAlignment.align("/rən/", emptyList(), run.senses[1], uk).ipa)
        assertEquals("/rʌn/", DictionaryAlignment.align("", emptyList(), run.senses[1], uk).ipa)
        assertEquals(listOf("ran"), DictionaryAlignment.align("", emptyList(), run.senses[1], uk).forms)
    }
}
