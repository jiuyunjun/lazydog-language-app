package com.lazydog.english.core.speech

import android.content.Context
import com.lazydog.english.domain.speaking.AssessmentResult
import com.lazydog.english.domain.speaking.SpeakResult
import com.lazydog.english.domain.speaking.SpeechProvider
import com.lazydog.english.domain.speaking.SpeechRate
import com.lazydog.english.domain.speaking.SpeechStyle
import com.lazydog.english.domain.speaking.TranscriptionResult
import com.microsoft.cognitiveservices.speech.AudioDataStream
import com.microsoft.cognitiveservices.speech.AutoDetectSourceLanguageConfig
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
import com.microsoft.cognitiveservices.speech.SpeechSynthesisOutputFormat
import com.microsoft.cognitiveservices.speech.SpeechSynthesisResult
import com.microsoft.cognitiveservices.speech.SpeechSynthesizer
import com.microsoft.cognitiveservices.speech.StreamStatus
import com.microsoft.cognitiveservices.speech.audio.AudioConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Azure Speech SDK 的 [SpeechProvider] 实现。
 * SDK 的 Future 都在 IO 线程上等待；错误信息不含密钥，可直接展示。
 */
class AzureSpeechProvider(
    context: Context,
    subscriptionKey: String,
    region: String,
    private val voiceName: String = "en-US-Ava:DragonHDLatestNeural",
) : SpeechProvider {

    private val speechConfig: SpeechConfig =
        SpeechConfig.fromSubscription(subscriptionKey, region).apply {
            speechRecognitionLanguage = "en-US"
            speechSynthesisVoiceName = voiceName
            // 音频由 PcmAudioPlayer 自己播，要的是裸 PCM，不是带容器的格式。
            setSpeechSynthesisOutputFormat(SpeechSynthesisOutputFormat.Raw24Khz16BitMonoPcm)
            // 读完停顿约 0.8 秒就收音，默认值等太久（用户实测反馈）。
            setProperty(PropertyId.Speech_SegmentationSilenceTimeoutMs, "800")
        }

    /** AudioConfig 传 null = 不让 SDK 自己放音，音频从 AudioDataStream 取（见 [PcmAudioPlayer]）。 */
    private val synthesizer: SpeechSynthesizer by lazy {
        SpeechSynthesizer(speechConfig, null as AudioConfig?)
    }

    /** 全程复用同一个播放器（AudioTrack 只建一次，见 [PcmAudioPlayer]）。 */
    private val player = PcmAudioPlayer(context, SYNTHESIS_SAMPLE_RATE_HZ)

    /** 有没有还没读完的合成流。 */
    @Volatile
    private var synthesizing = false

    @Volatile
    private var closed = false

    override suspend fun speak(
        text: String,
        rate: SpeechRate,
        voiceName: String?,
        style: SpeechStyle,
    ): SpeakResult = withContext(Dispatchers.IO) {
        if (closed) return@withContext SpeakResult.Failed("服务已释放")
        val ssml = buildSpeechSsml(text, voiceName ?: this@AzureSpeechProvider.voiceName, rate, style)
        // 打断正在播放的内容——点了新的就读新的，不排队。先掐声音，再停合成。
        val token = player.begin()
        // 只有确实还有合成在跑才去停——空转时这个调用也要等 SDK 一个来回，白白拖慢起播。
        if (synthesizing) runCatching { synthesizer.StopSpeakingAsync().get() }

        var result: SpeechSynthesisResult? = null
        var stream: AudioDataStream? = null
        try {
            // StartSpeaking 只等到合成开始；音频边到边播，长句不用等整段合成完。
            synthesizing = true
            result = synthesizer.StartSpeakingSsmlAsync(ssml).get()
            if (result.reason == ResultReason.Canceled) {
                val details = SpeechSynthesisCancellationDetails.fromResult(result)
                return@withContext SpeakResult.Failed("示范音频失败：${details.errorDetails ?: details.reason}")
            }
            stream = AudioDataStream.fromResult(result)

            val buffer = ByteArray(CHUNK_BYTES)
            while (true) {
                val read = stream.readData(buffer).toInt()
                if (read <= 0) break
                // 被下一次朗读顶掉了，剩下的音频没人要了。
                if (!player.isCurrent(token)) return@withContext SpeakResult.Done
                player.write(token, buffer, read)
            }
            when (stream.status) {
                StreamStatus.AllData, StreamStatus.PartialData -> {
                    player.drain(token)
                    SpeakResult.Done
                }
                StreamStatus.Canceled -> {
                    val details = SpeechSynthesisCancellationDetails.fromStream(stream)
                    SpeakResult.Failed("示范音频失败：${details.errorDetails ?: details.reason}")
                }
                else -> SpeakResult.Failed("示范音频失败：没拿到音频数据")
            }
        } catch (e: Exception) {
            SpeakResult.Failed("示范音频失败：${e.message ?: e.javaClass.simpleName}")
        } finally {
            // 流读完了，这次合成就算收工——下次朗读不用再花一个来回去停它。
            if (player.isCurrent(token)) synthesizing = false
            // 正常播完的收尾在 drain 里做过了；异常和提前返回靠这里把播放头和音频焦点放掉。
            player.finish(token)
            runCatching { stream?.close() }
            runCatching { result?.close() }
        }
    }

    override fun stopSpeaking(keepLink: Boolean) {
        if (closed) return
        // 先掐声音（立刻静），再停合成（要等 SDK 一个来回）。
        player.stop(keepLink)
        if (synthesizing) {
            synthesizing = false
            runCatching { synthesizer.StopSpeakingAsync() }
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

    override suspend fun transcribeOnce(languages: List<String>): TranscriptionResult = withContext(Dispatchers.IO) {
        if (closed) return@withContext TranscriptionResult.Failed("服务已释放")
        var audioConfig: AudioConfig? = null
        var languageConfig: AutoDetectSourceLanguageConfig? = null
        var recognizer: SpeechRecognizer? = null
        var result: SpeechRecognitionResult? = null
        try {
            audioConfig = AudioConfig.fromDefaultMicrophoneInput()
            val candidates = languages.map(String::trim).filter(String::isNotBlank).distinct()
                .ifEmpty { listOf("en-US") }
            recognizer = if (candidates.size == 1) {
                SpeechRecognizer(speechConfig, candidates.single(), audioConfig)
            } else {
                languageConfig = AutoDetectSourceLanguageConfig.fromLanguages(candidates)
                SpeechRecognizer(speechConfig, languageConfig, audioConfig)
            }
            result = recognizer.recognizeOnceAsync().get()
            when (result.reason) {
                ResultReason.RecognizedSpeech -> result.text?.trim()?.takeIf { it.isNotBlank() }
                    ?.let(TranscriptionResult::Done) ?: TranscriptionResult.NothingRecognized
                ResultReason.NoMatch -> TranscriptionResult.NothingRecognized
                ResultReason.Canceled -> {
                    val details = CancellationDetails.fromResult(result)
                    TranscriptionResult.Failed("听写中断：${details.errorDetails ?: details.reason}")
                }
                else -> TranscriptionResult.Failed("听写失败：${result.reason}")
            }
        } catch (e: Exception) {
            TranscriptionResult.Failed("听写失败：${e.message ?: e.javaClass.simpleName}")
        } finally {
            runCatching { result?.close() }
            runCatching { recognizer?.close() }
            runCatching { languageConfig?.close() }
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
        player.release()
        runCatching { synthesizer.close() }
        runCatching { speechConfig.close() }
    }

    private companion object {
        /** 跟 [SpeechSynthesisOutputFormat.Raw24Khz16BitMonoPcm] 对应，改格式时要一起改。 */
        const val SYNTHESIS_SAMPLE_RATE_HZ = 24_000

        /** 每次从合成流里取的字节数，约 33ms 音频——够小，起播不会被凑整块拖慢。 */
        const val CHUNK_BYTES = 1600
    }
}
