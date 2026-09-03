package com.lazydog.english.domain.generation

import org.junit.Assert.assertEquals
import org.junit.Test

class JsonStreamAllStringsTest {

    @Test
    fun `every value of a repeated key comes out in order`() {
        val raw = """{"words":[{"term":"flourish","meaningZh":"茂盛"},{"term":"tangible","meaningZh":"看得见的"}"""
        assertEquals(listOf("flourish", "tangible"), JsonStream.allStrings(raw, "term"))
    }

    @Test
    fun `the value still being written is included`() {
        // 预览的意义正在于此：最后那半个词也要露出来，它证明模型还在动。
        val raw = """{"items":[{"sentenceEn":"She ___ here."},{"sentenceEn":"They ___ al"""
        assertEquals(listOf("She ___ here.", "They ___ al"), JsonStream.allStrings(raw, "sentenceEn"))
    }

    @Test
    fun `a key that has not arrived yields nothing`() {
        assertEquals(emptyList<String>(), JsonStream.allStrings("""{"schemaVersion":1,"words":[""", "term"))
    }

    @Test
    fun `the limit keeps a long batch from filling the screen`() {
        val raw = (1..30).joinToString(",", "{\"words\":[", "]}") { """{"term":"w$it"}""" }
        assertEquals(5, JsonStream.allStrings(raw, "term", limit = 5).size)
    }
}
