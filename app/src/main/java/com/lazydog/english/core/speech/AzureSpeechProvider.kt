package com.lazydog.english.core.speech

import android.content.Context
import android.os.SystemClock
import android.util.Log
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Azure Speech SDK 的 [SpeechProvider] 实现。
 * SDK 的 Future 都在 IO 线程上等待；错误信息不含密钥，可直接展示。
 */
class AzureSpeechProvider(
    context: Context,
    private val subscriptionKey: String,
    private val region: String,
    private val voiceName: String = "en-US-Ava:DragonHDLatestNeural",
) : SpeechProvider {

    private val speechConfig: SpeechConfig =
        SpeechConfig.fromSubscription(subscriptionKey, region).apply {
            speechSynthesisVoiceName = voiceName
            // 音频由 PcmAudioPlayer 自己播，要的是裸 PCM，不是带容器的格式。
            setSpeechSynthesisOutputFormat(SpeechSynthesisOutputFormat.Raw24Khz16BitMonoPcm)
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

    @Volatile
    private var activeTranscriber: SpeechRecognizer? = null

    private val stopTranscriptionRequested = AtomicBoolean(false)

    /**
     * 一次只放一段朗读进来。
     *
     * [synthesizer] 和 [player] 都是共享的，两段朗读叠在一起会互相踩：后一段的
     * StopSpeakingAsync 可能落到它自己的 StartSpeaking 之后，把自己的合成取消掉；
     * AudioDataStream 也会读到上一段残留在 SDK 缓冲里的音频——用户实测就是这个现象，
     * 单词（短、音频一次到齐）点了没声音，例句（长、边收边播）反而正常。
     */
    private val speakMutex = Mutex()

    /** 给每次朗读编号：一次点击到底进来几次 speak、最后是哪一次出的声，只有编号能对上。 */
    private val speakSeq = AtomicInteger()

    override suspend fun speak(
        text: String,
        rate: SpeechRate,
        voiceName: String?,
        style: SpeechStyle,
        onPlaybackStarted: () -> Unit,
    ): SpeakResult {
        if (closed) return SpeakResult.Failed("服务已释放")
        // 退回音色重念时不能再报一次"开始播了"：界面只认第一次，多报会让状态白跳一下。
        val started = AtomicBoolean(false)
        val notifyStarted = { if (started.compareAndSet(false, true)) onPlaybackStarted() }
        val id = speakSeq.incrementAndGet()
        Log.d(TAG, "请求朗读 #$id：style=$style text=$text")
        val configured = voiceName ?: this.voiceName
        // 单词用播音腔、变速的句子也得换音色（HD 忽略 prosody），理由见 [voiceFor]。
        val voice = voiceFor(configured, style, rate)
        // 抢锁之前先掐掉上一段：上一段的读循环看到令牌失效会立刻收工把锁让出来，
        // 不然它会攥着锁把整段音频读完，新的朗读得干等。
        player.stop(keepLink = true)
        val result = speakMutex.withLock {
            speakExclusive(id, buildSpeechSsml(text, voice, rate, style), text, voice, "$style", notifyStarted)
        }
        if (result !is SpeakResult.Failed || voice == configured) return result

        // 换过的音色合不出来（这条连接绑在 HD 后端上，历史上怀疑过这种情况）：
        // 宁可没有播音腔，也不能让用户点了没声音。退回他自己选的音色再念一遍，并留下日志。
        val retryId = speakSeq.incrementAndGet()
        Log.w(TAG, "#$retryId：$voice 合不出音频，退回 $configured 再念一遍 text=$text")
        player.stop(keepLink = true)
        return speakMutex.withLock {
            speakExclusive(retryId, buildSpeechSsml(text, configured, rate, style), text, configured, "$style", notifyStarted)
        }
    }

    private suspend fun speakExclusive(
        id: Int,
        ssml: String,
        text: String,
        voice: String,
        style: String,
        onPlaybackStarted: () -> Unit,
    ): SpeakResult = withContext(Dispatchers.IO) {
        if (closed) return@withContext SpeakResult.Failed("服务已释放")
        // 打断正在播放的内容——点了新的就读新的，不排队。先掐声音，再停合成。
        val token = player.begin()
        // 只有确实还有合成在跑才去停——空转时这个调用也要等 SDK 一个来回，白白拖慢起播。
        if (synthesizing) runCatching { synthesizer.StopSpeakingAsync().get() }

        var result: SpeechSynthesisResult? = null
        var stream: AudioDataStream? = null
        val startedAt = SystemClock.elapsedRealtime()
        try {
            // StartSpeaking 只等到合成开始；音频边到边播，长句不用等整段合成完。
            synthesizing = true
            result = synthesizer.StartSpeakingSsmlAsync(ssml).get()
            if (result.reason == ResultReason.Canceled) {
                val details = SpeechSynthesisCancellationDetails.fromResult(result)
                val reason = "${details.errorDetails ?: details.reason}"
                Log.w(TAG, "#$id 合成没能开始：voice=$voice style=$style text=$text reason=$reason")
                return@withContext SpeakResult.Failed("示范音频失败：$reason")
            }
            stream = AudioDataStream.fromResult(result)

            var received = 0L
            val buffer = ByteArray(CHUNK_BYTES)
            while (true) {
                val read = stream.readData(buffer).toInt()
                if (read <= 0) break
                // 被下一次朗读顶掉了，剩下的音频没人要了。
                if (!player.isCurrent(token)) {
                    // 静默返回过一版，结果就是"点了没声音"在日志里什么都不留。
                    Log.w(TAG, "#$id 被后一次朗读顶掉：已收到 ${received / BYTES_PER_MS_MONO}ms text=$text")
                    return@withContext SpeakResult.Done
                }
                // 首块音频到手的时刻是关键：这之前播放器只能靠补静音把通路撑住。
                if (received == 0L) {
                    Log.d(TAG, "#$id 首块音频：${SystemClock.elapsedRealtime() - startedAt}ms")
                }
                received += read
                player.write(token, buffer, read)
                // 第一块语音写进去的同时播放器才垫静音开声（D-039 第 1 条），所以这一刻
                // 就是真正出声的时刻，界面从这里开始显示"正在播"（`语音服务DESIGN.md` §21）。
                if (received == read.toLong()) onPlaybackStarted()
            }
            when (stream.status) {
                StreamStatus.AllData, StreamStatus.PartialData -> {
                    player.drain(token)
                    // 流"正常"结束但一个字节都没有：SSML 里的音色在这条连接上合不出来就是这样，
                    // 调用方大多不看返回值，不记一笔的话表现就是点了没声音、哪儿都不报错。
                    if (received == 0L) {
                        Log.w(TAG, "#$id 合成流没有音频：voice=$voice style=$style text=$text")
                        SpeakResult.Failed("示范音频失败：没拿到音频数据")
                    } else {
                        // 收到多少 vs 真正灌进 AudioTrack 多少：两个数对不上就是播放侧丢了音频，
                        // 而不是 Azure 没给。差值只有在这里能看出来，别的地方都是静默的。
                        Log.d(
                            TAG,
                            "朗读完成 #$id：style=$style 收到 ${received / BYTES_PER_MS_MONO}ms" +
                                "、写入 ${player.lastWrittenMs()}ms" +
                                "、共 ${SystemClock.elapsedRealtime() - startedAt}ms text=$text",
                        )
                        SpeakResult.Done
                    }
                }
                StreamStatus.Canceled -> {
                    val details = SpeechSynthesisCancellationDetails.fromStream(stream)
                    val reason = "${details.errorDetails ?: details.reason}"
                    Log.w(TAG, "#$id 合成被取消：voice=$voice style=$style text=$text reason=$reason")
                    SpeakResult.Failed("示范音频失败：$reason")
                }
                else -> {
                    Log.w(TAG, "#$id 合成没出音频：status=${stream.status} voice=$voice style=$style text=$text")
                    SpeakResult.Failed("示范音频失败：没拿到音频数据")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "#$id 合成异常：voice=$voice style=$style text=$text", e)
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
            var recognitionConfig: SpeechConfig? = null
            var recognizer: SpeechRecognizer? = null
            var assessmentConfig: PronunciationAssessmentConfig? = null
            var result: SpeechRecognitionResult? = null
            try {
                audioConfig = AudioConfig.fromDefaultMicrophoneInput()
                recognitionConfig = newRecognitionConfig("en-US")
                recognizer = SpeechRecognizer(recognitionConfig, audioConfig)
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
                runCatching { recognitionConfig?.close() }
                runCatching { audioConfig?.close() }
            }
        }

    override suspend fun transcribeOnce(languages: List<String>): TranscriptionResult = withContext(Dispatchers.IO) {
        if (closed) return@withContext TranscriptionResult.Failed("服务已释放")
        var audioConfig: AudioConfig? = null
        var recognitionConfig: SpeechConfig? = null
        var languageConfig: AutoDetectSourceLanguageConfig? = null
        var recognizer: SpeechRecognizer? = null
        var result: SpeechRecognitionResult? = null
        try {
            audioConfig = AudioConfig.fromDefaultMicrophoneInput()
            val candidates = languages.map(String::trim).filter(String::isNotBlank).distinct()
                .ifEmpty { listOf("en-US") }
            recognitionConfig = newRecognitionConfig(candidates.first())
            recognizer = if (candidates.size == 1) {
                SpeechRecognizer(recognitionConfig, audioConfig)
            } else {
                languageConfig = AutoDetectSourceLanguageConfig.fromLanguages(candidates)
                SpeechRecognizer(recognitionConfig, languageConfig, audioConfig)
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
            runCatching { recognitionConfig?.close() }
            runCatching { audioConfig?.close() }
        }
    }

    override suspend fun transcribeContinuously(
        languages: List<String>,
        onPartial: (String) -> Unit,
    ): TranscriptionResult {
        // 在切到 IO 线程之前清掉上一轮标志，保证 UI 紧接着点“停止”不会被后台初始化覆盖。
        stopTranscriptionRequested.set(false)
        return withContext(Dispatchers.IO) {
            if (closed) return@withContext TranscriptionResult.Failed("服务已释放")
            var audioConfig: AudioConfig? = null
            var recognitionConfig: SpeechConfig? = null
            var languageConfig: AutoDetectSourceLanguageConfig? = null
            var recognizer: SpeechRecognizer? = null
            val completed = CompletableDeferred<TranscriptionResult?>()
            val transcript = StreamingTranscript()
            val autoStopRequested = AtomicBoolean(false)
            try {
                audioConfig = AudioConfig.fromDefaultMicrophoneInput()
                val candidates = languages.map(String::trim).filter(String::isNotBlank).distinct()
                    .ifEmpty { listOf("en-US") }
                recognitionConfig = newRecognitionConfig(candidates.first())
                recognizer = if (candidates.size == 1) {
                    SpeechRecognizer(recognitionConfig, audioConfig)
                } else {
                    languageConfig = AutoDetectSourceLanguageConfig.fromLanguages(candidates)
                    SpeechRecognizer(recognitionConfig, languageConfig, audioConfig)
                }

                recognizer.recognizing.addEventListener { _, event ->
                    transcript.preview(event.result.text.orEmpty()).takeIf(String::isNotBlank)?.let(onPartial)
                }
                recognizer.recognized.addEventListener { _, event ->
                    if (event.result.reason == ResultReason.RecognizedSpeech) {
                        event.result.text.orEmpty().trim().takeIf(String::isNotBlank)?.let { text ->
                            onPartial(transcript.commit(text))
                        }
                    }
                    // 一句提问在停顿后拿到定稿就自动结束；没开口则由 initial-silence 的 NoMatch 结束。
                    if (
                        event.result.reason in setOf(ResultReason.RecognizedSpeech, ResultReason.NoMatch) &&
                        autoStopRequested.compareAndSet(false, true)
                    ) {
                        stopTranscriptionRequested.set(true)
                        recognizer.stopContinuousRecognitionAsync()
                    }
                }
                recognizer.canceled.addEventListener { _, event ->
                    if (stopTranscriptionRequested.get()) {
                        completed.complete(null)
                    } else {
                        completed.complete(
                            TranscriptionResult.Failed(
                                "听写中断：${event.errorDetails?.takeIf(String::isNotBlank) ?: event.reason}",
                            ),
                        )
                    }
                }
                recognizer.sessionStopped.addEventListener { _, _ -> completed.complete(null) }

                activeTranscriber = recognizer
                recognizer.startContinuousRecognitionAsync().get()
                if (stopTranscriptionRequested.get()) recognizer.stopContinuousRecognitionAsync()

                val terminal = withTimeoutOrNull(MAX_TRANSCRIPTION_MS) { completed.await() }
                if (!completed.isCompleted) {
                    stopTranscriptionRequested.set(true)
                    recognizer.stopContinuousRecognitionAsync()
                }
                terminal ?: transcript.finalText().takeIf(String::isNotBlank)
                    ?.let(TranscriptionResult::Done)
                    ?: TranscriptionResult.NothingRecognized
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                TranscriptionResult.Failed("听写失败：${e.message ?: e.javaClass.simpleName}")
            } finally {
                if (activeTranscriber === recognizer) activeTranscriber = null
                runCatching { recognizer?.stopContinuousRecognitionAsync()?.get() }
                runCatching { recognizer?.close() }
                runCatching { languageConfig?.close() }
                runCatching { recognitionConfig?.close() }
                runCatching { audioConfig?.close() }
            }
        }
    }

    override fun stopTranscribing() {
        stopTranscriptionRequested.set(true)
        activeTranscriber?.let { recognizer ->
            runCatching { recognizer.stopContinuousRecognitionAsync() }
        }
    }

    /** 每次识别都用独立配置，避免共享的合成配置或上一轮语言污染当前识别器。 */
    private fun newRecognitionConfig(sourceLanguage: String): SpeechConfig =
        SpeechConfig.fromSubscription(subscriptionKey, region).apply {
            speechRecognitionLanguage = sourceLanguage
            // 读完停顿约 0.8 秒就收音，默认值等太久（用户实测反馈）。
            setProperty(PropertyId.Speech_SegmentationSilenceTimeoutMs, "800")
            // 实时听写优先尽快返回草稿；值越高文字越稳定，但首字等待也越久。
            setProperty(PropertyId.SpeechServiceResponse_StablePartialResultThreshold, "1")
            // 持续识别默认约 15 秒才判定开头一直没说话，提问场景不需要等这么久。
            setProperty(PropertyId.SpeechServiceConnection_InitialSilenceTimeoutMs, "7000")
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
        stopTranscribing()
        player.release()
        runCatching { synthesizer.close() }
        runCatching { speechConfig.close() }
    }

    private companion object {
        const val TAG = "AzureSpeech"

        /** 单声道 16bit @24kHz：1ms = 48 字节。日志里把字节数换算成时长，好跟播放时长直接比。 */
        const val BYTES_PER_MS_MONO = 48

        /** 跟 [SpeechSynthesisOutputFormat.Raw24Khz16BitMonoPcm] 对应，改格式时要一起改。 */
        const val SYNTHESIS_SAMPLE_RATE_HZ = 24_000

        /** 每次从合成流里取的字节数，约 33ms 音频——够小，起播不会被凑整块拖慢。 */
        const val CHUNK_BYTES = 1600

        /** 避免噪声环境或服务端异常让麦克风无限保持开启。 */
        const val MAX_TRANSCRIPTION_MS = 30_000L
    }
}

/** Azure 的 recognizing 是会反复改写的草稿，recognized 才能追加为下一段。 */
internal class StreamingTranscript {
    private val committed = mutableListOf<String>()

    fun preview(draft: String): String = synchronized(committed) {
        render(committed + draft.trim())
    }

    fun commit(text: String): String = synchronized(committed) {
        text.trim().takeIf(String::isNotBlank)?.let(committed::add)
        render(committed)
    }

    fun finalText(): String = synchronized(committed) { render(committed) }

    private fun render(parts: List<String>): String =
        parts.filter(String::isNotBlank).joinToString(" ")
}
