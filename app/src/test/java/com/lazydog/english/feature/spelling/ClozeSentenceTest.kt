package com.lazydog.english.feature.spelling

import org.junit.Assert.assertEquals
import org.junit.Test

class ClozeSentenceTest {

    @Test
    fun `a word inside a longer word is not a match`() {
        // 按子串找的话，run 会命中 runs，挖出 "She ___s every morning" 这种残句。
        assertEquals(-1, wordIndexOf("She runs every morning.", "run"))
        assertEquals(-1, wordIndexOf("I am going home.", "go"))
        assertEquals(4, wordIndexOf("She ran every morning.", "ran"))
    }

    @Test
    fun `punctuation and case do not stop a match`() {
        assertEquals(0, wordIndexOf("Go home.", "go"))
        assertEquals(7, wordIndexOf("Let me go, please.", "go"))
    }

    @Test
    fun `the later occurrence is found when the first one is inside a word`() {
        assertEquals(12, wordIndexOf("I was going go home.", "go"))
    }
}
