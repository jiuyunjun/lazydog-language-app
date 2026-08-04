package com.lazydog.english.core.network

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 用 issueToken 端点验证 Azure Speech 密钥和区域。
 * 只做连接测试；正式的语音功能走 Speech SDK（core/speech）。
 */
class AzureSpeechTokenClient(
    private val subscriptionKey: String,
    private val tokenUrl: String,
    private val okHttpClient: OkHttpClient = defaultOkHttpClient,
) {
    constructor(subscriptionKey: String, region: String) :
        this(subscriptionKey, tokenUrl = speechTokenUrl(region))

    sealed interface TokenResult {
        data object Success : TokenResult
        data class Failure(val reason: String) : TokenResult
    }

    suspend fun testConnection(): TokenResult {
        val request = Request.Builder()
            .url(tokenUrl)
            .header("Ocp-Apim-Subscription-Key", subscriptionKey)
            .post(okhttp3.RequestBody.create(null, ByteArray(0)))
            .build()
        return try {
            okHttpClient.newCall(request).await().use { response ->
                when {
                    response.isSuccessful -> TokenResult.Success
                    response.code == 401 || response.code == 403 ->
                        TokenResult.Failure("HTTP ${response.code}：密钥无效或区域不对")
                    else -> TokenResult.Failure("HTTP ${response.code}")
                }
            }
        } catch (e: IOException) {
            TokenResult.Failure("网络错误：${e.message ?: e.javaClass.simpleName}")
        }
    }

    companion object {
        private val defaultOkHttpClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}

internal fun speechTokenUrl(region: String): String =
    "https://${region.trim()}.api.cognitive.microsoft.com/sts/v1.0/issueToken"
