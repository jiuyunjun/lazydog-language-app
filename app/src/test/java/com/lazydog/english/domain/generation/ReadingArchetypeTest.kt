package com.lazydog.english.domain.generation

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 写法轮换（`引人入胜的阅读材料DESIGN.md` §6、§20）。 */
class ReadingArchetypeTest {

    @Test
    fun `避开最近用过的写法`() {
        val recent = listOf("hidden_system", "case_study", "narrative")
        repeat(20) { seed ->
            val picked = ReadingArchetype.pick(recent, Random(seed))
            assertTrue(picked.wire, picked.wire !in recent)
        }
    }

    @Test
    fun `全用过了就重来一轮，而不是退回没有写法`() {
        // 宁可重复一个老写法，也不能因为"都用过了"就放任自由发挥——那才是十篇一个样。
        val all = ReadingArchetype.entries.map { it.wire }
        val picked = ReadingArchetype.pick(all, Random(1))
        assertTrue(picked.wire in all)
    }

    @Test
    fun `认不出来的写法当作没用过`() {
        // 旧材料的 archetype 是空串（v17 之前存的），不该把整轮轮换搞乱。
        val picked = ReadingArchetype.pick(listOf("", "不存在的写法"), Random(3))
        assertTrue(picked.wire.isNotBlank())
    }

    @Test
    fun `wire 能来回转换`() {
        ReadingArchetype.entries.forEach {
            assertEquals(it, ReadingArchetype.fromWire(it.wire))
        }
        assertNull(ReadingArchetype.fromWire("nope"))
    }

    @Test
    fun `每种写法都给了模型一段英文说明`() {
        // 说明是要拼进英文写作指令里的，混着中文只会让模型跟着切换语言。
        ReadingArchetype.entries.forEach {
            assertTrue(it.wire, it.briefEn.isNotBlank())
            assertTrue(it.wire, it.briefEn.none { ch -> ch.code in 0x4E00..0x9FFF })
            assertTrue(it.wire, it.labelZh.isNotBlank())
        }
    }
}
