package com.lazydog.english.domain.generation

import com.lazydog.english.domain.vocabulary.DictionarySense
import com.lazydog.english.domain.vocabulary.PartOfSpeech
import org.junit.Assert.assertEquals
import org.junit.Test

class SenseChoiceTest {

    private val run = SenseChoiceItem(
        key = "7",
        term = "run",
        options = listOf(
            DictionarySense(PartOfSpeech.Verb, 0, "跑"),
            DictionarySense(PartOfSpeech.Verb, 1, "经营"),
        ),
    )
    private val bank = SenseChoiceItem(
        key = "9",
        term = "bank",
        options = listOf(DictionarySense(PartOfSpeech.Noun, 0, "银行")),
    )

    @Test
    fun `sense choices keep valid answers and explicit none`() {
        val result = validateSenseChoices(
            SenseChoiceRequest(listOf(run, bank)),
            listOf("7" to 1, "7" to 0, "9" to null, "42" to 0),
        )
        assertEquals(mapOf("7" to 1, "9" to null), result)
    }

    @Test
    fun `out of range index is dropped not treated as none`() {
        val result = validateSenseChoices(SenseChoiceRequest(listOf(bank)), listOf("9" to 3))
        assertEquals(emptyMap<String, Int?>(), result)
    }

    @Test
    fun `new word picks are limited to candidates and count`() {
        val request = NewWordPickRequest(
            count = 1,
            learnerLevel = "B1",
            topics = emptyList(),
            candidates = listOf(run.copy(key = "run"), bank.copy(key = "bank")),
        )
        val picks = validateNewWordPicks(request, listOf("walk" to 0, "RUN" to 5, "run" to 1, "bank" to 0))
        assertEquals(listOf(NewWordPick("run", DictionarySense(PartOfSpeech.Verb, 1, "经营"))), picks)
    }
}
