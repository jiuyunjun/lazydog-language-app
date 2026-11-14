package com.lazydog.english.domain.generation

import kotlin.random.Random

/**
 * 文章的写法（`引人入胜的阅读材料DESIGN.md` §6）。
 *
 * 不定这个的话，模型十篇有八篇写成"某某现象很常见，原因有三"。archetype 不是题材，
 * 是**叙述结构**：同一个主题用"隐藏系统"和"小案例"写出来是两篇完全不同的文章，
 * 这正是"看了十篇感觉都是一篇"的解药（§20）。
 *
 * 前十种都是讲道理的文章，轮一圈下来仍然是十篇说明文。后面九种换的是**体裁**：
 * 对话、来信、指南、悬案、第一人称——读的人换的不只是话题，是读法。
 *
 * [wire] 是存库和进提示词用的稳定 key。说明给两份：[briefEn] 进英文阅读的写作指令，
 * [briefZh] 进母语阅读的中文写作指令——指令语言和成文语言一致，模型才不会跟着切换语言。
 */
enum class ReadingArchetype(
    val wire: String,
    val labelZh: String,
    val briefEn: String,
    val briefZh: String,
) {
    ExplainSomethingWeird(
        "explain_weird",
        "解释一个怪现象",
        "Open with something that sounds wrong but is true. Walk through the obvious guess, " +
            "show why it fails, then reveal the real mechanism.",
        "用一件听起来不对、其实是真的事开头。先走一遍最容易想到的解释，说明它为什么站不住，再给出真正的机制。",
    ),
    OneQuestionOneAnswer(
        "one_question",
        "一问一答",
        "Ask one concrete question in the first two sentences and spend the whole piece " +
            "answering just that, going one layer deeper each paragraph.",
        "头两句提出一个具体的问题，全文只回答这一个，每段往下挖一层。",
    ),
    MiniCaseStudy(
        "case_study",
        "小案例",
        "Follow one specific incident: what someone tried, what went wrong, what it cost, " +
            "what everyone does differently now.",
        "跟着一件具体的事走：有人试了什么，哪里出了岔子，代价是什么，后来大家改成了怎么做。",
    ),
    HiddenSystem(
        "hidden_system",
        "隐藏的系统",
        "Take something the reader touches every day and show the machinery behind it, " +
            "step by step, in the order it actually happens.",
        "挑一件读者天天碰到的东西，按它实际发生的顺序，一步一步把背后那套运转讲出来。",
    ),
    CounterintuitiveIdea(
        "counterintuitive",
        "反直觉",
        "State the intuition the reader almost certainly holds, then show the evidence that " +
            "it is backwards, and give them the better model.",
        "先说出读者几乎一定持有的那个直觉，再摆出它其实反了的依据，最后给一个更好用的理解方式。",
    ),
    ShortNarrative(
        "narrative",
        "短叙事",
        "Tell it as a story with a person, a goal, an obstacle and a turn. No moral at the end; " +
            "let the last scene carry the point.",
        "写成一个故事：一个人、一个目标、一个阻碍、一次转折。结尾不讲道理，让最后一个场景自己说话。",
    ),
    TradeOff(
        "trade_off",
        "权衡",
        "Lay out two options people argue about, give each its strongest case, and show what " +
            "actually decides between them.",
        "摆出人们争来争去的两个选项，各给出最有力的理由，再说清真正决定选哪个的是什么。",
    ),
    MythVsReality(
        "myth_reality",
        "迷思与真相",
        "Take a belief real people hold for real reasons, show where it came from, and where " +
            "it stops being true. Never invent a silly version of it to knock down.",
        "挑一个真有人信、而且信得有道理的说法，讲它从哪儿来、到哪儿就不成立了。不许编一个蠢版本来驳。",
    ),
    BeforeAndAfter(
        "before_after",
        "前后对比",
        "Show how something worked before, what changed it, and what that change cost or " +
            "made possible.",
        "讲一件事以前是怎么运转的，是什么改变了它，这次改变让人付出了什么、又让什么成为可能。",
    ),
    NoticeItToday(
        "notice_today",
        "今天就能观察到",
        "Explain something the reader can go out and verify within a day, and tell them " +
            "exactly what to look for.",
        "讲一件读者一天之内就能自己去验证的事，并且告诉他具体该看什么。",
    ),
    ObjectStory(
        "object_story",
        "一件东西的身世",
        "Pick one ordinary object and follow its life: where it starts, the hands it passes " +
            "through, the stage nobody thinks about, and where it ends up.",
        "挑一件普通的东西，跟着它走一遍：从哪儿来，经过谁的手，哪一段没人想过，最后去了哪儿。",
    ),
    DayOnTheJob(
        "day_on_job",
        "某个行当的一天",
        "Follow a clearly invented person through one shift in a job the reader has seen but " +
            "never done. Let the small routines show what the work is really about.",
        "跟着一个明说是虚构的人，过完某个读者见过却没干过的行当的一个班。让那些小规矩自己透露这份工作真正在做什么。",
    ),
    TwoVoices(
        "two_voices",
        "两个人的争论",
        "Write it mostly as a conversation between two people who disagree about something " +
            "small and concrete. Give each real points; let the last exchange shift the question " +
            "instead of declaring a winner.",
        "主体写成两个人的对话，他们为一件具体的小事意见不合。两边都要有站得住的理由；最后一来一回把问题换个问法，而不是宣布谁赢。",
    ),
    LetterAndReply(
        "letter_reply",
        "来信与回信",
        "A short letter from someone with a specific everyday dilemma, followed by a reply " +
            "that takes it seriously and offers one unexpected but practical way to see it.",
        "一封短信，写信的人有个具体的日常难题；接着是一封认真对待它的回信，给出一个意外却用得上的看法。",
    ),
    MicroMystery(
        "micro_mystery",
        "小悬案",
        "Set up a small puzzle with three or four clues laid out fairly, give the reader room " +
            "to guess, then solve it using only clues already on the page.",
        "摆出一个小谜团，公平地给三四条线索，留出让读者自己猜的余地，再只用已经写出来的线索把它解开。",
    ),
    WhatIf(
        "what_if",
        "假如",
        "Change exactly one thing about ordinary life and follow the consequences step by " +
            "step, each one following honestly from the last. Mark it plainly as a thought experiment.",
        "把日常生活里的一件事改掉，只改这一件，然后一步一步推后果，每一步都老老实实从上一步来。明说这是一个思想实验。",
    ),
    FieldGuide(
        "field_guide",
        "上手指南",
        "Teach the reader to do or spot one specific thing, the way someone experienced would " +
            "explain it to a friend: the mistake beginners make, the trick, how to tell it worked.",
        "教读者做成或认出一件具体的事，像老手讲给朋友听：新手常犯的错、诀窍在哪、怎么知道自己做对了。",
    ),
    RankedThree(
        "ranked_three",
        "排个座次",
        "Pick three contenders for one oddly specific title, argue for each in turn, then name " +
            "a winner and admit what that choice gives up.",
        "为一个很具体、有点怪的头衔挑三个候选，挨个替它们说话，最后定一个第一名，并承认这么选丢掉了什么。",
    ),
    FirstPerson(
        "first_person",
        "第一人称小事",
        "First person, one small episode told by an invented narrator: what they expected, " +
            "what actually happened, and the detail they still think about. State no lesson.",
        "用第一人称，由一个虚构的叙述者讲一件小事：原本以为会怎样，实际发生了什么，至今还记得的那个细节。不点明道理。",
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
