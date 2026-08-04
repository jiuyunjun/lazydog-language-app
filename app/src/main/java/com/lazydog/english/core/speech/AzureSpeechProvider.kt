package com.lazydog.english.core.speech

import com.lazydog.english.domain.speaking.AssessmentResult
import com.lazydog.english.domain.speaking.SpeakResult
import com.lazydog.english.domain.speaking.SpeechProvider
import com.lazydog.english.domain.speaking.SpeechRate
import com.microsoft.cognitiveservices.speech.CancellationDetails
import com.microsoft.cognitiveservices.speech.PropertyId
import com.microsoft.cognitiveservices.speech.PronunciationAssessmentConfig
import com.microsoft.cognitiveservices.speech.PronunciationAssessmentGradingSystem
import com.microsoft.cognitiveservices.speech.PronunciationAssessmentGranularity
import com.microsoft.cognitiveservices.speech.ResultReason
import com.microsoft.cognitiveservices.speech.SpeechConfig
import com.microsoft.cognitiveservices.speech.SpeechRecognitionResult
import com.microsoft.cognitiveservices.speech.SpeechRecognizer
import com.microsoft.cognitiveservices.speech.SpeechSynthesisCancellationDetails
import com.microsoft.cognitiveservices.speech.SpeechSynthesizer
import com.microsoft.cognitiveservices.speech.audio.AudioConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Azure Speech SDK 的 [SpeechProvider] 实现。
 * SDK 的 Future 都在 IO 线程上等待；错误信息不含密钥，可直接展示。
 */
class AzureSpeechProvider(
    subscriptionKey: String,
    region: String,
    private val voiceName: String = "en-US-JennyNeural",
) : SpeechProvider {

    private val speechConfig: SpeechConfig =
        SpeechConfig.fromSubscription(subscriptionKey, region).apply {
            speechRecognitionLanguage = "en-US"
            speechSynthesisVoiceName = voiceName
            // 读完停顿约 0.8 秒就收音，默认值等太久（用户实测反馈）。
            setProperty(PropertyId.Speech_SegmentationSilenceTimeoutMs, "800")
        }

    private val synthesizer: SpeechSynthesizer by lazy { SpeechSynthesizer(speechConfig) }

    @Volatile
    private var closed = false

    override suspend fun speak(text: String, rate: SpeechRate): SpeakResult = withContext(Dispatchers.IO) {
        if (closed) return@withContext SpeakResult.Failed("服务已释放")
        var result: com.microsoft.cognitiveservices.speech.SpeechSynthesisResult? = null
        try {
            result = synthesizer.SpeakSsmlAsync(buildSpeechSsml(text, voiceName, rate)).get()
            when (result.reason) {
                ResultReason.SynthesizingAudioCompleted -> SpeakResult.Done
                ResultReason.Canceled -> {
                    val details = SpeechSynthesisCancellationDetails.fromResult(result)
                    SpeakResult.Failed("示范音频失败：${details.errorDetails ?: details.reason}")
                }
                else -> SpeakResult.Failed("示范音频失败：${result.reason}")
            }
        } catch (e: Exception) {
            SpeakResult.Failed("示范音频失败：${e.message ?: e.javaClass.simpleName}")
        } finally {
            runCatching { result?.close() }
        }
    }

    override suspend fun assessReading(referenceText: String): AssessmentResult =
        withContext(Dispatchers.IO) {
            if (closed) return@withContext AssessmentResult.Failed("服务已释放")
            var audioConfig: AudioConfig? = null
            var recognizer: SpeechRecognizer? = null
            var assessmentConfig: PronunciationAssessmentConfig? = null
            var result: SpeechRecognitionResult? = null
            try {
                audioConfig = AudioConfig.fromDefaultMicrophoneInput()
                recognizer = SpeechRecognizer(speechConfig, audioConfig)
                assessmentConfig = PronunciationAssessmentConfig(
                    referenceText,
                    PronunciationAssessmentGradingSystem.HundredMark,
                    PronunciationAssessmentGranularity.Word,
                    /* enableMiscue = */ true,
                )
                assessmentConfig.applyTo(recognizer)
                result = recognizer.recognizeOnceAsync().get()
                toAssessmentResult(result)
            } catch (e: Exception) {
                AssessmentResult.Failed("录音评估失败：${e.message ?: e.javaClass.simpleName}")
            } finally {
                runCatching { result?.close() }
                runCatching { assessmentConfig?.close() }
                runCatching { recognizer?.close() }
                runCatching { audioConfig?.close() }
            }
        }

    private fun toAssessmentResult(result: SpeechRecognitionResult): AssessmentResult =
        when (result.reason) {
            ResultReason.RecognizedSpeech -> {
                val raw = result.properties.getProperty(PropertyId.SpeechServiceResponse_JsonResult)
                val feedback = raw?.let(PronunciationJson::parse)
                if (feedback != null) AssessmentResult.Done(feedback)
                else AssessmentResult.Failed("拿到了识别结果，但没有发音评估数据")
            }
            ResultReason.NoMatch -> AssessmentResult.NothingRecognized
            ResultReason.Canceled -> {
                val details = CancellationDetails.fromResult(result)
                AssessmentResult.Failed("识别中断：${details.errorDetails ?: details.reason}")
            }
            else -> AssessmentResult.Failed("识别失败：${result.reason}")
        }

    override fun close() {
        closed = true
        runCatching { synthesizer.close() }
        runCatching { speechConfig.close() }
    }
}
