package com.lazydog.english.core.config

/**
 * 对话模型用哪家（D-090）。
 *
 * 两家走的都是 OpenAI 兼容的 `/chat/completions` 和 `/models`，生成器一行不用改；
 * 区别只在地址、密钥和**模型名**。模型名必须按服务商分开存：切到 DeepSeek 时还拿着
 * `gpt-xxx` 去请求，第一次生成就 400。所以默认模型和「各功能使用的模型」都是每家一套。
 *
 * [fixedBaseUrl] 为 null 表示地址用本地配置（可能是代理或聚合网关），非空表示这家地址是固定的。
 */
enum class AiVendor(
    val wire: String,
    val labelZh: String,
    val fixedBaseUrl: String?,
    val defaultModel: String,
) {
    OpenAiCompatible("openai", "OpenAI 兼容", null, LocalEnv.AI_MODEL),

    /** 国内直连可用。`deepseek-flash` 是官方当前的快档，`deepseek-v4-pro` 在模型列表里可选。 */
    DeepSeek("deepseek", "DeepSeek", "https://api.deepseek.com", "deepseek-flash"),
    ;

    companion object {
        fun fromWire(wire: String?): AiVendor = entries.firstOrNull { it.wire == wire } ?: OpenAiCompatible
    }
}

/**
 * 网页检索用哪家（D-090）。只管 `WebSearchProvider`：母语阅读的联网检索、热点兜底、找新闻原文。
 * **单词配图不跟着切**——DeepSeek 没有图片检索，配图始终用 Brave。
 */
enum class SearchVendor(val wire: String, val labelZh: String) {
    Brave("brave", "Brave"),

    /** 和 DeepSeek 对话用同一个密钥。 */
    DeepSeek("deepseek", "DeepSeek"),
    ;

    companion object {
        fun fromWire(wire: String?): SearchVendor = entries.firstOrNull { it.wire == wire } ?: Brave
    }
}
