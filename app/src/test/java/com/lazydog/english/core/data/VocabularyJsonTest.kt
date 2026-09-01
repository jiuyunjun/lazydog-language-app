package com.lazydog.english.core.data

import com.lazydog.english.domain.generation.Collocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VocabularyJsonTest {

    @Test
    fun `collocations round trip through json`() {
        val collocations = listOf(
            Collocation("resolve an issue", "解决一个问题"),
            Collocation("a technical issue", "一个技术问题"),
        )
        val encoded = VocabularyJson.encodeCollocations(collocations)
        assertEquals(collocations, VocabularyJson.decodeCollocations(encoded))
    }

    /** 加翻译字段之前入库的行长这样，不做数据库迁移，解码时按"只有英文"处理。 */
    @Test
    fun `collocations stored as plain strings still decode`() {
        val decoded = VocabularyJson.decodeCollocations("""["curb traffic","curb inflation"]""")

        assertEquals(listOf("curb traffic", "curb inflation"), decoded.map { it.en })
        assertTrue(decoded.all { it.zh.isEmpty() })
    }

    @Test
    fun `empty list round trips`() {
        assertEquals(
            emptyList<Collocation>(),
            VocabularyJson.decodeCollocations(VocabularyJson.encodeCollocations(emptyList())),
        )
    }

    @Test
    fun `malformed json decodes to empty list instead of throwing`() {
        assertTrue(VocabularyJson.decodeCollocations("not json").isEmpty())
        assertTrue(VocabularyJson.decodeCollocations("").isEmpty())
    }
}
