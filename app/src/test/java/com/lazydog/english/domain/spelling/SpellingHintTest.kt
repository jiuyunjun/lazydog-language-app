package com.lazydog.english.domain.spelling

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 提示阶梯：题面上的骨架逐级显形（`拼写训练DESIGN.md` §12）。 */
class SpellingHintTest {

    private val word = "environment"
    private val facts = SpellingFacts(chunks = listOf("en", "viron", "ment"))

    private fun hint(level: Int, wrong: String = "", ipa: String = "/ɪnˈvaɪrənmənt/") =
        spellingHint(word, level, facts, ipa, emptyList(), wrong)

    private fun letters(skeleton: String) = skeleton.filter { it.isLetter() }

    @Test
    fun `第 0 级只有字母位，连词块都不划`() {
        val h = hint(0)
        assertEquals(HintStage.Blank, h.stage)
        assertTrue(h.skeleton, letters(h.skeleton).isEmpty())
        assertTrue(h.skeleton, !h.skeleton.contains("·"))
        // 字母数本身就是信息，不该再单收一级的费。
        assertEquals(word.length, h.skeleton.count { it == '_' })
    }

    @Test
    fun `第 1 级只给结构，一个字母都不给`() {
        val h = hint(1)
        assertTrue(h.skeleton, letters(h.skeleton).isEmpty())
        assertTrue(h.skeleton, h.skeleton.contains("·"))
    }

    @Test
    fun `第 2 级每块给首字母`() {
        val h = hint(2)
        assertEquals("e v m", letters(h.skeleton).toList().joinToString(" "))
    }

    @Test
    fun `第 3 级给读音，字母不多给`() {
        val sound = hint(3)
        assertEquals(letters(hint(2).skeleton), letters(sound.skeleton))
        assertTrue(sound.ipa.isNotBlank())
        // 前面几级不许提前把音标漏出去。
        assertTrue(hint(2).ipa.isEmpty())
    }

    @Test
    fun `第 4 级把这次写错的那一段显出来`() {
        val h = hint(4, wrong = "enviroment")
        assertTrue(h.skeleton, h.skeleton.contains("v i r o n"))
    }

    @Test
    fun `只有最后一级才给出完整拼写`() {
        for (level in 0..4) {
            val h = hint(level, wrong = "enviroment")
            assertTrue("level $level 漏了答案：${h.skeleton}", letters(h.skeleton) != word)
        }
        assertEquals(word, letters(hint(5).skeleton))
    }

    @Test
    fun `每一级给的都不比上一级少`() {
        // 阶梯的意义就在这：花了分就得多换到东西。
        var previous = 0
        for (level in 0..5) {
            val shown = letters(hint(level, wrong = "enviroment").skeleton).length
            assertTrue("level $level 比上一级给得还少", shown >= previous)
            previous = shown
        }
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
        assertEquals("cat", letters(h.skeleton))
    }
}
