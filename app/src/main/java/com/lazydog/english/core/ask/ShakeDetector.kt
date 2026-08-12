package com.lazydog.english.core.ask

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import kotlin.math.sqrt

/**
 * 摇一摇检测。阈值按用户设的灵敏度取，触发后有一段冷却，避免走路时连着弹。
 * 没有加速度传感器的设备走顶栏问号降级方案（见 AskController）。
 */
object ShakeDetector {

    /** 设置里的三档灵敏度，值即 UserPreferences 存的 index。 */
    const val SENSITIVITY_LOW = 0
    const val SENSITIVITY_MEDIUM = 1
    const val SENSITIVITY_HIGH = 2

    val sensitivityLabels = listOf("低 · 得用力甩", "适中", "高 · 轻轻一晃")

    /** 合力相对重力的倍数；越低越灵敏。 */
    private val thresholds = listOf(2.7f, 2.1f, 1.6f)

    /** 去抖：一次摇动会连着越过阈值好几帧，这段时间内的重复越界忽略。 */
    internal const val DEBOUNCE_MS = 300L

    /** 弹出抽屉后的冷却，防止手放下的回摆被判成第二次。 */
    internal const val COOLDOWN_MS = 1200L

    fun isAvailable(context: Context): Boolean =
        sensorManager(context)?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null

    fun thresholdFor(sensitivity: Int): Float =
        thresholds[sensitivity.coerceIn(0, thresholds.lastIndex)]

    fun label(sensitivity: Int): String =
        sensitivityLabels[sensitivity.coerceIn(0, sensitivityLabels.lastIndex)]

    private fun sensorManager(context: Context): SensorManager? =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
}

/**
 * [enabled] 为 false 时不注册监听，也就不耗电。
 * 抽屉打开、页面没注册上下文时调用方应该传 false。
 */
@Composable
fun ShakeToAsk(
    enabled: Boolean,
    sensitivity: Int,
    onShake: () -> Unit,
) {
    val context = LocalContext.current
    val currentOnShake by rememberUpdatedState(onShake)

    DisposableEffect(enabled, sensitivity) {
        if (!enabled) return@DisposableEffect onDispose { }
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (manager == null || sensor == null) return@DisposableEffect onDispose { }

        val threshold = ShakeDetector.thresholdFor(sensitivity)
        var lastCrossedAt = 0L
        var lastFiredAt = 0L

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                val g = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
                if (g < threshold) return

                val now = System.currentTimeMillis()
                if (now - lastFiredAt < ShakeDetector.COOLDOWN_MS) return
                if (now - lastCrossedAt < ShakeDetector.DEBOUNCE_MS) {
                    lastCrossedAt = now
                    return
                }
                lastCrossedAt = now
                lastFiredAt = now
                currentOnShake()
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { manager.unregisterListener(listener) }
    }
}
