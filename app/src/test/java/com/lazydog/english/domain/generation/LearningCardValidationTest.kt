package com.lazydog.english.domain.generation

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LearningCardValidationTest {
    private val card = WordExplanation(
        term = "received", lemma = "receive", ipa = "/rɪˈsiːv/", meaningZh = "收到",
        usageNoteZh = "", exampleEn = "I received a letter.", exampleZh = "我收到了一封信。", pos = "VERB",
    )

    @Test fun completeInflectedCardCanBeSaved() { assertNull(validateWordCard(card)) }
    @Test fun shortLookupWithoutExamplesCannotBecomeLearningCard() {
        assertNotNull(validateWordCard(card.copy(exampleEn = "")))
        assertNotNull(validateWordCard(card.copy(exampleZh = "")))
    }
    @Test fun invalidIdentityOrMeaningCannotBeSaved() {
        assertNotNull(validateWordCard(card.copy(pos = "unknown")))
        assertNotNull(validateWordCard(card.copy(term = "", lemma = "")))
        assertNotNull(validateWordCard(card.copy(meaningZh = "")))
    }
}
