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
     * 想要的思考力度（`reasoning_effort`）。推理模型开口前的那段思考实测能占到整次调用的六成
     * （听力一次实测 49 秒），而这里多数任务是"按模板填内容"，不需要它想那么久。
     *
     * null 表示不发这个参数，用模型自己的默认值——多数模型默认就是 medium，
     * 显式再发一遍 medium 没有意义，还多一个可能被拒的参数。
     *
     * **取值是模型相关的**：文档列出的有 none / minimal / low / medium / high / xhigh / max，
     * 但没有哪个模型全支持（gpt-5.6-terra 认 none 却不认 minimal）。所以这里只是首选，
     * 被拒了会退到 low，再不行才不发，见 [effortCandidates]。
     */
    val reasoningEffort: String?,
) {
    Listening("listening", "听力训练", "一次写十句带干扰项的题，最吃模型能力", "low"),
    Reading("reading", "阅读生成", "一篇短文加理解题，长且要求一致", "low"),
    Words("words", "单词生成", "词义、例句、记忆方法", "low"),
    Grammar("grammar", "语法讲解与练习", "讲解和挖空题", "low"),
    // 判定要稳，宁可多想一会儿：忽宽忽严比慢更伤，所以不压默认值。
    Translation("translation", "中译英出题与判定", "判定要稳，别忽宽忽严", null),
    // 这三个要的是马上出字，思考对它们几乎没有增益。
    Scenario("scenario", "情景演练", "每轮两次调用，慢一点很明显", "none"),
    Explain("explain", "点词点句与摇一摇", "边生成边显示，快比强更重要", "none"),
    Assessment("assessment", "能力测评", "出题和开放表达评分", null),
    Speaking("speaking", "发音提示", "把分数讲成人话，很短", "none"),
    ;

    companion object {
        fun fromKey(key: String): AiTask? = entries.firstOrNull { it.key == key }

        /**
         * 文档列出的全部取值，从最快到最慢。**没有哪个模型全支持**
         * （gpt-5.6-terra 认 none 却不认 minimal），所以发出去的取值被拒是正常路径，不是异常。
         */
        val ALL_EFFORTS = listOf("none", "minimal", "low", "medium", "high", "xhigh", "max")

        /** 用户在设置里选"用模型自己的默认值"时存的值：不发这个参数。 */
        const val MODEL_DEFAULT = "model-default"

        /** 上面那个值在界面上的说法。 */
        const val MODEL_DEFAULT_LABEL = "模型默认"

        /**
         * 这次调用真正要试的取值，按顺序。
         *
         * [chosen] 是用户在设置里挑的：null 表示跟随本功能的推荐值，[MODEL_DEFAULT] 表示不发。
         * [rejected] 是这个模型已经拒过的取值，直接跳过——撞过一次就不该再撞。
         *
         * 用户明确挑了某个值时，后面仍然跟上兜底候选：挑的那个不被支持时，退到别的取值
         * 也比退回模型默认（多数是 medium）强。
         */
        fun effortCandidates(
            task: AiTask,
            chosen: String? = null,
            rejected: Set<String> = emptySet(),
        ): List<String> {
            if (chosen == MODEL_DEFAULT) return emptyList()
            val preferred = chosen ?: task.reasoningEffort ?: return emptyList()
            // 只留一个兜底：候选越多，撞不上的模型就要多花几个往返才安定下来。
            // low 几乎所有推理模型都认，用它兜底，也比退回模型默认（多数是 medium）快。
            return listOf(preferred, FALLBACK)
                .distinct()
                .filterNot { it in rejected }
        }

        private const val FALLBACK = "low"
    }
}
