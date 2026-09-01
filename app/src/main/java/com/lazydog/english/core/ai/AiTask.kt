package com.lazydog.english.core.ai

/**
 * 按功能挑模型（设置页「各功能使用的模型」）。
 *
 * 存在的理由：这些调用的性质差得很远。听力一次要写十句带干扰项的题，值得用最强的模型；
 * 摇一摇提问和点词讲解要的是**马上出字**，用大模型反而难受。一个全局模型没法同时满足两头。
 *
 * 每一项都可以留空表示"跟随默认"——默认模型改了，跟随的项自动跟着改，
 * 不用一项项去改（见 [com.lazydog.english.core.data.UserPreferences.aiModelFor]）。
 */
enum class AiTask(
    val key: String,
    val labelZh: String,
    val noteZh: String,
    /**
     * 思考力度（`reasoning_effort`）。推理模型开口前的那段思考实测能占到整次调用的六成，
     * 而这里多数任务是"按模板填内容"，不需要它想那么久。
     *
     * 服务端不认这个参数就会自动去掉重发并记住（见 [AiConfig.sendReasoningEffort]），
     * 所以对老模型和非 OpenAI 服务商也是安全的。
     */
    val reasoningEffort: String,
) {
    Listening("listening", "听力训练", "一次写十句带干扰项的题，最吃模型能力", "low"),
    Reading("reading", "阅读生成", "一篇短文加理解题，长且要求一致", "low"),
    Words("words", "单词生成", "词义、例句、记忆方法", "low"),
    Grammar("grammar", "语法讲解与练习", "讲解和挖空题", "low"),
    // 判定要稳，宁可多想一会儿：忽宽忽严比慢更伤。
    Translation("translation", "中译英出题与判定", "判定要稳，别忽宽忽严", "medium"),
    Scenario("scenario", "情景演练", "每轮两次调用，慢一点很明显", "minimal"),
    Explain("explain", "点词点句与摇一摇", "边生成边显示，快比强更重要", "minimal"),
    Assessment("assessment", "能力测评", "出题和开放表达评分", "medium"),
    Speaking("speaking", "发音提示", "把分数讲成人话，很短", "minimal"),
    ;

    companion object {
        fun fromKey(key: String): AiTask? = entries.firstOrNull { it.key == key }
    }
}
