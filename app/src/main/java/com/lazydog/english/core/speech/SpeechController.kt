package com.lazydog.english.core.speech

import com.lazydog.english.core.data.UserPreferences
import com.lazydog.english.domain.speaking.AssessmentResult
import com.lazydog.english.domain.speaking.SpeakResult
import com.lazydog.english.domain.speaking.SpeechProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 应用级共享的朗读入口：懒加载底层 provider，speak 自动应用用户设置的语速。
 * 单词卡、知识库详情和朗读练习都走这里，避免每个页面各建一套 SDK 资源。
 */
class SpeechController(private val prefs: UserPreferences) {

    private val mutex = Mutex()
    private var provider: SpeechProvider? = null

    private suspend fun provider(): SpeechProvider = mutex.withLock {
        provider ?: AzureSpeechProvider(
            subscriptionKey = prefs.speechKey.first(),
            region = prefs.speechRegion.first(),
        ).also { provider = it }
    }

    /** 用当前语速和音色朗读英文；会打断上一次播放。失败静默返回结果，由调用方决定是否展示。 */
    suspend fun speak(text: String): SpeakResult =
        provider().speak(text, prefs.speechRate.first(), prefs.ttsVoice.first())

    suspend fun assessReading(referenceText: String): AssessmentResult =
        provider().assessReading(referenceText)
}
