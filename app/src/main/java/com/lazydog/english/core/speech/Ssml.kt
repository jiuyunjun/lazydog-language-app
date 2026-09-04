package com.lazydog.english.core.speech

import com.lazydog.english.domain.speaking.SpeechRate
import com.lazydog.english.domain.speaking.SpeechStyle

/**
 * 生成带语速的 TTS SSML。文本会做 XML 转义，防止内容里的符号破坏结构。
 *
 * 这里只管排版，**用哪个音色由调用方决定**（`AzureSpeechProvider.speak`）：单词走播音腔，
 * 换成同一个人的标准 Neural（[broadcastVoiceOf]）；例句和短文用用户设置里的音色。
 * 把选音色的策略留在调用方，是因为换过的音色万一合不出音频要能退回去重念一遍——
 * 那需要用原音色再排一次版，策略埋在这里就没法退。
 *
 * [SpeechStyle.Word] 前后各垫一段停顿（[WORD_BREAK_MS]），避免起音被吃掉、句末也不至于压得太狠。
 * 这段停顿是合成出来的音频的一部分，跟播放器为唤醒通路垫的静音是两码事。
 */
internal fun buildSpeechSsml(
    text: String,
    voiceName: String,
    rate: SpeechRate,
    style: SpeechStyle = SpeechStyle.Sentence,
): String {
    val escaped = escapeXml(text)
    val voice = voiceName
    val body = when (style) {
        SpeechStyle.Word ->
            "<break time=\"${WORD_BREAK_MS}ms\"/>" +
                "<prosody rate=\"${rate.prosodyRate}\" pitch=\"0%\">$escaped</prosody>" +
                "<break time=\"${WORD_BREAK_MS}ms\"/>"
        SpeechStyle.Sentence -> "<prosody rate=\"${rate.prosodyRate}\">$escaped</prosody>"
    }
    return "<speak version=\"1.0\" xmlns=\"http://www.w3.org/2001/10/synthesis\" xml:lang=\"en-US\">" +
        "<voice name=\"$voice\">$body</voice>" +
        "</speak>"
}

/**
 * 这一次朗读用哪个音色。
 *
 * 两种情况要换成同一个人的标准 Neural（[broadcastVoiceOf]）：
 * - **单词**：要的是播音腔，理由见 [broadcastVoiceOf]。
 * - **这次要变速**：Dragon HD 不支持 `<prosody>`，服务端直接忽略。不换的话用户点了"慢速"
 *   却一点变化都没有——听力的慢速重放、设置里的语速都是这样白设的。宁可这一句不用 HD 的
 *   自然度，也不能让一个明确的操作毫无反应。
 *
 * 正常语速的例句和短文仍然走用户设置的音色（默认 HD），日常听到的还是那把自然的声音。
 */
internal fun voiceFor(configured: String, style: SpeechStyle, rate: SpeechRate): String =
    if (style == SpeechStyle.Word || rate != SpeechRate.Normal) broadcastVoiceOf(configured) else configured

/**
 * Dragon HD 音色名 → 同一个人的标准 Neural 音色名，如
 * `en-US-Ava:DragonHDLatestNeural` → `en-US-AvaNeural`。
 * 不带 `:` 的名字（已经是标准音色，或用户自填）原样返回。
 *
 * 单词朗读要的是"播音腔"。Dragon HD 是奔着对话调的：念一个孤立单词会自动补上句子的情绪和
 * 句末降调，听着像在跟你聊天而不是在报读音，而且每次合成的语调都不一样（HD 的 prosody
 * variation 是设计如此，同一个词连点三次能听出三个语气）。标准 Neural 平得多，更接近词典
 * 示范音，每次也一致。
 *
 * 附带好处：**Dragon HD 不支持 `<prosody>`**（官方 HD 音色 SSML 支持表里 prosody 和 bookmark
 * 都是 No），语速设置对它是无效的，服务端直接忽略。换成标准 Neural 之后语速才真的生效——
 * 所以变速的句子也走这里，见 [voiceFor]。
 */
internal fun broadcastVoiceOf(voiceName: String): String {
    val speaker = voiceName.substringBefore(':', missingDelimiterValue = "")
    return if (speaker.isBlank()) voiceName else speaker + "Neural"
}

/** 单词前后的停顿。太短起收发急，太长就成了点了半天不响。 */
internal const val WORD_BREAK_MS = 200

internal fun escapeXml(text: String): String = buildString(text.length) {
    for (ch in text) {
        when (ch) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> append(ch)
        }
    }
}
