package com.lazydog.english.domain.spelling

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 提示阶梯：让题面上那排格子多亮几个（`拼写训练DESIGN.md` §7）。 */
class SpellingHintTest {

    private val word = "environment"
    private val facts = SpellingFacts(chunks = listOf("en", "viron", "ment"))

    /** 完全空的题面，用来单独看某一级给了什么。 */
    private val blank = "_".repeat(word.length)

    private fun hint(level: Int, wrong: String = "", ipa: String = "/ɪnˈvaɪrənmənt/") =
        spellingHint(word, level, facts, ipa, emptyList(), wrong)

    private fun shown(level: Int, wrong: String = "") =
        hint(level, wrong).mask(blank).filter { it != '_' }

    @Test
    fun `第 0 级什么都不给`() {
        assertEquals(HintStage.Blank, hint(0).stage)
        assertEquals("", shown(0))
        // 词块也还不划：结构是第 1 级买的东西。
        assertTrue(!hint(0).groupsVisible)
    }

    @Test
    fun `第 1 级只给结构，一个字母都不给`() {
        assertEquals("", shown(1))
        assertTrue(hint(1).groupsVisible)
        assertEquals(setOf(0, 2, 7), hint(1).chunkStarts)
    }

    @Test
    fun `第 2 级每块给首字母`() {
        assertEquals("evm", shown(2))
    }

    @Test
    fun `第 3 级给读音，字母不多给`() {
        assertEquals(shown(2), shown(3))
        assertTrue(hint(3).ipa.isNotBlank())
        // 前面几级不许提前把音标漏出去。
        assertTrue(hint(2).ipa.isEmpty())
    }

    @Test
    fun `第 4 级把这次写错的那一段显出来`() {
        assertTrue(shown(4, wrong = "enviroment").contains("viron"))
    }

    @Test
    fun `只有最后一级才给出完整拼写`() {
        for (level in 0..4) {
            assertTrue("level $level 漏了答案", shown(level, wrong = "enviroment") != word)
        }
        assertEquals(word, hint(5).mask(blank))
    }

    @Test
    fun `每一级给的都不比上一级少`() {
        // 阶梯的意义就在这：花了分就得多换到东西。
        var previous = 0
        for (level in 0..5) {
            val count = shown(level, wrong = "enviroment").length
            assertTrue("level $level 比上一级给得还少", count >= previous)
            previous = count
        }
    }

    @Test
    fun `提示只会让题面露得更多，盖不掉题型本来给的`() {
        // 局部补全本来就露着大半个词：合并之后那些字母必须还在。
        val base = "en_____ment"
        val merged = hint(2).mask(base)
        assertEquals(11, merged.length)
        base.forEachIndexed { i, c ->
            if (c != '_') assertEquals("第 $i 位被提示盖掉了", c, merged[i])
        }
        // 而且确实多给了东西：viron 那块的首字母亮了。
        assertEquals('v', merged[2])
    }

    @Test
    fun `按钮上写的是下一级会给什么`() {
        assertEquals(HintStage.Chunks, hint(0).next)
        assertEquals(HintStage.Answer, hint(4).next)
        assertEquals(null, hint(5).next)
    }

    @Test
    fun `短词没有词块也照样能显形`() {
        val h = spellingHint("cat", HintStage.Answer.level, SpellingFacts.None)
        assertEquals("cat", h.mask("___"))
    }
}
