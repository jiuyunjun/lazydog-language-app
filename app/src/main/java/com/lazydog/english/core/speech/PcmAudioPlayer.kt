package com.lazydog.english.core.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.SystemClock
import kotlinx.coroutines.delay

/**
 * 自己播 TTS 音频，不用 Azure SDK 的默认扬声器输出。
 *
 * 为什么要自己播（都是用户实测反馈的起播问题）：
 * 1. SDK 内部按单声道建 AudioTrack，起播那几十毫秒只有左声道出声，戴耳机能明显听出
 *    "先左耳、然后才两只耳朵"。这里把单声道样本复制成左右两份，从第 0 个样本起两边就一样。
 *    （Azure TTS 只出单声道，双声道只能在客户端复制出来。）
 * 2. 音频通路（尤其蓝牙刚被唤醒时）需要一点时间稳定。真正的语音前面垫一段静音，
 *    让抖动和爆音落在静音里，开头那一两个词不会被削掉。静音长度按输出设备来定，
 *    见 [leadInSilenceMs]；静音在 [begin] 就开始播，和等合成的时间重叠。
 *
 * AudioTrack 建一次就一直留着，每次朗读只是 pause+flush 后复用——每念一个词都重建
 * AudioTrack，等于每次都从冷通路重新起播，开头吞音会更严重。
 *
 * 播放是流式的：合成出一块就播一块，长句不用等整段合成完。
 *
 * 一次只播一段：[begin] 会掐掉上一段并返回本次的令牌，[write]/[drain] 都要带上它，
 * 令牌过期就说明被新的朗读顶掉了，直接不做事。
 */
internal class PcmAudioPlayer(
    context: Context,
    private val sampleRateHz: Int,
) {

    private val appContext = context.applicationContext
    private val audioManager: AudioManager? = appContext.getSystemService(AudioManager::class.java)

    /** track / focus / 令牌都在这把锁下改，[begin] 可能和上一段的 write 撞在一起。 */
    private val lock = Any()

    private var track: AudioTrack? = null
    private var focusRequest: AudioFocusRequest? = null

    /** 当前朗读的令牌，每次 [begin] 自增；旧令牌一律作废。 */
    @Volatile
    private var token = 0

    private var released = false

    /** 本段已写入的立体声帧数，用来算还剩多久播完。flush 之后跟播放头一起归零。 */
    private var writtenFrames = 0L

    private var playing = false

    /** 上一段播完（或被掐断）的时刻，用来判断这次是不是冷起播。 */
    private var lastStopUptimeMs = 0L

    /** 第一块真正的音频还没写进去——写进去之前要先把多余的静音裁掉，见 [write]。 */
    private var speaking = false

    /**
     * 开始一次新的朗读：掐掉上一段，立刻开始播前置静音，返回本次令牌。
     *
     * 静音必须在这里就开始播，而不是等第一块音频到了再垫：静音一开声，蓝牙通路就同时在唤醒，
     * 跟等合成返回的那几百毫秒是重叠的，语音开口时刻是 max(静音, 首块音频到达) 而不是两者相加。
     * 而且这段静音一次就按最长的写足，等的时间越长通路醒得越透——真正要留在语音前面的
     * 只有 [TAIL_SILENCE_MS]，多出来的在 [write] 里裁掉，不让它变成干等。
     */
    fun begin(): Int = synchronized(lock) {
        check(!released) { "播放器已释放" }
        stopLocked()
        val current = ++token
        val leadInMs = leadInSilenceMs(currentRoute(), coldStart = isColdStart())
        requestFocusLocked()
        val track = trackLocked()
        if (track != null) {
            // 静音长度不能超过缓冲区容量：写超了 write 会阻塞，反而把起播拖慢。
            writeAllLocked(track, silence(leadInMs))
            track.play()
            playing = true
        }
        current
    }

    /** 这个令牌还是当前朗读吗——false 说明已经被新的朗读顶掉，调用方可以直接收工。 */
    fun isCurrent(token: Int): Boolean = token == this.token

    /** 写入一块单声道 16bit PCM（[length] 是 [monoPcm] 里的有效字节数）。 */
    fun write(token: Int, monoPcm: ByteArray, length: Int) {
        if (length <= 0) return
        val stereo = monoToStereo(monoPcm, length)
        synchronized(lock) {
            if (token != this.token || !playing) return
            val track = track ?: return
            if (!speaking) {
                trimLeadInLocked(track)
                speaking = true
            }
            writeAllLocked(track, stereo)
        }
    }

    /** 等已写入的音频真正播完；被新的朗读顶掉时立即返回。 */
    suspend fun drain(token: Int) {
        while (true) {
            val waitMs = synchronized(lock) {
                if (token != this.token || !playing) return
                val remaining = writtenFrames - playedFramesLocked()
                if (remaining <= 0) return@synchronized 0L
                (remaining * 1000 / sampleRateHz).coerceIn(MIN_DRAIN_POLL_MS, MAX_DRAIN_POLL_MS)
            }
            if (waitMs == 0L) break
            delay(waitMs)
        }
        finish(token)
    }

    /** 本段播完或不再需要：停住播放头、放掉音频焦点，track 留着给下一段复用。 */
    fun finish(token: Int) {
        synchronized(lock) {
            if (token != this.token) return
            stopLocked()
            abandonFocusLocked()
        }
    }

    /** 立刻掐掉声音（点了新的朗读、或者要释放整个 provider 时用）。 */
    fun stop() {
        synchronized(lock) {
            ++token
            stopLocked()
            abandonFocusLocked()
        }
    }

    fun release() {
        synchronized(lock) {
            if (released) return
            released = true
            ++token
            stopLocked()
            abandonFocusLocked()
            runCatching { track?.release() }
            track = null
        }
    }

    /** 暂停并丢掉缓冲里没播的部分。flush 会把播放头归零，写入计数跟着一起清。 */
    private fun stopLocked() {
        speaking = false
        val track = track ?: return
        if (playing) {
            runCatching { track.pause() }
            runCatching { track.flush() }
            playing = false
            lastStopUptimeMs = SystemClock.elapsedRealtime()
        }
        writtenFrames = 0
    }

    /**
     * 第一块语音到手时，把还没播的静音裁到只剩 [TAIL_SILENCE_MS]。
     *
     * 合成来得快的时候，前面写足的静音大半还堵在缓冲里，不裁就得干等它播完。裁掉不影响防吞音：
     * 通路从 [begin] 到现在一直在出声，早就醒透了，语音前面留一小段收尾静音就够。
     */
    private fun trimLeadInLocked(track: AudioTrack) {
        val tailFrames = TAIL_SILENCE_MS.toLong() * sampleRateHz / 1000
        if (writtenFrames - playedFramesLocked() <= tailFrames) return
        runCatching { track.pause() }
        runCatching { track.flush() }
        writtenFrames = 0
        writeAllLocked(track, silence(TAIL_SILENCE_MS))
        track.play()
    }

    private fun silence(durationMs: Int): ByteArray =
        ByteArray(durationMs * sampleRateHz / 1000 * BYTES_PER_FRAME)

    private fun isColdStart(): Boolean =
        track == null || SystemClock.elapsedRealtime() - lastStopUptimeMs > WARM_WINDOW_MS

    /** playbackHeadPosition 是会回绕的 32 位帧计数；一段朗读远到不了回绕点。 */
    private fun playedFramesLocked(): Long =
        (track?.playbackHeadPosition?.toLong() ?: 0L) and 0xFFFFFFFFL

    private fun writeAllLocked(track: AudioTrack, data: ByteArray) {
        var offset = 0
        while (offset < data.size) {
            val written = track.write(data, offset, data.size - offset)
            if (written <= 0) break
            offset += written
        }
        writtenFrames += offset / BYTES_PER_FRAME
    }

    private fun requestFocusLocked() {
        val manager = audioManager ?: return
        if (focusRequest != null) return
        // 朗读是短促的示范音，用 TRANSIENT_MAY_DUCK：别的音乐压一下就行，不用停。
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(audioAttributes())
            .setWillPauseWhenDucked(false)
            .build()
        runCatching { manager.requestAudioFocus(request) }
        focusRequest = request
    }

    private fun abandonFocusLocked() {
        val manager = audioManager ?: return
        val request = focusRequest ?: return
        focusRequest = null
        runCatching { manager.abandonAudioFocusRequest(request) }
    }

    private fun currentRoute(): AudioRoute {
        val devices = audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS).orEmpty()
        val types = devices.map { it.type }
        return when {
            types.any { it in BLUETOOTH_TYPES } -> AudioRoute.Bluetooth
            types.any { it in WIRED_TYPES } -> AudioRoute.Wired
            else -> AudioRoute.Speaker
        }
    }

    private fun trackLocked(): AudioTrack? {
        track?.let { return it }
        val minBytes = AudioTrack.getMinBufferSize(
            sampleRateHz,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        // 缓冲要装得下前置静音再加一点余量：合成分块是按网络来的，缓冲太小句子中间会断续。
        val wantedMs = AudioRoute.entries.maxOf { it.leadInSilenceMs } + COLD_START_EXTRA_MS + BUFFER_MS
        val bufferBytes = maxOf(minBytes, sampleRateHz * BYTES_PER_FRAME * wantedMs / 1000)
        val built = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(audioAttributes())
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRateHz)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build(),
                )
                .setBufferSizeInBytes(bufferBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.getOrNull() ?: return null
        if (built.state != AudioTrack.STATE_INITIALIZED) {
            built.release()
            return null
        }
        track = built
        return built
    }

    private fun audioAttributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private companion object {
        const val BYTES_PER_FRAME = 4 // 立体声 × 16bit
        const val BUFFER_MS = 400
        const val MIN_DRAIN_POLL_MS = 5L
        const val MAX_DRAIN_POLL_MS = 60L

        /** 上一段刚播完不久就认为通路还热着，不用再多垫静音。 */
        const val WARM_WINDOW_MS = 3_000L

        /** 裁完之后留在语音前面的那点静音，只用来吸收 flush 后重新开声的抖动。 */
        const val TAIL_SILENCE_MS = 150

        val BLUETOOTH_TYPES = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_HEARING_AID,
        )

        val WIRED_TYPES = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
        )
    }
}

/**
 * 输出去向。前置静音长度按它来定：蓝牙通路唤醒最慢，吞音也最明显；
 * 手机扬声器和有线耳机快得多，垫太长只会让人觉得点了没反应。
 */
internal enum class AudioRoute(val leadInSilenceMs: Int) {
    Speaker(250),
    Wired(250),

    /** 蓝牙实测 500ms 还会吞开头，取区间上沿；延迟被合成等待盖掉了，见 [PcmAudioPlayer.begin]。 */
    Bluetooth(800),
}

/** 冷起播（第一次播、或者上一段已经过去一会儿）通路要重新唤醒，多垫一点。 */
internal const val COLD_START_EXTRA_MS = 200

internal fun leadInSilenceMs(route: AudioRoute, coldStart: Boolean): Int =
    route.leadInSilenceMs + if (coldStart) COLD_START_EXTRA_MS else 0

/**
 * 单声道 16bit PCM 复制成立体声：每个样本原样写进左右两个声道。
 * [length] 为奇数时忽略最后那个凑不成样本的字节。
 */
internal fun monoToStereo(mono: ByteArray, length: Int): ByteArray {
    val samples = length / 2
    val out = ByteArray(samples * 4)
    var read = 0
    var write = 0
    repeat(samples) {
        val low = mono[read]
        val high = mono[read + 1]
        out[write] = low
        out[write + 1] = high
        out[write + 2] = low
        out[write + 3] = high
        read += 2
        write += 4
    }
    return out
}
