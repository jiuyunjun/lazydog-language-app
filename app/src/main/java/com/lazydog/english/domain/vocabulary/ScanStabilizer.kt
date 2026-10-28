package com.lazydog.english.domain.vocabulary

import kotlin.math.PI
import kotlin.math.abs

/**
 * 扫一扫的帧间稳定：实时识别每帧都从头认一遍，原样显示会抖成三种样子，各治各的。
 *
 * 1. **框在颤**：同一个词每帧给的框差几个像素，再叠上手抖。用 [OneEuroFilter] 平滑框——
 *    拿稳时压得很狠，快速移动时自动放松，不会让框拖在字后面。
 * 2. **目标在两个词之间跳**：准星压在交界处，每帧离谁更近都在变。现任有 [stickiness] 的粘性，
 *    别的词要连续 [confirmFrames] 帧胜出才换；单独一帧把 world 认成 wor1d 也就换不过去。
 * 3. **一两帧认不出字**：目标保留 [holdMillis]，结果卡和加号不跟着一闪一灭。
 *
 * 句子另外去抖：整句里任何一个词认错一帧，句子就变一次。用 [SentenceFusion] 按词多帧投票，
 * 准星词也取投票结果——锁定时那一帧把 world 认成 wor，几帧之后会被纠正回来。
 */
class ScanStabilizer(
    private val holdMillis: Long = 600,
    private val confirmFrames: Int = 2,
) {
    /** 对外给的结果：框已平滑、句子已去抖。 */
    private var current: ScanTarget? = null
    /** 上一帧锁定词的原始识别结果，拿来在下一帧里认"还是不是它"。 */
    private var currentRaw: ScanTarget? = null
    private var lastSeenAt = 0L
    private var challenger: ScanTarget? = null
    private var challengerFrames = 0
    private val fusion = SentenceFusion()
    private val box = BoxFilter()

    fun update(
        blocks: List<ScannedBlock>,
        x: Float,
        y: Float,
        tolerance: Float,
        stickiness: Float,
        nowMillis: Long,
    ): ScanTarget? {
        // 即使每帧都有不同的挑战者，旧词也不能无限续命。
        if (current != null && nowMillis - lastSeenAt > holdMillis) clear()
        val raw = ScanAim.aim(blocks, x, y, tolerance, prefer = currentRaw, stickiness = stickiness)
        val locked = currentRaw
        when {
            raw == null -> {
                challenger = null
                challengerFrames = 0
                if (current != null && nowMillis - lastSeenAt > holdMillis) clear()
            }
            locked == null -> adopt(raw, nowMillis, continuing = false)
            ScanAim.sameWord(raw, locked) -> adopt(raw, nowMillis, continuing = true)
            else -> {
                challengerFrames = if (challenger?.let { ScanAim.sameWord(raw, it) } == true) challengerFrames + 1 else 1
                challenger = raw
                if (challengerFrames >= confirmFrames) adopt(raw, nowMillis, continuing = false)
            }
        }
        return current
    }

    fun clear() {
        current = null
        currentRaw = null
        challenger = null
        challengerFrames = 0
        fusion.reset()
        box.reset()
    }

    private fun adopt(raw: ScanTarget, nowMillis: Long, continuing: Boolean) {
        val previous = current
        // 换了目标词也不清投票：同一句里挪到隔壁词，句子照样稳；挪到别的句子时 fusion 自己会清。
        if (!continuing) box.reset()
        challenger = null
        challengerFrames = 0
        currentRaw = raw
        lastSeenAt = nowMillis

        // 只平滑锁定词自己的框，句子里其他词的框跟着它整体平移：
        // 整句是一起动的，各框各自滤波反而会把一行字滤得参差不齐。
        val rawBox = raw.wordBoxes.first()
        val smooth = box.filter(rawBox, nowMillis)
        val dx = smooth.centerX - rawBox.centerX
        val dy = smooth.centerY - rawBox.centerY

        val fused = fusion.add(raw.sentence, raw.wordIndex)
        current = raw.copy(
            word = fused.word ?: if (continuing && previous != null) previous.word else raw.word,
            sentence = fused.sentence,
            wordBoxes = listOf(smooth) + raw.wordBoxes.drop(1).map { it.offset(dx, dy) },
            sentenceBoxes = raw.sentenceBoxes.map { it.offset(dx, dy) },
            wordIndex = fused.wordIndex,
        )
    }
}

/** 一个框的四个量（中心、宽、高）各一份 One Euro。宽高变化本来就慢，给更小的 beta。 */
private class BoxFilter {
    private val cx = OneEuroFilter()
    private val cy = OneEuroFilter()
    private val w = OneEuroFilter(beta = OneEuroFilter.DEFAULT_BETA / 4)
    private val h = OneEuroFilter(beta = OneEuroFilter.DEFAULT_BETA / 4)

    fun filter(raw: TextBox, nowMillis: Long): TextBox {
        val x = cx.filter(raw.centerX, nowMillis)
        val y = cy.filter(raw.centerY, nowMillis)
        val halfW = w.filter(raw.width, nowMillis) / 2f
        val halfH = h.filter(raw.height, nowMillis) / 2f
        return TextBox(x - halfW, y - halfH, x + halfW, y + halfH)
    }

    fun reset() {
        cx.reset()
        cy.reset()
        w.reset()
        h.reset()
    }
}

/**
 * One Euro 滤波（Casiez 等，CHI 2012）：自适应截止频率的一阶低通。
 *
 * 截止频率 = [minCutoff] + [beta] × |速度|。几乎不动时截止频率低、抖动被压掉；
 * 动得快时截止频率升高、几乎不滞后。比固定系数的指数平滑强在这里：
 * 后者要么静止时还颤、要么一移动框就拖在字后面，两头只能顾一头。
 *
 * 单位：值是像素，时间是毫秒。参数是按"720p 分析帧、约 10 帧每秒、手持"调的起点，
 * 真机上觉得还颤就调小 [minCutoff]，觉得跟不上就调大 [beta]。
 */
class OneEuroFilter(
    private val minCutoff: Double = DEFAULT_MIN_CUTOFF,
    private val beta: Double = DEFAULT_BETA,
    private val derivativeCutoff: Double = 1.0,
) {
    private var last: Double? = null
    private var lastDerivative = 0.0
    private var lastAt = 0L

    fun filter(value: Float, nowMillis: Long): Float {
        val previous = last
        if (previous == null) {
            last = value.toDouble()
            lastAt = nowMillis
            return value
        }
        val dt = (nowMillis - lastAt).coerceAtLeast(1) / 1000.0
        lastAt = nowMillis
        val derivative = (value - previous) / dt
        lastDerivative += alpha(derivativeCutoff, dt) * (derivative - lastDerivative)
        val cutoff = minCutoff + beta * abs(lastDerivative)
        val next = previous + alpha(cutoff, dt) * (value - previous)
        last = next
        return next.toFloat()
    }

    fun reset() {
        last = null
        lastDerivative = 0.0
    }

    private fun alpha(cutoff: Double, dt: Double): Double {
        val tau = 1.0 / (2 * PI * cutoff)
        return 1.0 / (1.0 + tau / dt)
    }

    companion object {
        const val DEFAULT_MIN_CUTOFF = 0.8
        const val DEFAULT_BETA = 0.01
    }
}
