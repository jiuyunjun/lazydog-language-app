package com.lazydog.english.feature.library

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow

/** 快捷档位。超出机器支持范围的不显示。 */
private val ZOOM_PRESETS = listOf(1f, 2f, 4f)

/** 拖动时额外画的细刻度，帮助判断拖了多少。 */
private val ZOOM_TICKS = listOf(1f, 1.5f, 2f, 3f, 4f, 5f, 6f, 8f)

/**
 * 扫一扫的缩放条，浮在画面上、紧挨底部控件上沿，整条与屏幕同宽。
 *
 * 人体工学上的取舍：
 * - **居中**：左右手单手握持时，拇指够到屏幕下方正中的距离一样；放在一侧总有一只手要横跨半屏。
 * - **相对拖动**：拇指落在这一条的任何位置左右一抹就缩放，按下处不跳值。普通滑动条要先精确按中
 *   滑块或按哪儿跳哪儿，单手拇指做不准；这里右手在右半边抹、左手在左半边抹都一样。
 *   抹满整条宽度 = 从最小倍数到最大倍数。**向左抹放大、向右抹缩小**，
 *   所以档位和刻度尺也是左大右小，拖动时圆点跟着手指走。
 * - **快捷档位**：4× / 2× / 1× 点一下直接到，最常用的操作不用拖。
 * - **档位轻震**：拖过 1×、2×、4× 时震一下，像拨轮的卡位，眼睛不用离开取景画面。
 */
@Composable
internal fun ScanZoomStrip(
    ratio: Float,
    range: ClosedFloatingPointRange<Float>,
    onRatio: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val low = range.start
    val high = range.endInclusive
    fun progressOf(r: Float) = (ln(r / low) / ln(high / low)).coerceIn(0f, 1f)
    fun ratioAt(p: Float) = low * (high / low).pow(p.coerceIn(0f, 1f))

    var dragging by remember { mutableStateOf(false) }
    val latestRatio by rememberUpdatedState(ratio)
    val latestOnRatio by rememberUpdatedState(onRatio)
    val presets = ZOOM_PRESETS.filter { it in range }
    val readout = String.format(Locale.ROOT, "%.1f×", ratio)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .semantics {
                contentDescription = "缩放画面，左右滑动调整"
                stateDescription = readout
                progressBarRangeInfo = ProgressBarRangeInfo(progressOf(ratio), 0f..1f)
                setProgress { latestOnRatio(ratioAt(it)); true }
            }
            .pointerInput(low, high) {
                var progress = 0f
                detectHorizontalDragGestures(
                    onDragStart = {
                        dragging = true
                        progress = progressOf(latestRatio)
                    },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                ) { change, dx ->
                    change.consume()
                    val before = ratioAt(progress)
                    // 向左拖是放大：左大右小，和档位、刻度尺的排列一致。
                    progress = (progress - dx / size.width).coerceIn(0f, 1f)
                    val after = ratioAt(progress)
                    val crossed = presets.any { preset ->
                        (before < preset && after >= preset) || (before > preset && after <= preset)
                    }
                    if (crossed) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    latestOnRatio(after)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (dragging) {
            ZoomRuler(progress = progressOf(ratio), tickProgress = ZOOM_TICKS.filter { it in range }.map(::progressOf),
                presetProgress = presets.map(::progressOf), readout = readout)
        } else {
            // 当前倍数落在哪一档就点亮哪一档；不在整档上时，亮着的那颗直接显示实际倍数（如 2.6×）。
            val active = presets.lastOrNull { ratio >= it - 0.05f }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                presets.asReversed().forEach { preset ->
                    val selected = preset == active
                    // 浮在相机画面上：深色半透明底 + 白字，选中的一档反白，任何画面上都看得清。
                    Surface(
                        onClick = { onRatio(preset) },
                        shape = CircleShape,
                        color = if (selected) Color.White else Color.Black.copy(alpha = 0.5f),
                        contentColor = if (selected) Color.Black else Color.White,
                        modifier = Modifier
                            .size(if (selected) 48.dp else 40.dp)
                            .semantics { contentDescription = "缩放到 ${preset.toInt()} 倍" },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = if (selected && abs(ratio - preset) > 0.05f) readout
                                else String.format(Locale.ROOT, "%d×", preset.toInt()),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 拖动时的刻度尺：整条宽度就是全部倍数范围，左端最大、右端最小，圆点是当前位置，档位刻度更长。 */
@Composable
private fun ZoomRuler(progress: Float, tickProgress: List<Float>, presetProgress: List<Float>, readout: String) {
    val tickColor = Color.White.copy(alpha = 0.6f)
    val presetColor = Color.White
    val thumbColor = Color.White
    Column(
        modifier = Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)).padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(readout, style = MaterialTheme.typography.labelLarge, color = thumbColor)
        Canvas(Modifier.fillMaxWidth().height(22.dp)) {
            val inset = 12.dp.toPx()
            val usable = size.width - inset * 2
            val y = size.height / 2
            fun xOf(p: Float) = inset + usable * (1 - p)
            tickProgress.forEach { p ->
                val x = xOf(p)
                val long = presetProgress.any { abs(it - p) < 0.001f }
                val half = (if (long) 8.dp else 4.dp).toPx()
                drawLine(if (long) presetColor else tickColor, Offset(x, y - half), Offset(x, y + half),
                    strokeWidth = (if (long) 2.dp else 1.dp).toPx())
            }
            drawCircle(thumbColor, radius = 7.dp.toPx(), center = Offset(xOf(progress), y))
        }
    }
}
