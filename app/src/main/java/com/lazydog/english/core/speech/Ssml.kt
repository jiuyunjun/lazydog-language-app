package com.lazydog.english.core.speech

import com.lazydog.english.domain.speaking.SpeechRate
import com.lazydog.english.domain.speaking.SpeechStyle

/**
 * 生成带语速的 TTS SSML。文本会做 XML 转义，防止内容里的符号破坏结构。
 *
 * [SpeechStyle.Word] 走"播音腔"：
 * - 换成同口音的标准 Neural 音色。Dragon HD 是奔着对话去调的，念一个孤立单词时会自动
 *   补上句子的情绪和句末降调，听着像在跟你聊天而不是在报读音；标准 Neural 平得多，
 *   更接近词典示范音。
 * - 前后各垫一小段停顿，避免起音被吃掉、句末也不至于压得太狠。
 */
internal fun buildSpeechSsml(
    text: String,
    voiceName: String,
    rate: SpeechRate,
    style: SpeechStyle = SpeechStyle.Sentence,
): String {
    val escaped = escapeXml(text)
    val voice = if (style == SpeechStyle.Word) broadcastVoiceOf(voiceName) else voiceName
    val body = when (style) {
        SpeechStyle.Word ->
            "<break time=\"120ms\"/>" +
                "<prosody rate=\"${rate.prosodyRate}\" pitch=\"0%\">$escaped</prosody>" +
                "<break time=\"120ms\"/>"
        SpeechStyle.Sentence -> "<prosody rate=\"${rate.prosodyRate}\">$escaped</prosody>"
    }
    return "<speak version=\"1.0\" xmlns=\"http://www.w3.org/2001/10/synthesis\" xml:lang=\"en-US\">" +
        "<voice name=\"$voice\">$body</voice>" +
        "</speak>"
}

/**
 * Dragon HD 音色名 → 同一个人的标准 Neural 音色名，如
 * `en-US-Ava:DragonHDLatestNeural` → `en-US-AvaNeural`。
 * 不带 `:` 的名字（已经是标准音色，或用户自填）原样返回。
 */
internal fun broadcastVoiceOf(voiceName: String): String {
    val speaker = voiceName.substringBefore(':', missingDelimiterValue = "")
    return if (speaker.isBlank()) voiceName else speaker + "Neural"
}

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
