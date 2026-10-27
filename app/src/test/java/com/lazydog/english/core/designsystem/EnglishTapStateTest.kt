package com.lazydog.english.core.designsystem

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EnglishTapStateTest {
    private val text = "Try this word. Another sentence."

    @Test fun `single tap preserves the original span or chip action`() = runTest {
        var selected = 0
        val state = EnglishTapState(text, this) { error("Should not speak") }
        state.onTap(5, false) { selected++ }
        assertEquals(0, selected)
        advanceUntilIdle()
        assertEquals(1, selected)
        assertNull(state.word)
    }

    @Test fun `double tap opens word lookup without firing the single action`() = runTest {
        val state = EnglishTapState(text, this) { error("Should not speak") }
        repeat(2) { state.onTap(5, true) { error("Should not select") } }
        advanceUntilIdle()
        assertEquals("this", state.word?.first)
        assertNull(state.sentence)
    }

    @Test fun `triple tap opens sentence without opening word or selecting span`() = runTest {
        val state = EnglishTapState(text, this) { error("Should not speak") }
        repeat(3) { state.onTap(5, true) { error("Should not select") } }
        advanceUntilIdle()
        assertEquals("Try this word.", state.sentence)
        assertNull(state.word)
    }

    @Test fun `long press or leaving text cancels delayed single actions`() = runTest {
        val state = EnglishTapState(text, this) { error("Should not speak") }
        state.onTap(5, true) { error("Should not select") }
        state.cancelPendingTap()
        advanceUntilIdle()
        assertNull(state.word)
        assertNull(state.sentence)
    }
}
