package com.lazydog.english.core.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.delay

/**
 * 自己播 TTS 音频，不用 Azure SDK 的默认扬声器输出。
 *
 * 为什么要自己播（都是用户实测反馈的起播问题）：
 * 1. SDK 内部按单声道建 AudioTrack，起播那几十毫秒只有左声道出声，戴耳机能明显听出
 *    "先左耳、然后才两只耳朵"。这里把单声道样本复制成左右两份，从第 0 个样本起两边就一样。
 *    （Azure TTS 只出单声道，双声道只能在客户端复制出来。）
 * 2. 音频通路（尤其蓝牙刚被唤醒时）需要一点时间稳定。真正的语音前面垫一段静音，让抖动和
 *    爆音落在静音里，开头那一两个词不会被削掉。静音长度按输出设备来定，见 [leadInSilenceMs]。
 *
 *    静音是在**第一块语音到手时**连同语音一起写进去、然后才开声的，而不是在 [begin] 就先
 *    开声空放。曾经那样干过（想让唤醒和等合成的几百毫秒重叠），但在 AudioFlinger 面前走不通：
 *    缓冲一空它就把 track 判为 underrun 并停掉，而被停掉的 track **只有下一次写入**才会
 *    重新启动（日志里的 restartIfDisabled）。最后一块语音写完之后再被停掉，就再没有写入能
 *    救它了——剩下的音频烂在缓冲里，用户实测就是"点了整段没声音"，而且是概率性的。
 *    所以现在的铁律是：**只在缓冲里已经有数据时才 play()，并且一直写到收尾**（见 [drain]）。
 *
 * 4. 正常播完用 [AudioTrack.stop]，打断才用 pause + [AudioTrack.flush]。文档写得很清楚：
 *    MODE_STREAM 下 stop 会"把已写入缓冲的数据播完之后再停"，flush 则"把还没播的部分直接丢掉"。
 *    收尾时刻是按时间估的（见 [drain]），估早一点点很正常，用 flush 就等于把尾音剪掉——
 *    用户实测"结尾仓促、有时没读完就断"。用 stop 则估早了也无所谓，剩下的照样播完。
 *
 * 3. 蓝牙下光复用 AudioTrack 还不够。一段读完就 pause+flush，输出流几秒内进入 standby，
 *    A2DP 跟着 suspend，下一次朗读还是冷通路。所以蓝牙输出时空闲期间由 [keepAliveLoop]
 *    持续灌无声数据把链路挂住（这就是"长连接"），起播只需要 [KEPT_ALIVE_LEAD_IN_MS]，
 *    不用再垫将近一秒。挂住有代价（蓝牙射频不休眠），所以有 [KEEP_ALIVE_MAX_IDLE_MS] 上限，
 *    并且只在蓝牙路由上做；[stop] 这种"人已经走了"的信号会立刻收掉。
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

    /** 本段已写入的立体声帧数，用来算这段什么时候播完（见 [drain]）。flush 之后归零。 */
    private var writtenFrames = 0L

    private var playing = false

    /** 上一段播完（或被掐断）的时刻，用来判断这次是不是冷起播。 */
    private var lastStopUptimeMs = 0L

    /** 第一块真正的音频还没写进去——它到手时才垫前置静音、才开声，见 [write]。 */
    private var speaking = false

    /** 本段真正灌进去的语音帧数（不含前后静音），只用来跟收到的音频量对账，见 [lastWrittenMs]。 */
    private var spokenFrames = 0L

    /** 空闲期间灌无声数据的线程；非 null 就说明蓝牙链路正被挂着，见 [keepAliveLoop]。 */
    private var keepAlive: Thread? = null

    /** 本段 [begin] 的时刻，用来在日志里报“等了多久才等到第一块语音”。 */
    private var beganAtUptimeMs = 0L

    /** 本段要垫多长的前置静音（[leadInSilenceMs]），在第一块语音到手时写进去，见 [write]。 */
    private var leadInTargetMs = 0

    /** 收尾期间反复补的一小块静音，只为把可能被停掉的 track 重新启动，见 [drain]。 */
    private val pokeSilence: ByteArray by lazy { silence(POKE_SILENCE_MS) }

    /**
     * 本段真正开声（[AudioTrack.play]）的时刻，0 表示还没开声。
     *
     * 播放进度只能靠它加上已写入时长来推——[playedFramesLocked] 不可信：track 因 underrun
     * 被 AudioFlinger 停掉之后播放头会归零且不再推进（用户实测：等了 1018ms 播放头还是 0），
     * 拿它算余量会一直以为"缓冲还满着"而不再补数据，拿它算剩余会永远等不完。
     */
    private var playStartUptimeMs = 0L

    /**
     * 开始一次新的朗读：掐掉上一段，返回本次令牌。
     *
     * 这里只是把上一段清干净、把本次要垫多长静音算好，**不写数据也不开声**——等合成的这几百
     * 毫秒里让 track 安安静静停着。空放静音去跟合成延迟抢时间的做法见类注释第 2 条，那条路
     * 会把 track 送进 underrun 被停掉的状态，代价是整段没声音。
     */
    fun begin(): Int = synchronized(lock) {
        check(!released) { "播放器已释放" }
        stopLocked()
        spokenFrames = 0
        val current = ++token
        requestFocusLocked()
        // 路由要在 track 建好之后问：routedDevice 才是真正在出声的那个，见 [currentRoute]。
        val track = trackLocked()
        val route = currentRoute()
        val leadInMs = leadInSilenceMs(
            route = route,
            coldStart = isColdStart(),
            keptAlive = keepAlive != null,
        )
        beganAtUptimeMs = SystemClock.elapsedRealtime()
        leadInTargetMs = leadInMs
        playing = track != null
        Log.d(TAG, "开播 #$current：路由=$route 前置静音=${leadInMs}ms 链路挂着=${keepAlive != null}")
        current
    }

    /** 这个令牌还是当前朗读吗——false 说明已经被新的朗读顶掉，调用方可以直接收工。 */
    fun isCurrent(token: Int): Boolean = token == this.token

    /** 本段真正灌进 AudioTrack 的语音时长（毫秒）；跟收到的音频量一比就知道有没有丢。 */
    fun lastWrittenMs(): Long = synchronized(lock) { spokenFrames * 1000 / sampleRateHz }

    /** 写入一块单声道 16bit PCM（[length] 是 [monoPcm] 里的有效字节数）。 */
    fun write(token: Int, monoPcm: ByteArray, length: Int) {
        if (length <= 0) return
        val stereo = monoToStereo(monoPcm, length)
        synchronized(lock) {
            // 令牌还是当前的却没在播，说明这段音频没人接着——丢掉之前必须留个记号，
            // 否则调用方看到的只是"点了没声音"，哪一层丢的完全查不出来。
            if (token == this.token && !playing) {
                Log.w(TAG, "丢弃音频：playing=false token=$token track=${track != null} keepAlive=${keepAlive != null}")
            }
            if (token != this.token || !playing) return
            val track = track ?: return
            if (!speaking) {
                // 先把前置静音垫进去，再开声：缓冲里有数据 play() 才不会被立刻判 underrun。
                // 语音紧跟在后面写，中间不会断，所以整段播放期间不会再出现空缓冲。
                writeAllLocked(track, silence(leadInTargetMs))
                startPlaybackLocked(track)
                onFirstSpeechLocked()
                speaking = true
            }
            writeAllLocked(track, stereo, speech = true)
        }
    }

    /**
     * 等已写入的音频真正播完；被新的朗读顶掉时立即返回。
     *
     * 收尾时间按"开声时刻 + 已写入时长"算，不看播放头——播放头在 underrun 之后会归零且不再
     * 推进（见 [playStartUptimeMs]），照它等会永远等不完，用户实测卡过 12 秒。截止时间只在
     * 进来时算一次，后面补的静音不会把它往后推。
     */
    suspend fun drain(token: Int) {
        val endsAt = synchronized(lock) {
            if (token != this.token || !playing) return
            val startedAt = playStartUptimeMs
            // 一块语音都没写进去过（合成失败），没什么可等的。
            if (startedAt == 0L) return
            // 尾巴后面补一小段静音：设备侧从写入到出声还隔着一段延迟，不留余量的话
            // 收尾动作正好卡在最后一个音上。补的是静音，听不出来，但尾音有地方落。
            track?.let { writeAllLocked(it, silence(TAIL_SILENCE_MS)) }
            startedAt + writtenFrames * 1000 / sampleRateHz + DRAIN_SLACK_MS
        }
        while (true) {
            val waitMs = synchronized(lock) {
                if (token != this.token || !playing) return
                val remaining = endsAt - SystemClock.elapsedRealtime()
                if (remaining <= 0) return@synchronized 0L
                // 一路补一小块静音：track 要是在这期间被判 underrun 停掉，只有写入才能把它
                // 重新启动（restartIfDisabled）。最后一块语音之后要是再没有写入，剩下的音频
                // 就烂在缓冲里了——用户实测过整段没声音。静音排在语音后面，听不出来。
                track?.let {
                    runCatching { it.write(pokeSilence, 0, pokeSilence.size, AudioTrack.WRITE_NON_BLOCKING) }
                }
                remaining.coerceIn(MIN_DRAIN_POLL_MS, MAX_DRAIN_POLL_MS)
            }
            if (waitMs == 0L) break
            delay(waitMs)
        }
        finish(token)
    }

    /**
     * 本段播完或不再需要：停住播放头、放掉音频焦点，track 留着给下一段复用。
     *
     * 读完一段之后八成还有下一段（一串单词、一段对话），所以蓝牙路由上从这里开始挂链路。
     * 音频焦点照常放掉：无声数据不需要焦点，一直占着只会让别人的音乐一直被压低。
     */
    fun finish(token: Int) {
        synchronized(lock) {
            if (token != this.token) return
            finishLocked()
            abandonFocusLocked()
            startKeepAliveLocked()
        }
    }

    /**
     * 正常播完的收尾：用 [AudioTrack.stop]，它会把已写入缓冲的数据播完之后才停。
     *
     * 不能用 [stopLocked] 那套 pause + flush——flush 会把还没播的部分直接丢掉。收尾时刻是
     * 按时间估的（见 [drain]），估早一点点很正常，用 flush 就等于把尾音剪了。
     */
    private fun finishLocked() {
        speaking = false
        playStartUptimeMs = 0
        val track = track ?: return
        runCatching { track.stop() }
        if (playing) {
            playing = false
            lastStopUptimeMs = SystemClock.elapsedRealtime()
        }
        writtenFrames = 0
    }

    /**
     * 立刻掐掉声音。
     *
     * 默认是"别念了"的明确信号——页面切走、退到后台都走这里，顺手把链路也放掉，不留着耗电。
     *
     * [keepLink] 用于"这段不要了，但马上还要念下一段"的场合（听力一题接一题就是这样）：
     * 声音照掐，蓝牙链路留着。否则每翻一页都放手，下一句又是冷起播，等于白挂。
     */
    fun stop(keepLink: Boolean = false) {
        synchronized(lock) {
            ++token
            stopLocked()
            abandonFocusLocked()
            if (keepLink) startKeepAliveLocked() else stopKeepAliveLocked()
        }
    }

    fun release() {
        synchronized(lock) {
            if (released) return
            released = true
            ++token
            stopLocked()
            abandonFocusLocked()
            stopKeepAliveLocked()
            runCatching { track?.release() }
            track = null
        }
    }

    /**
     * 暂停并丢掉缓冲里没播的部分。flush 会把播放头归零，写入计数跟着一起清。
     *
     * 挂链路时 [playing] 是 false 但缓冲里压着无声数据，所以无条件清一次，不看 [playing]——
     * 不清掉的话新的一段要排在那些无声数据后面，起播反而更慢。
     * [lastStopUptimeMs] 只记真正的朗读结束时刻，它是空闲计时的起点。
     */
    private fun stopLocked() {
        speaking = false
        playStartUptimeMs = 0
        val track = track ?: return
        runCatching { track.pause() }
        runCatching { track.flush() }
        if (playing) {
            playing = false
            lastStopUptimeMs = SystemClock.elapsedRealtime()
        }
        writtenFrames = 0
    }

    /**
     * 第一块语音到手了，记一笔账——真正的动作（垫静音、开声）在 [write] 里。
     *
     * 等待时长要跟前置静音一起看：等得越久，通路睡得越死，垫的静音就越该管用。
     */
    private fun onFirstSpeechLocked() {
        val waitedMs = SystemClock.elapsedRealtime() - beganAtUptimeMs
        Log.d(TAG, "首块语音等了 ${waitedMs}ms，垫 ${leadInTargetMs}ms 静音后开声")
    }

    /**
     * 开声（幂等），并记下时刻——[drain] 要靠它推算这段什么时候播完。
     *
     * 调用前缓冲里必须已经有数据：对着空缓冲 play() 会被 AudioFlinger 立刻判 underrun
     * 并把 track 停掉，见类注释第 2 条。
     */
    private fun startPlaybackLocked(track: AudioTrack) {
        if (track.playState == AudioTrack.PLAYSTATE_PLAYING) return
        runCatching { track.play() }
        if (playStartUptimeMs == 0L) playStartUptimeMs = SystemClock.elapsedRealtime()
    }

    /**
     * 蓝牙路由上起一个线程，空闲期间不停灌无声数据，把 A2DP 链路挂住。
     *
     * 只对蓝牙做：扬声器和有线本来就几乎不用唤醒，挂着纯属白耗电。
     */
    private fun startKeepAliveLocked() {
        if (released || keepAlive != null || track == null) return
        if (currentRoute() != AudioRoute.Bluetooth) return
        val thread = Thread({ keepAliveLoop() }, "tts-keep-alive").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY
        }
        keepAlive = thread
        thread.start()
    }

    private fun stopKeepAliveLocked() {
        val thread = keepAlive ?: return
        keepAlive = null
        thread.interrupt()
    }

    /**
     * 每轮都在锁里判断该干什么，写完立刻放锁——用 WRITE_NON_BLOCKING 保证不会攥着锁等缓冲。
     *
     * 真正在朗读时这里什么都不做（[playing] 为 true），让 [write] 独占 track；
     * 距离上一段超过 [KEEP_ALIVE_MAX_IDLE_MS]、路由不再是蓝牙、或者被 [stopKeepAliveLocked]
     * 打断，就退出并把自己从 [keepAlive] 摘掉。
     */
    private fun keepAliveLoop() {
        val chunk = keepAliveDither(KEEP_ALIVE_CHUNK_MS * sampleRateHz / 1000)
        val self = Thread.currentThread()
        var routeCheckedAt = SystemClock.elapsedRealtime()
        while (!self.isInterrupted) {
            val sleepMs = synchronized(lock) {
                if (released || keepAlive !== self) return@synchronized EXIT
                if (SystemClock.elapsedRealtime() - lastStopUptimeMs > KEEP_ALIVE_MAX_IDLE_MS) {
                    keepAlive = null
                    return@synchronized EXIT
                }
                // 路由可能中途变了（耳机断开、切回外放），别再对着扬声器空转。
                val now = SystemClock.elapsedRealtime()
                if (now - routeCheckedAt >= ROUTE_RECHECK_MS) {
                    routeCheckedAt = now
                    if (currentRoute() != AudioRoute.Bluetooth) {
                        keepAlive = null
                        return@synchronized EXIT
                    }
                }
                if (playing) return@synchronized KEEP_ALIVE_CHUNK_MS.toLong()
                val track = track ?: run {
                    keepAlive = null
                    return@synchronized EXIT
                }
                if (track.playState != AudioTrack.PLAYSTATE_PLAYING) runCatching { track.play() }
                val written = runCatching {
                    track.write(chunk, 0, chunk.size, AudioTrack.WRITE_NON_BLOCKING)
                }.getOrDefault(0)
                // 缓冲满了就等它播掉一点；写进去了就马上接着写，别让缓冲见底。
                if (written < chunk.size) (KEEP_ALIVE_CHUNK_MS / 2).toLong() else 0L
            }
            if (sleepMs == EXIT) {
                endKeepAliveWrites()
                return
            }
            if (sleepMs > 0) {
                try {
                    Thread.sleep(sleepMs)
                } catch (e: InterruptedException) {
                    self.interrupt()
                }
            }
        }
        endKeepAliveWrites()
    }

    /**
     * 不再挂链路了：停住播放头，并且把缓冲里剩下的无声数据丢掉——留着的话下一段朗读
     * 得排在它们后面才出声，起播反而更慢。正在朗读时不能碰，那是 [write] 的数据。
     */
    private fun endKeepAliveWrites() {
        synchronized(lock) {
            if (playing) return
            val track = track ?: return
            runCatching { track.pause() }
            runCatching { track.flush() }
        }
    }

    private fun silence(durationMs: Int): ByteArray =
        ByteArray(durationMs * sampleRateHz / 1000 * BYTES_PER_FRAME)

    private fun isColdStart(): Boolean =
        track == null || SystemClock.elapsedRealtime() - lastStopUptimeMs > WARM_WINDOW_MS

    /** playbackHeadPosition 是会回绕的 32 位帧计数；一段朗读远到不了回绕点。 */
    private fun playedFramesLocked(): Long =
        (track?.playbackHeadPosition?.toLong() ?: 0L) and 0xFFFFFFFFL

    private fun writeAllLocked(track: AudioTrack, data: ByteArray, speech: Boolean = false) {
        var offset = 0
        while (offset < data.size) {
            val written = track.write(data, offset, data.size - offset)
            // 写不进去就只能停手，但绝不能停得无声无息：剩下的音频会被直接丢掉，
            // 而 drain 只认 writtenFrames，会以为这段已经播完，表现就是话说到一半没了。
            if (written <= 0) {
                Log.w(
                    TAG,
                    "AudioTrack 写入中断：ret=$written 丢了 ${(data.size - offset) / BYTES_PER_FRAME * 1000 / sampleRateHz}ms",
                )
                break
            }
            offset += written
        }
        val frames = offset / BYTES_PER_FRAME
        writtenFrames += frames
        // 只有语音计入 spokenFrames：它是拿来跟"收到多少音频"对账的，垫的静音不算数。
        if (speech) spokenFrames += frames
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

    /**
     * 声音当前实际走哪儿。
     *
     * 不能只看 `getDevices(GET_DEVICES_OUTPUTS)`——它列的是所有连着的输出设备，蓝牙耳机连着、
     * 声音却走外放时也会判成蓝牙，于是白垫 800ms 静音，还让 [keepAliveLoop] 对着扬声器空转
     * （用户实测：外放播放，log 里是 AUDIO_DEVICE_OUT_SPEAKER）。track 建起来之后
     * routedDevice 才是真正在出声的那个；还没建就只能先按连接情况估。
     */
    private fun currentRoute(): AudioRoute {
        track?.routedDevice?.let { return routeOf(listOf(it.type)) }
        return routeOf(audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS).orEmpty().map { it.type })
    }

    private fun routeOf(types: List<Int>): AudioRoute = when {
        types.any { it in BLUETOOTH_TYPES } -> AudioRoute.Bluetooth
        types.any { it in WIRED_TYPES } -> AudioRoute.Wired
        else -> AudioRoute.Speaker
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
        const val TAG = "AzureSpeech"
        const val BYTES_PER_FRAME = 4 // 立体声 × 16bit
        const val BUFFER_MS = 400
        const val MIN_DRAIN_POLL_MS = 5L
        const val MAX_DRAIN_POLL_MS = 60L

        /** 按时间推算收尾时多留的一点余量：写进 AudioTrack 到真正出声还隔着一段设备延迟。 */
        const val DRAIN_SLACK_MS = 120L

        /** 上一段刚播完不久就认为通路还热着，不用再多垫静音。 */
        const val WARM_WINDOW_MS = 3_000L

        /** 挂链路的写入粒度。太大起播时要多等一块，太小线程醒得太频繁。 */
        const val KEEP_ALIVE_CHUNK_MS = 20

        /** 一直没人朗读就放手：射频不休眠是有电量代价的，不能无限挂着。 */
        const val KEEP_ALIVE_MAX_IDLE_MS = 90_000L

        /** 挂着的时候多久确认一次输出还在蓝牙上（getDevices 不便宜，别每块都查）。 */
        const val ROUTE_RECHECK_MS = 1_000L

        /** [keepAliveLoop] 里表示"该退出了"的哨兵，正常路径只会返回 >= 0 的等待毫秒。 */
        const val EXIT = -1L

        /** 收尾期间反复补的静音块，只为让写入不断，把可能被停掉的 track 拉回来，见 [drain]。 */
        const val POKE_SILENCE_MS = 20

        /** 语音末尾补的静音，给设备输出延迟留出余量，免得尾音正好卡在收尾那一刻。 */
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

/**
 * 链路一直被挂着时的前置静音：通路根本没睡过，只需要吸收 pause+flush 后重新开声的抖动。
 * 这是"长连接"真正省下来的时间——蓝牙从近一秒降到这个数。
 */
internal const val KEPT_ALIVE_LEAD_IN_MS = 150

internal fun leadInSilenceMs(route: AudioRoute, coldStart: Boolean, keptAlive: Boolean = false): Int =
    if (keptAlive) KEPT_ALIVE_LEAD_IN_MS
    else route.leadInSilenceMs + if (coldStart) COLD_START_EXTRA_MS else 0

/**
 * 挂链路用的立体声数据块：不是纯零，而是在最低位上正负交替。
 *
 * 幅度 1/32768 约合 -90dBFS，人耳听不到；但有些耳机自带静音检测，收到一长串纯零会
 * 自己把功放降下去，等于白挂。给一点点抖动可以让它一直认为流里有内容。
 */
internal fun keepAliveDither(frames: Int): ByteArray {
    val bytes = ByteArray(frames * 4)
    var i = 0
    var positive = true
    while (i + 4 <= bytes.size) {
        // 小端 16bit：+1 是 0x0001，-1 是 0xFFFF。高位字节必须一起写，
        // 只改低位会得到 255（约 -42dBFS），那就成了听得见的嘶声。
        val low: Byte = if (positive) 1 else -1
        val high: Byte = if (positive) 0 else -1
        bytes[i] = low
        bytes[i + 1] = high
        bytes[i + 2] = low
        bytes[i + 3] = high
        positive = !positive
        i += 4
    }
    return bytes
}

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
