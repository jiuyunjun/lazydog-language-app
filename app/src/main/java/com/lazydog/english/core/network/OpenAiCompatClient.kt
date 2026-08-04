package com.lazydog.english.core.network

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * OpenAI 兼容接口的最小客户端。M0 只提供连接测试；
 * M3 的内容生成 provider 会在此之上按 AI_CONTRACTS 定义领域接口。
 */
class OpenAiCompatClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val okHttpClient: OkHttpClient = defaultOkHttpClient,
) {

    sealed interface ConnectionResult {
        /** 接口可达且密钥有效。[modelListed] 表示配置的模型是否出现在 /models 列表里。 */
        data class Success(val modelCount: Int, val modelListed: Boolean) : ConnectionResult
        data class Failure(val reason: String) : ConnectionResult
    }

    /** 调 GET {baseUrl}/models 验证地址与密钥。失败原因不含密钥，可直接展示。 */
    suspend fun testConnection(expectedModel: String): ConnectionResult {
        val request = Request.Builder()
            .url(modelsUrl(baseUrl))
            .header("Authorization", "Bearer $apiKey")
            .get()
            .build()

        return try {
            okHttpClient.newCall(request).await().use { response ->
                if (!response.isSuccessful) {
                    return ConnectionResult.Failure(httpFailureReason(response.code))
                }
                val body = response.body?.string().orEmpty()
                val models = json.decodeFromString<ModelListResponse>(body)
                ConnectionResult.Success(
                    modelCount = models.data.size,
                    modelListed = models.data.any { it.id == expectedModel },
                )
            }
        } catch (e: IOException) {
            ConnectionResult.Failure("网络错误：${e.message ?: e.javaClass.simpleName}")
        } catch (e: kotlinx.serialization.SerializationException) {
            ConnectionResult.Failure("返回内容不是预期的模型列表格式")
        }
    }

    @Serializable
    private data class ModelListResponse(val data: List<ModelInfo> = emptyList())

    @Serializable
    private data class ModelInfo(val id: String = "")

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        private val defaultOkHttpClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

        private fun httpFailureReason(code: Int): String = when (code) {
            401 -> "HTTP 401：密钥无效或已过期"
            403 -> "HTTP 403：密钥没有访问权限"
            404 -> "HTTP 404：Base URL 不对，或服务不提供 /models"
            429 -> "HTTP 429：请求过于频繁或额度用尽"
            else -> "HTTP $code"
        }
    }
}

/** 拼出 /models 地址；容忍 baseUrl 尾部斜杠。 */
internal fun modelsUrl(baseUrl: String): String =
    baseUrl.trim().trimEnd('/') + "/models"
