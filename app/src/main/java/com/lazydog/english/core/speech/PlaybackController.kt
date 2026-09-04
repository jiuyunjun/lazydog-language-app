package com.lazydog.english.core.speech

import android.util.Log
import com.lazydog.english.domain.speaking.SpeakResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** [PlaybackController] 底下那层：合成一段并把它播完。由 [SpeechController] 接到真正的 provider 上。 */
interface TtsPlayback {

    /**
     * 合成 [job] 并播放，播完（缓冲真正放空）才返回。
     * 音频真正开始出声时回调 [onStarted]。
     * 被下一次播放顶掉时应当尽快返回，不必等 Azure 那边取消完成。
     */
    suspend fun speak(job: PlaybackJob, onStarted: () -> Unit): SpeakResult

    /** 立刻掐声。[keepLink] 见 `SpeechProvider.stopSpeaking`。 */
    fun stop(keepLink: Boolean)
}

/**
 * 全局唯一的播放状态拥有者（`语音服务DESIGN.md` §19、§37.6）。
 *
 * 界面、Azure 回调、音频线程都不直接改状态，只往 [events] 里投事件，由 [scope] 上那一条
 * 消费协程串行地过一遍 [reduce]。所以 `currentJob` 和 `state` 天然没有并发写。
 *
 * 交互约定（§12）：
 * - 同一个按钮再点一次 = 停。
 * - 点别的按钮 = 立刻顶掉当前这段，换成新的，不排队。
 * - 连点取「最后一次有效」：每次新意图都 [generation]++，之前的任务全部作废。
 *
 * 取消只负责省资源，正确性靠 [isCurrent] 校验（§16）——迟到的回调即使 cancel 已经发出去了
 * 也可能到达，它们在这里一律被丢掉，不会把声音或状态改回去。
 */
class PlaybackController(
    private val scope: CoroutineScope,
    private val tts: TtsPlayback,
    private val now: () -> Long = System::currentTimeMillis,
) {

    private val _state = MutableStateFlow(PlaybackState.Idle)
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val events = Channel<PlaybackEvent>(Channel.UNLIMITED)

    private var generation = 0L
    private var jobSeq = 0L
    private var currentJob: PlaybackJob? = null

    /** 这一段的耗时打点，只对 [currentJob] 有效（§33）。 */
    private var requestedAtMs = 0L
    private var startedAtMs = 0L

    init {
        scope.launch {
            for (event in events) reduce(event)
        }
    }

    /**
     * 播放按钮被点了：没在播就播，正在播同一个就停，正在播别的就顶掉换成这个。
     * 界面只调这一个入口，不碰 cancel、stop 这些具体动作（§13）。
     */
    fun onPlayClicked(source: PlaybackSource) {
        events.trySend(PlaybackEvent.PlayRequested(source, toggle = true))
    }

    /** 直接播这一段，不做「再点一次 = 停」的切换。自动朗读、听力自动放音走这里。 */
    fun play(source: PlaybackSource) {
        events.trySend(PlaybackEvent.PlayRequested(source, toggle = false))
    }

    /** 别念了。[keepLink] 表示人还在这一页、马上还要念下一段，见 `SpeechProvider.stopSpeaking`。 */
    fun stop(keepLink: Boolean = false) {
        events.trySend(PlaybackEvent.StopRequested(keepLink))
    }

    private fun reduce(event: PlaybackEvent) {
        when (event) {
            is PlaybackEvent.PlayRequested -> {
                val current = currentJob
                if (current != null && event.toggle && current.source.id == event.source.id) {
                    // 人在停一段自己刚点开的音，马上多半还要点下一个，链路留着。
                    stopCurrent(keepLink = true)
                } else {
                    start(event.source)
                }
            }

            is PlaybackEvent.StopRequested -> {
                stopCurrent(keepLink = event.keepLink)
            }

            is PlaybackEvent.PlaybackStarted -> {
                if (!isCurrent(event.job)) return
                startedAtMs = now()
                _state.value = PlaybackState(PlaybackStatus.Playing, event.job.source.id, event.job.jobId)
            }

            is PlaybackEvent.PlaybackCompleted -> {
                if (!isCurrent(event.job)) return
                logJob(event.job, outcome = "completed")
                currentJob = null
                _state.value = PlaybackState.Idle
            }

            is PlaybackEvent.Failed -> {
                if (!isCurrent(event.job)) return
                logJob(event.job, outcome = "failed: ${event.error.message}")
                currentJob = null
                tts.stop(keepLink = false)
                _state.value = PlaybackState(
                    status = PlaybackStatus.Error,
                    sourceId = event.job.source.id,
                    jobId = event.job.jobId,
                    error = event.error,
                )
            }
        }
    }

    /**
     * 顶掉当前这段并开始新的一段。
     *
     * 顺序照 §14：先让旧任务失效、立刻掐掉声音，然后马上开新任务，不等旧任务那边取消完成。
     * 旧任务的协程自己会在下一次校验时发现被顶掉了，收拾完自然结束。
     */
    private fun start(source: PlaybackSource) {
        generation += 1
        // 只在确实还有东西在响的时候才掐。provider 每次朗读前本来就会自己停一次，
        // 这里掐是为了"声音立刻停"，不是为了让新的一段能开始（§14）。
        if (currentJob != null) tts.stop(keepLink = true)
        val job = PlaybackJob(jobId = "job-${++jobSeq}", source = source, generation = generation)
        currentJob = job
        requestedAtMs = now()
        startedAtMs = 0L
        _state.value = PlaybackState(PlaybackStatus.Loading, source.id, job.jobId)
        scope.launch {
            val result = tts.speak(job) { events.trySend(PlaybackEvent.PlaybackStarted(job)) }
            events.trySend(
                when (result) {
                    SpeakResult.Done -> PlaybackEvent.PlaybackCompleted(job)
                    is SpeakResult.Failed -> PlaybackEvent.Failed(job, PlaybackError(result.reason))
                },
            )
        }
    }

    private fun stopCurrent(keepLink: Boolean) {
        generation += 1
        val job = currentJob
        currentJob = null
        tts.stop(keepLink)
        if (job != null) logJob(job, outcome = "stopped")
        // 没在播也要落回 Idle：上一段留下的错误提示不该跟着用户翻页。
        _state.value = PlaybackState.Idle
    }

    /** 这个回调还作数吗（§37.4）：任务是当前任务，而且没被更新的播放意图顶掉。 */
    private fun isCurrent(job: PlaybackJob): Boolean {
        val current = currentJob ?: return false
        return current.jobId == job.jobId && current.generation == generation
    }

    /**
     * 一段播完就记一行：出「点了半天不响」的问题时，靠它区分是合成慢、起播慢还是播放本身出错
     * （§33）。underrun 由 [PcmAudioPlayer] 自己记，那边才知道缓冲的情况。
     */
    private fun logJob(job: PlaybackJob, outcome: String) {
        val end = now()
        Log.i(
            TAG,
            "job=${job.jobId} source=${job.source.id} gen=${job.generation} " +
                "chars=${job.source.text.length} style=${job.source.style} " +
                "playStartMs=${if (startedAtMs > 0) startedAtMs - requestedAtMs else -1} " +
                "totalMs=${end - requestedAtMs} $outcome",
        )
    }

    private companion object {
        const val TAG = "Playback"
    }
}

/** 所有异步行为先变成事件，再进 [PlaybackController.reduce]（§20）。 */
private sealed interface PlaybackEvent {
    data class PlayRequested(val source: PlaybackSource, val toggle: Boolean) : PlaybackEvent
    data class StopRequested(val keepLink: Boolean) : PlaybackEvent
    data class PlaybackStarted(val job: PlaybackJob) : PlaybackEvent
    data class PlaybackCompleted(val job: PlaybackJob) : PlaybackEvent
    data class Failed(val job: PlaybackJob, val error: PlaybackError) : PlaybackEvent
}
