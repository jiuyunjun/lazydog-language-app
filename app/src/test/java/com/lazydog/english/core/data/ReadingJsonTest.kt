package com.lazydog.english.core.data

import com.lazydog.english.domain.generation.ReadingQuestion
import com.lazydog.english.domain.generation.ReadingTargetGrammar
import com.lazydog.english.domain.generation.ReadingTargetWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingJsonTest {

    @Test
    fun `words round trip`() {
        val words = listOf(
            ReadingTargetWord("curb", "控制", "to curb traffic", "review"),
            ReadingTargetWord("plausible", "看似合理的", "sounded plausible", "new"),
        )
        assertEquals(words, ReadingJson.decodeWords(ReadingJson.encodeWords(words)))
    }

    @Test
    fun `grammar and questions round trip`() {
        val grammar = listOf(ReadingTargetGrammar("过去完成时", "had left", "过去的过去"))
        val questions = listOf(ReadingQuestion("问题", listOf("A", "B"), 1, "解析"))
        assertEquals(grammar, ReadingJson.decodeGrammar(ReadingJson.encodeGrammar(grammar)))
        assertEquals(questions, ReadingJson.decodeQuestions(ReadingJson.encodeQuestions(questions)))
    }

    @Test
    fun `corrupted json degrades to empty list`() {
        assertTrue(ReadingJson.decodeWords("not json").isEmpty())
        assertTrue(ReadingJson.decodeQuestions("[{broken").isEmpty())
    }
}
