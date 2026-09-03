package com.lazydog.english.core.speech

import com.lazydog.english.domain.speaking.SpeechRate
import com.lazydog.english.domain.speaking.SpeechStyle

/**
 * 生成带语速的 TTS SSML。文本会做 XML 转义，防止内容里的符号破坏结构。
 *
 * 注意 Dragon HD 音色只支持 SSML 的一个子集：`<break>`、`<lang>`、`<say-as>`、`<sub>`、
 * `<phoneme>` 可用，而 **`<prosody>` 和 `<bookmark>` 都不支持**（见官方文档
 * "Supported and unsupported SSML elements for Azure Speech HD voices" 一节）。两个后果：
 * 语速对 HD 音色不生效（服务端直接忽略），以及拿不到书签，所以"读到哪句高亮哪句"在 HD 上做不了
 * ——那需要换成标准 Neural 音色，而音质是不换的。
 *
 * [SpeechStyle.Word] 前后各垫一段停顿（[WORD_BREAK_MS]）：Dragon HD 念孤立单词是"聊天腔"，
 * 起收都急，不给起拍收拍听着像被掐头去尾（用户实测"开始和结束太仓促"）。这段停顿是合成出来的
 * 音频的一部分，跟播放器为唤醒通路垫的静音是两码事。
 *
 * 音色两种风格用同一个（就是用户在设置里选的那个）。曾经单词会被换成同口音的标准
 * Neural 音色去掉 Dragon HD 的"聊天腔"，但 SpeechConfig 的连接是绑在 HD 那套合成
 * 后端上的，在这条连接上点名标准 Neural 合不出音频——表现就是单词和词组一点声音都
 * 没有、例句却正常，而且失败是静默的（见 AzureSpeechProvider 里的日志）。
 * 真要恢复播音腔，得给标准 Neural 单开一个 SpeechConfig/SpeechSynthesizer。
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
