package com.lazydog.english.core.speech

import android.content.Context
import com.lazydog.english.core.data.UserPreferences
import com.lazydog.english.domain.speaking.AssessmentResult
import com.lazydog.english.domain.speaking.SpeakResult
import com.lazydog.english.domain.speaking.SpeechProvider
import com.lazydog.english.domain.speaking.TranscriptionResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 应用级共享的朗读入口：懒加载底层 provider，播放意图统一交给 [PlaybackController]。
 *
 * 单词卡、知识库详情、拼写、听力和朗读练习都走这里，避免每个页面各建一套 SDK 资源，
 * 也避免每个按钮各存一份"在不在播"（`语音服务DESIGN.md` §23、§37.6）。
 */
class SpeechController(context: Context, private val prefs: UserPreferences) {

    /** 播放要按当前输出设备（扬声器/耳机/蓝牙）调整起播静音，所以底层需要 Context。 */
    private val appContext = context.applicationContext

    private val mutex = Mutex()

    @Volatile
    private var provider: SpeechProvider? = null

    /** 播放状态机活在应用级作用域上：页面来去、协程取消都不该把正在播的一段带走。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val playbackController = PlaybackController(scope, ProviderPlayback())

    /**
     * 全局唯一的播放状态。播放按钮用 [PlaybackState.statusOf] 按自己的 sourceId 取状态，
     * 不要自己存 `isPlaying`。
     */
    val playback: StateFlow<PlaybackState> = playbackController.state

    private suspend fun provider(): SpeechProvider = mutex.withLock {
        provider ?: AzureSpeechProvider(
            context = appContext,
            subscriptionKey = prefs.speechKey.first(),
            region = prefs.speechRegion.first(),
        ).also { provider = it }
    }

    /**
     * 播放按钮被点了：没在播就播，正在播同一段就停，正在播别的就立刻换成这一段。
     * 界面只管调它，不用自己判断当前在播什么（`语音服务DESIGN.md` §13）。
     */
    fun onPlayClicked(source: PlaybackSource) = playbackController.onPlayClicked(source)

    /** 直接播这一段，不做"再点一次 = 停"。进卡片自动朗读、听力自动放音走这里。 */
    fun play(source: PlaybackSource) = playbackController.play(source)

    /**
     * 停掉正在播的朗读。页面切走、退到后台、弹窗关掉时调用——人已经走了就不该还在念。
     * 还没用过朗读就不会为了停而去初始化 SDK。
     *
     * [keepLink] 用于"人没走，只是这段不要了、马上还要念下一段"：一题接一题的听力训练
     * 就是这样。不区分的话每翻一页都会把刚热起来的蓝牙链路放掉，下一句又从冷通路起播。
     */
    fun stop(keepLink: Boolean = false) = playbackController.stop(keepLink)

    /**
     * 录音前先把朗读掐掉（`语音服务DESIGN.md` §28 半双工）：这一版不做边放边收，
     * 扬声器还在响的时候开麦只会把示范音自己录进去。
     *
     * 除了走状态机，还直接掐一次底层：事件是排队处理的，而麦克风下一行就要开。
     */
    private fun haltForRecording() {
        playbackController.stop()
        provider?.stopSpeaking(keepLink = false)
    }

    suspend fun assessReading(referenceText: String): AssessmentResult {
        haltForRecording()
        return provider().assessReading(referenceText)
    }

    suspend fun transcribeOnce(languages: List<String> = listOf("en-US")): TranscriptionResult {
        haltForRecording()
        return provider().transcribeOnce(languages)
    }

    suspend fun transcribeContinuously(
        languages: List<String> = listOf("en-US"),
        onPartial: (String) -> Unit,
    ): TranscriptionResult {
        haltForRecording()
        return provider().transcribeContinuously(languages, onPartial)
    }

    fun stopTranscribing() {
        provider?.stopTranscribing()
    }

    /** 把播放状态机接到真正的 Azure provider 上。 */
    private inner class ProviderPlayback : TtsPlayback {

        override suspend fun speak(job: PlaybackJob, onStarted: () -> Unit): SpeakResult =
            provider().speak(
                text = job.source.text,
                rate = job.source.rate ?: prefs.speechRate.first(),
                voiceName = prefs.ttsVoice.first(),
                style = job.source.style,
                onPlaybackStarted = onStarted,
            )

        override fun stop(keepLink: Boolean) {
            provider?.stopSpeaking(keepLink)
        }
    }
}
