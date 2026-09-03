package com.lazydog.english.core.model

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TopicCatalogTest {

    @Test
    fun `preferred topics come first and nothing repeats`() {
        val list = TopicCatalog.withPreferred(listOf("游戏", "自己写的一个"))
        assertEquals(listOf("游戏", "自己写的一个"), list.take(2))
        assertEquals(list.size, list.distinct().size)
        assertTrue(list.containsAll(TopicCatalog.all))
    }

    @Test
    fun `blank preferences are dropped`() {
        assertEquals(TopicCatalog.all, TopicCatalog.withPreferred(listOf("", "  ")))
    }

    @Test
    fun `random never returns the topic already chosen`() {
        // 点了"随机"却原地不动，看起来就像按钮坏了。
        repeat(50) { seed ->
            assertNotEquals("旅行", TopicCatalog.random(exclude = "旅行", random = Random(seed)))
        }
    }

    @Test
    fun `the catalog is big enough to be worth browsing`() {
        assertTrue(TopicCatalog.all.size >= 30)
        assertEquals(TopicCatalog.all.size, TopicCatalog.all.distinct().size)
    }
}
