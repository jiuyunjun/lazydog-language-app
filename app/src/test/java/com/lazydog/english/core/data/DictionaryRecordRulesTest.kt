package com.lazydog.english.core.data

import com.lazydog.english.core.database.KnowledgeItemEntity
import com.lazydog.english.core.database.VocabularyDetailEntity
import com.lazydog.english.core.database.VocabularyRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DictionaryRecordRulesTest {

    private fun record(id: Long, term: String, metadata: String, chunks: String = "[]") = VocabularyRecord(
        item = KnowledgeItemEntity(
            id = id, type = "Vocabulary", stage = "Learning", stability = 1.0, difficulty = 5.0,
            reviewCount = 0, lapseCount = 0, lastReviewedAt = null, nextReviewAt = null,
            createdAt = 0L, updatedAt = 0L, generationMetadataJson = metadata,
        ),
        detail = VocabularyDetailEntity(
            itemId = id, term = term, ipa = "", meaningZh = "经营", exampleEn = "", exampleZh = "",
            pos = "VERB", chunksJson = chunks,
        ),
    )

    private fun tagged(order: Int?) =
        GenerationMetadata.withDictionarySense("", DictionarySenseTag("base", 1, "VERB", order))

    @Test
    fun `aligned quick card is still a quick card`() {
        assertTrue(record(1, "run", tagged(15)).isQuickCard())
        assertFalse(record(1, "run", """{"model":"gpt"}""").isQuickCard())
    }

    @Test
    fun `counts other cards on the same dictionary sense`() {
        val a = record(1, "run", tagged(15))
        val b = record(2, "Run", tagged(15), chunks = """["r","un"]""")
        val c = record(3, "run", tagged(0))
        val unmatched = record(4, "run", tagged(null))
        val all = listOf(a, b, c, unmatched)
        assertEquals(1, all.countSameDictionarySense(a))
        assertEquals(0, all.countSameDictionarySense(c))
        assertEquals(0, all.countSameDictionarySense(unmatched))
    }
}
