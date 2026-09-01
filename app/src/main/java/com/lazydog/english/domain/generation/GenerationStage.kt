package com.lazydog.english.domain.generation

/**
 * 长生成过程中「现在到底卡在哪一步」。
 *
 * 只报"已生成 N 字符"的话，推理模型开口前的那段（可能几十秒）界面上什么都不动，
 * 看着就像卡死或者没联网——那段其实是模型在想，跟接不通完全是两回事，
 * 用户对这两件事的反应也不一样（一个是等，一个是去查网络）。
 */
sealed interface GenerationStage {

    /** 请求发出去了，响应头还没回来。真正的"接通中"只有这一段。 */
    data object Connecting : GenerationStage

    /**
     * 服务端接上了，但正文还没开始——推理模型会先想一阵。
     * [excerpt] 是拿得到的思考文本（服务商愿意流式给的话），拿不到就是空串。
     */
    data class Thinking(val excerpt: String) : GenerationStage

    /** 正文在写了，[chars] 是已经收到的字符数。 */
    data class Writing(val chars: Int) : GenerationStage
}
