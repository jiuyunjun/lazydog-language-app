package com.lazydog.english.core.ask

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalInspectionMode
import com.lazydog.english.domain.ask.AskContext

/**
 * 一个学习页面范围内的提问状态。页面通过 [ProvideAskContext] 注册"现在能问什么"，
 * 摇一摇或顶栏问号通过 [open] 把抽屉升起来。
 */
@Stable
class AskController {

    /** 页面注册情况；未接入与暂不可用必须可区分。 */
    var availability: AskAvailability by mutableStateOf(AskAvailability.Missing)
        private set

    val context: AskContext? get() = (availability as? AskAvailability.Ready)?.context
    val isDeclared: Boolean get() = availability != AskAvailability.Missing
    private var owner: Any? = null
    private val suspensions = mutableStateMapOf<Any, String>()

    var visible: Boolean by mutableStateOf(false)
        private set

    /** 没有重力传感器、或用户在设置里打开时，页面顶栏显示问号入口。 */
    var showTopBarIcon: Boolean by mutableStateOf(false)

    val canAsk: Boolean get() = context != null && suspensions.isEmpty()

    fun suspend(owner: Any, reason: String) {
        require(reason.isNotBlank())
        suspensions[owner] = reason
        close()
    }

    fun resume(owner: Any) { suspensions.remove(owner) }

    fun register(owner: Any, value: AskContext?, unavailableReason: String) {
        require(unavailableReason.isNotBlank()) { "暂不可用时必须说明原因" }
        val next = value?.let(AskAvailability::Ready) ?: AskAvailability.Unavailable(unavailableReason)
        if (next != availability) close()
        this.owner = owner
        availability = next
    }

    fun unregister(owner: Any) {
        if (this.owner === owner) {
            this.owner = null
            availability = AskAvailability.Missing
            close()
        }
    }

    fun requireDeclared(pageName: String) {
        check(isDeclared) { "$pageName 启用了 AI Chat，但未调用 ProvideAskContext 声明当前状态" }
    }

    fun open() {
        if (canAsk) visible = true
    }

    fun close() {
        visible = false
    }
}

sealed interface AskAvailability {
    data object Missing : AskAvailability
    data class Unavailable(val reason: String) : AskAvailability
    data class Ready(val context: AskContext) : AskAvailability
}

/** 每个导航目的地必须显式选择；Required 在未注册时会暴露接入错误。 */
sealed interface AskPagePolicy {
    data object Required : AskPagePolicy
    data class Disabled(val reason: String) : AskPagePolicy {
        init { require(reason.isNotBlank()) }
    }
}

val LocalAskController = staticCompositionLocalOf<AskController?> { null }

/**
 * 把当前屏幕的结构化上下文注册给外层 AskHost。[context] 为 null 时这一屏不可提问
 * （比如生成中、失败页）。预览可不挂宿主；真实页面缺宿主属于接入错误。
 */
@Composable
fun ProvideAskContext(context: AskContext?, unavailableReason: String) {
    val controller = LocalAskController.current
    if (LocalInspectionMode.current) return
    checkNotNull(controller) { "ProvideAskContext 所在页面未声明 AskPagePolicy.Required" }
    DisposableEffect(controller, context, unavailableReason) {
        val owner = Any()
        controller.register(owner, context, unavailableReason)
        onDispose { controller.unregister(owner) }
    }
}

/** 模态内容临时遮住学习页时暂停提问；关闭后恢复原上下文，不覆盖或伪造它。 */
@Composable
fun SuspendAsk(reason: String) {
    val controller = LocalAskController.current ?: return
    DisposableEffect(controller, reason) {
        val owner = Any()
        controller.suspend(owner, reason)
        onDispose { controller.resume(owner) }
    }
}
