package com.lazydog.english.domain.ask

import com.lazydog.english.domain.generation.JsonStream

/**
 * 摇一摇提问的领域模型（DESIGN 屏 45～49）。
 *
 * 上下文由各学习页面主动注册成结构化对象，不截屏；阅读页会把当前材料全文作为一个字段注册，
 * 让追问能够联系前后文。
 * 抽屉关掉即结束一次会话，不保留跨会话的问答历史。
 */
enum class AskContextKind(
    /** 上下文卡折叠时左侧那行小字。 */
    val cardLabel: String,
    /** 进提示词时对"用户正在看什么"的描述。 */
    val promptLabel: String,
) {
    Word("正在看", "一个单词卡"),
    Grammar("正在学", "一条语法讲解"),
    Reading("正在读", "一篇阅读材料"),
    Question("这道题", "一道刚做过的选择题"),
    Scenario("这场演练", "一次英语情景演练"),
}

/** 上下文卡展开后显示的一行，同时逐条进提示词。 */
data class AskDetail(val label: String, val value: String)

data class AskContext(
    val kind: AskContextKind,
    /** 折叠时显示的一行，如 "linger · 逗留、残留"。 */
    val title: String,
    val details: List<AskDetail> = emptyList(),
    /** 抽屉刚升起时给的建议问题，点一下直接问。 */
    val suggestions: List<String> = emptyList(),
)

/** 本次抽屉里的一轮问答，用于连续追问时不必重述对象。 */
data class AskExchange(val question: String, val answerZh: String)

data class AskRequest(
    val context: AskContext,
    val learnerLevel: String,
    val history: List<AskExchange>,
    val question: String,
)

/** 回答里出现、值得加进复习的英文词或表达；由用户点按钮才入库。 */
data class AskAddableTerm(val term: String, val meaningZh: String)

data class AskAnswer(val answerZh: String, val addable: List<AskAddableTerm> = emptyList())

object AskValidation {

    const val MAX_ADDABLE = 3
    const val MAX_QUESTION_LENGTH = 300

    /** 返回 null 表示通过；否则是给用户看的失败原因。 */
    fun validate(answer: AskAnswer): String? = when {
        answer.answerZh.isBlank() -> "回答是空的"
        else -> null
    }

    /** 去掉空条目并截断到上限；答案本身不限长（设计决定：抽屉可滚动）。 */
    fun clean(answer: AskAnswer): AskAnswer = AskAnswer(
        answerZh = answer.answerZh.trim(),
        addable = answer.addable
            .map { AskAddableTerm(it.term.trim(), it.meaningZh.trim()) }
            .filter { it.term.isNotBlank() && it.meaningZh.isNotBlank() }
            .distinctBy { it.term.lowercase() }
            .take(MAX_ADDABLE),
    )
}

/** 流式展示用：取出 answerZh 已经生成的部分，见 [JsonStream]。 */
object AskStreaming {

    fun partialAnswer(raw: String): String = JsonStream.partialString(raw, "answerZh")
}
