package com.lazydog.english.core.ask

import com.lazydog.english.domain.ask.AskContext
import com.lazydog.english.domain.ask.AskContextKind
import org.junit.Assert.*
import org.junit.Test

class AskControllerTest {
    private val word = AskContext(AskContextKind.Word, "hello")

    @Test fun `missing wiring fails but an explicit loading state passes`() {
        val controller = AskController()
        assertThrows(IllegalStateException::class.java) { controller.requireDeclared("new-page") }
        controller.register(Any(), null, "加载中")
        controller.requireDeclared("new-page")
        assertFalse(controller.canAsk)
        controller.open()
        assertFalse(controller.visible)
    }

    @Test fun `leaving the context closes chat and cannot reopen stale content`() {
        val controller = AskController()
        val owner = Any()
        controller.register(owner, word, "没有词卡")
        controller.open()
        assertTrue(controller.visible)
        controller.unregister(owner)
        controller.open()
        assertNull(controller.context)
        assertFalse(controller.visible)
    }

    @Test fun `disposal of an older owner cannot erase the new context`() {
        val controller = AskController()
        val oldOwner = Any()
        controller.register(oldOwner, word, "加载")
        controller.register(Any(), word.copy(title = "next"), "加载")
        controller.unregister(oldOwner)
        assertEquals("next", controller.context?.title)
    }

    @Test fun `nested overlays pause chat until all are dismissed without losing context`() {
        val controller = AskController()
        controller.register(Any(), word, "加载")
        controller.open()
        val first = Any()
        val second = Any()
        controller.suspend(first, "回想")
        controller.suspend(second, "查词")
        assertFalse(controller.visible)
        controller.resume(first)
        assertFalse(controller.canAsk)
        controller.resume(second)
        assertTrue(controller.canAsk)
        assertEquals(word, controller.context)
        assertFalse(controller.visible)
    }

    @Test fun `recording state replaces ready state and closes the old session`() {
        val controller = AskController()
        controller.register(Any(), word, "加载")
        controller.open()
        controller.register(Any(), null, "正在录音")
        assertEquals(AskAvailability.Unavailable("正在录音"), controller.availability)
        assertFalse(controller.canAsk)
        assertFalse(controller.visible)
    }

    @Test fun `disabled policy requires a real reason`() {
        assertThrows(IllegalArgumentException::class.java) { AskPagePolicy.Disabled(" ") }
    }
}
