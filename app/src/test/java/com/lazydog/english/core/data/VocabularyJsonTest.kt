package com.lazydog.english.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VocabularyJsonTest {

    @Test
    fun `collocations round trip through json`() {
        val collocations = listOf("resolve an issue", "a technical issue")
        val encoded = VocabularyJson.encodeCollocations(collocations)
        assertEquals(collocations, VocabularyJson.decodeCollocations(encoded))
    }

    @Test
    fun `empty list round trips`() {
        assertEquals(emptyList<String>(), VocabularyJson.decodeCollocations(VocabularyJson.encodeCollocations(emptyList())))
    }

    @Test
    fun `malformed json decodes to empty list instead of throwing`() {
        assertTrue(VocabularyJson.decodeCollocations("not json").isEmpty())
        assertTrue(VocabularyJson.decodeCollocations("").isEmpty())
    }
}
