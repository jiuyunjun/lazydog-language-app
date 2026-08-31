package com.lazydog.english.core.ai

import android.util.Log

/**
 * AI 调用的日志。
 *
 * 出问题时要能一眼看出「哪个调用、打到哪、什么状态、服务端说了什么」。在这之前
 * 整个 App 一行日志都没有，界面上只剩一句「HTTP 400」，等于什么都没说。
 *
 * 按 AI_CONTRACTS.md §8 脱敏：不打 Authorization、不打密钥、不打提示词和用户长文本，
 * 只打长度。服务端返回的错误正文会打，但先过 [redact] 并截断——那是定位问题的关键，
 * 不打就等于没日志。
 *
 * 用 `adb logcat -s LazyDogAI` 看。
 */
internal object AiLog {

    private const val TAG = "LazyDogAI"
    private const val MAX_BODY_CHARS = 500

    fun start(op: String, model: String, url: String, promptChars: Int, streaming: Boolean) {
        Log.i(TAG, "$op → $model @ ${host(url)}｜提示词 $promptChars 字符｜${if (streaming) "流式" else "整取"}")
    }

    fun success(op: String, model: String, chars: Int, millis: Long) {
        Log.i(TAG, "$op ✓ $model｜返回 $chars 字符｜耗时 ${millis} ms")
    }

    fun failure(op: String, reason: String, millis: Long) {
        Log.w(TAG, "$op ✗ $reason｜耗时 ${millis} ms")
    }

    fun retry(op: String, reason: String, note: String) {
        Log.w(TAG, "$op ↻ $reason｜$note")
    }

    /** 服务端错误正文：脱敏、压掉换行、截断。 */
    fun body(raw: String): String {
        val clean = redact(raw).replace(Regex("\\s+"), " ").trim()
        return if (clean.length <= MAX_BODY_CHARS) clean else clean.take(MAX_BODY_CHARS) + "…（已截断）"
    }

    /** 密钥有可能被服务端原样回显在错误里，别让它落进 logcat。 */
    fun redact(text: String): String = text
        .replace(Regex("(?i)bearer\\s+\\S+"), "Bearer ***")
        .replace(Regex("sk-[A-Za-z0-9_\\-]{8,}"), "sk-***")

    /** 只留主机名：完整 URL 里可能带查询参数形式的密钥。 */
    private fun host(url: String): String =
        runCatching { java.net.URI(url).host ?: url }.getOrDefault(url)
}

/**
 * 从 OpenAI 兼容的错误返回里取出人能看懂的那句话。
 *
 * 服务端的 `{"error":{"message":"..."}}` 才说明白到底哪不对（比如某个参数不支持）。
 * 取不出来就退回原始正文，总比只剩一个状态码强。
 */
internal fun extractErrorMessage(body: String): String {
    val marker = "\"message\""
    val at = body.indexOf(marker)
    if (at < 0) return body
    val colon = body.indexOf(':', at + marker.length)
    if (colon < 0) return body
    val open = body.indexOf('"', colon + 1)
    if (open < 0) return body
    val builder = StringBuilder()
    var i = open + 1
    while (i < body.length) {
        val ch = body[i]
        when {
            ch == '\\' && i + 1 < body.length -> {
                // 转义序列原样保留，只保证不被下一个引号误当成结尾。
                builder.append(ch).append(body[i + 1])
                i += 2
            }
            ch == '"' -> return builder.toString().ifBlank { body }
            else -> {
                builder.append(ch)
                i += 1
            }
        }
    }
    return body
}
