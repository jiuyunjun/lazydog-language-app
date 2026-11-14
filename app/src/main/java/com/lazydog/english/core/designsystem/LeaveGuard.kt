package com.lazydog.english.core.designsystem

import androidx.activity.compose.BackHandler
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.lazydog.english.core.ask.SuspendAsk

/**
 * 离开前确认：只在离开**真的会丢东西**的时候问一声。
 *
 * 生成挂在页面的协程作用域上，页面一走就取消。推理模型一次要等几十秒，全面屏手势又极易误触
 * 侧滑返回——不拦的话，等了一分钟、钱花了、东西没了，界面上还什么都不说。
 *
 * [warning] 说清楚这次离开会丢掉什么；传 null 表示没有损失（总结页、失败页、空闲页），
 * 直接走，不多问一句。返回键和页面左上角的返回都该走返回的那个函数。
 */
@Composable
fun rememberLeaveGuard(
    warning: String?,
    onLeave: () -> Unit,
    stayLabel: String = "继续",
): () -> Unit {
    var asking by rememberSaveable { mutableStateOf(false) }
    val currentWarning by rememberUpdatedState(warning)
    val currentOnLeave by rememberUpdatedState(onLeave)

    BackHandler(enabled = warning != null) { asking = true }

    // 问到一半，那件会丢的事自己结束了（生成回来了）：损失已经不存在，收起确认框。
    // 不收的话 asking 一直挂着，下一次又有东西可丢时，确认框会自己冒出来。
    LaunchedEffect(warning == null) {
        if (warning == null) asking = false
    }
    val shown = currentWarning
    if (asking && shown != null) {
        SuspendAsk("退出确认框已打开")
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text("现在退出？") },
            text = { Text(shown) },
            confirmButton = {
                TextButton(
                    onClick = {
                        asking = false
                        currentOnLeave()
                    },
                ) { Text("退出") }
            },
            dismissButton = {
                TextButton(onClick = { asking = false }) { Text(stayLabel) }
            },
        )
    }

    return remember {
        {
            if (currentWarning != null) asking = true else currentOnLeave()
        }
    }
}
