package com.lazydog.english.core.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationMetadataTest {

    private val generated = Json.encodeToString(
        LearningCardMetadata(
            input = "run", context = "", learnerLevel = "B1", topics = emptyList(),
            model = "gpt-test", promptVersion = 9, generatedAt = 1L,
        ),
    )
    private val tag = DictionarySenseTag(pack = "base", version = 1, pos = "VERB", order = 1)

    @Test
    fun `alignment alone does not count as generation info`() {
        val aligned = GenerationMetadata.withDictionarySense("", tag)
        assertFalse(GenerationMetadata.hasGenerationInfo(""))
        assertFalse(GenerationMetadata.hasGenerationInfo(aligned))
        assertTrue(GenerationMetadata.hasGenerationInfo(generated))
        assertTrue(GenerationMetadata.hasGenerationInfo(GenerationMetadata.withDictionarySense(generated, tag)))
        // 解析不了的老数据：按"有"处理，保持原来的判断。
        assertTrue(GenerationMetadata.hasGenerationInfo("not json"))
    }

    @Test
    fun `alignment round trips and keeps other keys`() {
        val raw = GenerationMetadata.withDictionarySense(generated, tag)
        assertEquals(tag, GenerationMetadata.dictionarySenseOf(raw))
        assertEquals("gpt-test", Json.parseToJsonElement(raw).jsonObject["model"]!!.jsonPrimitive.content)
        val unmatched = DictionarySenseTag("base", 1)
        val replaced = GenerationMetadata.withDictionarySense(raw, unmatched)
        assertEquals(unmatched, GenerationMetadata.dictionarySenseOf(replaced))
        assertFalse(unmatched.matched)
        assertNull(GenerationMetadata.dictionarySenseOf(generated))
    }

    @Test
    fun `replacing generation info carries alignment over`() {
        val previous = GenerationMetadata.withDictionarySense("", tag)
        val carried = GenerationMetadata.carryDictionarySense(previous, generated)
        assertEquals(tag, GenerationMetadata.dictionarySenseOf(carried))
        assertTrue(GenerationMetadata.hasGenerationInfo(carried))
        assertEquals(generated, GenerationMetadata.carryDictionarySense("", generated))
    }
}
