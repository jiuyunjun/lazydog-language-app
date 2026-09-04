package com.lazydog.english.core.speech

import com.lazydog.english.domain.speaking.SpeechRate
import com.lazydog.english.domain.speaking.SpeechStyle

/**
 * 一次播放意图指向的内容（`语音服务DESIGN.md` §11）。
 *
 * [id] 是「哪个按钮」：同一个词在词卡和详情页用同一个 id，互相之间才能正确地顶掉和切换。
 * 它和 jobId 是两回事——停掉再点同一个词，id 不变，job 是新的。
 */
data class PlaybackSource(
    val id: String,
    val text: String,
    val style: SpeechStyle = SpeechStyle.Sentence,
    /** 传空跟随设置里的默认语速；听力的「慢速」按钮靠它单独指定。 */
    val rate: SpeechRate? = null,
) {
    companion object {

        /** 报一个单词或搭配：用播音腔（见 [SpeechStyle]）。 */
        fun word(term: String): PlaybackSource =
            PlaybackSource(id = "word:${term.trim().lowercase()}", text = term, style = SpeechStyle.Word)

        /**
         * 念一句话。id 只看内容：同一句话不管是点旁边的喇叭还是单击句子本身触发的，
         * 都是同一个 source，按钮才能正确地显示成"正在播"。
         */
        fun sentence(text: String): PlaybackSource =
            PlaybackSource(id = "sentence:${text.trim().lowercase()}", text = text)
    }
}

/**
 * 一次播放任务。每次点击都产生新的 [jobId]，[generation] 是当时的全局播放意图序号。
 *
 * 迟到的异步回调靠这两个字段判断自己是不是还作数（§15、§37.4）。
 */
data class PlaybackJob(
    val jobId: String,
    val source: PlaybackSource,
    val generation: Long,
)

/** 暴露给界面的播放状态（§10）。内部的 STOPPING / DRAINING 之类不往上抬。 */
enum class PlaybackStatus { Idle, Loading, Playing, Error }

/**
 * 全局唯一的播放状态。播放按钮不许自己存 `isPlaying`（§23），
 * 一律用 [statusOf] 按自己的 sourceId 去问，这样任何时刻只有一个按钮显示在播。
 */
data class PlaybackState(
    val status: PlaybackStatus = PlaybackStatus.Idle,
    val sourceId: String? = null,
    val jobId: String? = null,
    val error: PlaybackError? = null,
) {

    fun statusOf(sourceId: String): PlaybackStatus =
        if (this.sourceId == sourceId) status else PlaybackStatus.Idle

    companion object {
        val Idle = PlaybackState()
    }
}

/**
 * 播放失败的原因，已经是能直接给用户看的中文（§32）。
 *
 * 底层区分 Azure 错误和音频设备错误，但现在两条路都只会给出一句话，界面也只有一种处理方式
 * （提示 + 回到可重播），所以这里不按来源再分类型，等真有分别处理的需要再拆。
 */
data class PlaybackError(val message: String)
