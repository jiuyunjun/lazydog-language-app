package com.lazydog.english.domain.generation

import kotlin.random.Random

/**
 * 文章的写法（`引人入胜的阅读材料DESIGN.md` §6）。
 *
 * 不定这个的话，模型十篇有八篇写成"某某现象很常见，原因有三"。archetype 不是题材，
 * 是**叙述结构**：同一个主题用"隐藏系统"和"小案例"写出来是两篇完全不同的文章，
 * 这正是"看了十篇感觉都是一篇"的解药（§20）。
 *
 * [wire] 是存库和进提示词用的稳定 key，[briefEn] 直接给模型看——用英文写是因为
 * 它要出现在英文写作指令里，混着中文只会让模型跟着切换语言。
 */
enum class ReadingArchetype(
    val wire: String,
    val labelZh: String,
    val briefEn: String,
) {
    ExplainSomethingWeird(
        "explain_weird",
        "解释一个怪现象",
        "Open with something that sounds wrong but is true. Walk through the obvious guess, " +
            "show why it fails, then reveal the real mechanism.",
    ),
    OneQuestionOneAnswer(
        "one_question",
        "一问一答",
        "Ask one concrete question in the first two sentences and spend the whole piece " +
            "answering just that, going one layer deeper each paragraph.",
    ),
    MiniCaseStudy(
        "case_study",
        "小案例",
        "Follow one specific incident: what someone tried, what went wrong, what it cost, " +
            "what everyone does differently now.",
    ),
    HiddenSystem(
        "hidden_system",
        "隐藏的系统",
        "Take something the reader touches every day and show the machinery behind it, " +
            "step by step, in the order it actually happens.",
    ),
    CounterintuitiveIdea(
        "counterintuitive",
        "反直觉",
        "State the intuition the reader almost certainly holds, then show the evidence that " +
            "it is backwards, and give them the better model.",
    ),
    ShortNarrative(
        "narrative",
        "短叙事",
        "Tell it as a story with a person, a goal, an obstacle and a turn. No moral at the end; " +
            "let the last scene carry the point.",
    ),
    TradeOff(
        "trade_off",
        "权衡",
        "Lay out two options people argue about, give each its strongest case, and show what " +
            "actually decides between them.",
    ),
    MythVsReality(
        "myth_reality",
        "迷思与真相",
        "Take a belief real people hold for real reasons, show where it came from, and where " +
            "it stops being true. Never invent a silly version of it to knock down.",
    ),
    BeforeAndAfter(
        "before_after",
        "前后对比",
        "Show how something worked before, what changed it, and what that change cost or " +
            "made possible.",
    ),
    NoticeItToday(
        "notice_today",
        "今天就能观察到",
        "Explain something the reader can go out and verify within a day, and tell them " +
            "exactly what to look for.",
    ),
    ;

    companion object {
        fun fromWire(value: String): ReadingArchetype? =
            entries.firstOrNull { it.wire == value.trim().lowercase() }

        /**
         * 挑一个最近没用过的写法。[recent] 是最近几篇用过的 wire，越靠前越新。
         *
         * 全用过了就整体重来一轮——宁可重复一个老写法，也不要因为"都用过了"就退回
         * 没有写法约束的自由发挥，那才是回到十篇一个样。
         */
        fun pick(recent: List<String>, random: Random = Random.Default): ReadingArchetype {
            val used = recent.mapNotNull(::fromWire).toSet()
            val fresh = entries.filterNot { it in used }
            return (fresh.ifEmpty { entries.toList() }).random(random)
        }
    }
}
