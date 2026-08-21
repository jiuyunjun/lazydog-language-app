package com.lazydog.english.core.speech

import android.content.Context
import com.lazydog.english.core.data.UserPreferences
import com.lazydog.english.domain.speaking.AssessmentResult
import com.lazydog.english.domain.speaking.SpeakResult
import com.lazydog.english.domain.speaking.SpeechProvider
import com.lazydog.english.domain.speaking.SpeechStyle
import com.lazydog.english.domain.speaking.TranscriptionResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 应用级共享的朗读入口：懒加载底层 provider，speak 自动应用用户设置的语速。
 * 单词卡、知识库详情和朗读练习都走这里，避免每个页面各建一套 SDK 资源。
 */
class SpeechController(context: Context, private val prefs: UserPreferences) {

    /** 播放要按当前输出设备（扬声器/耳机/蓝牙）调整起播静音，所以底层需要 Context。 */
    private val appContext = context.applicationContext

    private val mutex = Mutex()

    @Volatile
    private var provider: SpeechProvider? = null

    private suspend fun provider(): SpeechProvider = mutex.withLock {
        provider ?: AzureSpeechProvider(
            context = appContext,
            subscriptionKey = prefs.speechKey.first(),
            region = prefs.speechRegion.first(),
        ).also { provider = it }
    }

    /** 用当前语速和音色朗读英文；会打断上一次播放。失败静默返回结果，由调用方决定是否展示。 */
    suspend fun speak(text: String): SpeakResult = speak(text, SpeechStyle.Sentence)

    /** 报一个单词或搭配：用播音腔，平稳清楚，不带句子的语调（见 [SpeechStyle]）。 */
    suspend fun speakWord(text: String): SpeakResult = speak(text, SpeechStyle.Word)

    private suspend fun speak(text: String, style: SpeechStyle): SpeakResult =
        provider().speak(text, prefs.speechRate.first(), prefs.ttsVoice.first(), style)

    /**
     * 停掉正在播的朗读。页面切走、退到后台、弹窗关掉时调用——人已经走了就不该还在念。
     * 还没用过朗读就不会为了停而去初始化 SDK。
     */
    fun stopSpeaking() {
        provider?.stopSpeaking()
    }

    suspend fun assessReading(referenceText: String): AssessmentResult =
        provider().assessReading(referenceText)

    suspend fun transcribeOnce(): TranscriptionResult = provider().transcribeOnce()
}
