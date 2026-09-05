package com.lazydog.english.core.data

import com.lazydog.english.domain.generation.GenerationResult
import com.lazydog.english.domain.generation.MemoryAssistance
import com.lazydog.english.domain.generation.MemoryType
import java.time.Instant
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class MemoryHintStorageTest {
    private val hint = MemoryAssistance(
        term = "unhappy",
        coreMeaningZh = "不开心",
        primaryType = MemoryType.Morphology,
        memoryHookZh = "un- 表否定；happy 开心，前面加 un- 就变成不开心。",
        morphologyZh = "un + happy = 不 + 开心",
        recallQuestionZh = "在开心这个词前加哪一段，就变成不开心？",
    )
    private val result = GenerationResult.Success(hint, "test-model", 2, listOf("无效音节已移除"))

    @Test
    fun `draft storage preserves the selected hint and its generation metadata`() {
        val at = Instant.parse("2026-09-06T00:00:00Z")
        val entity = MemoryHintRepository.toEntity(result, 42L, "unhappy", at)
        assertEquals(42L, entity.itemId)
        assertEquals("unhappy", entity.term)
        assertEquals(hint, Json.decodeFromString(MemoryAssistance.serializer(), entity.payloadJson))
        assertEquals("test-model", entity.model)
        assertEquals(2, entity.promptVersion)
        assertEquals(1, entity.schemaVersion)
        assertEquals(at.toEpochMilli(), entity.createdAt)
        assertEquals("无效音节已移除", entity.droppedNotes)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `draft cannot be saved under another word`() {
        MemoryHintRepository.toEntity(result, 43L, "borrow", Instant.EPOCH)
    }
}
