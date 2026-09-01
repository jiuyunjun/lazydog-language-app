package com.lazydog.english.domain.listening

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SoundChangesTest {

    @Test
    fun `a consonant running into a vowel is reported as linking`() {
        val changes = analyzeSoundChanges("Please pick up an extra chair.")
        val linking = changes.firstOrNull { it.rule == SoundRule.Linking }
        assertTrue("应该找出连读：$changes", linking != null)
        assertTrue(linking!!.noteZh.contains("粘成"))
    }

    @Test
    fun `a t between vowels is called out as the flapped t`() {
        val changes = analyzeSoundChanges("I got it from the water cooler.")
        assertTrue(changes.any { it.rule == SoundRule.Flap })
    }

    @Test
    fun `weak forms of function words are explained`() {
        val changes = analyzeSoundChanges("I need to talk to you.")
        assertTrue(changes.any { it.rule == SoundRule.WeakForm && it.noteZh.contains("to") })
    }

    @Test
    fun `contractions are explained as one word`() {
        val changes = analyzeSoundChanges("I'm gonna check the schedule.")
        assertTrue(changes.any { it.rule == SoundRule.Contraction })
    }

    @Test
    fun `the same rule is only illustrated once`() {
        // 三条都在讲连读的话，这张卡就退化成同一句话说三遍。
        val changes = analyzeSoundChanges("Pick up an old orange in an open box.")
        assertEquals(changes.map { it.rule }.distinct().size, changes.size)
    }

    @Test
    fun `at most three changes are shown`() {
        val changes = analyzeSoundChanges("I'm gonna pick up an extra water bottle for her at eight.")
        assertTrue(changes.size <= 3)
    }

    @Test
    fun `the key expression is explained first`() {
        // 用户刚刚没听出来的多半就是重点表达那一段，讲解从那儿开始。
        val changes = analyzeSoundChanges(
            textEn = "She said the deal is off and everyone left.",
            focusEn = "deal is off",
        )
        assertTrue("$changes", changes.first().spanEn.lowercase() in "deal is off")
    }

    @Test
    fun `a sentence with nothing worth saying yields nothing`() {
        assertEquals(emptyList<SoundChange>(), analyzeSoundChanges("Hi."))
    }

    @Test
    fun `a silent e still counts as a consonant ending`() {
        val changes = analyzeSoundChanges("Take our time and see.")
        assertTrue(changes.any { it.rule == SoundRule.Linking && it.spanEn == "Take our" })
    }

    @Test
    fun `a word that only looks like it starts with a vowel is left alone`() {
        // "one" 读起来是 w 开头，不构成连读；误报比漏报更伤——用户会照着错的去听。
        val changes = analyzeSoundChanges("It cost one dollar.")
        assertTrue(changes.none { it.rule == SoundRule.Linking && it.spanEn.contains("one") })
    }
}
