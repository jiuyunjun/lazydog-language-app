package com.lazydog.english.core.ask

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.lazydog.english.domain.ask.AskContext

/**
 * 一个学习页面范围内的提问状态。页面通过 [ProvideAskContext] 注册"现在能问什么"，
 * 摇一摇或顶栏问号通过 [open] 把抽屉升起来。
 */
@Stable
class AskController {

    /** 当前页面注册的上下文；为 null 表示这页不响应摇一摇。 */
    var context: AskContext? by mutableStateOf(null)
        private set

    var visible: Boolean by mutableStateOf(false)
        private set

    /** 没有重力传感器、或用户在设置里打开时，页面顶栏显示问号入口。 */
    var showTopBarIcon: Boolean by mutableStateOf(false)

    val canAsk: Boolean get() = context != null

    fun register(value: AskContext) {
        context = value
    }

    fun unregister(value: AskContext) {
        if (context == value) context = null
    }

    fun open() {
        if (canAsk) visible = true
    }

    fun close() {
        visible = false
    }
}

val LocalAskController = staticCompositionLocalOf<AskController?> { null }

/**
 * 把当前屏幕的结构化上下文注册给外层 AskHost。[context] 为 null 时这一屏不可提问
 * （比如生成中、失败页）。不在 AskHost 里时是空操作，页面可以单独预览。
 */
@Composable
fun ProvideAskContext(context: AskContext?) {
    val controller = LocalAskController.current ?: return
    DisposableEffect(controller, context) {
        if (context != null) controller.register(context)
        onDispose { if (context != null) controller.unregister(context) }
    }
}
