package com.lazydog.english.core.speech

import com.lazydog.english.domain.speaking.SpeechRate

/** 生成带语速的 TTS SSML。文本会做 XML 转义，防止内容里的符号破坏结构。 */
internal fun buildSpeechSsml(text: String, voiceName: String, rate: SpeechRate): String {
    val escaped = escapeXml(text)
    return "<speak version=\"1.0\" xmlns=\"http://www.w3.org/2001/10/synthesis\" xml:lang=\"en-US\">" +
        "<voice name=\"$voiceName\"><prosody rate=\"${rate.prosodyRate}\">$escaped</prosody></voice>" +
        "</speak>"
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
