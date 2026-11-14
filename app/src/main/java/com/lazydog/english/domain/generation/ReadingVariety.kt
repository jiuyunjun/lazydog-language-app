package com.lazydog.english.domain.generation

import kotlin.random.Random

/**
 * 切入角度：把一个很宽的主题收窄到一件具体的事上。
 *
 * 主题是用户挑的（"旅行""咖啡"），写法是轮换的（[ReadingArchetype]），但光有这两样，
 * 模型对同一个主题仍然每次都写它最先想到的那一面——"旅行"永远是"为什么旅行让人放松"。
 * 角度由本地随机抽，不交给模型自己选：让模型"换个新角度"，它换出来的还是那几个。
 *
 * 刻意不收"一个惊人的数字""一个词的来历"这类角度：它们最容易逼出编造的数据和词源，
 * 而阅读生成没有检索资料可依（AI_CONTRACTS 的事实边界）。
 *
 * 两份说明的分工同 [ReadingArchetype]：英文进英文阅读，中文进母语阅读。
 */
enum class ReadingAngle(
    val labelZh: String,
    val briefEn: String,
    val briefZh: String,
) {
    Money("钱", "the money: who pays, who earns, and what it really costs", "钱：谁出钱、谁赚钱、真正的代价是什么"),
    Mistake("一次失误", "one mistake, failure or near miss", "一次失误、失败或差点出事"),
    FirstTime("第一次", "someone doing it for the very first time", "一个头一回做这件事的人"),
    Unseen("看不见的人", "the people whose work nobody notices", "那些没人注意到的干活的人"),
    UnwrittenRule("没人明说的规矩", "an unwritten rule everyone follows and nobody explains", "一条人人照做、却没人解释过的规矩"),
    SmallDetail("一个小细节", "one small detail most people never notice", "一个大多数人从没留意过的小细节"),
    Extreme("最极端的那个", "the smallest, cheapest, slowest or strangest version of it", "它最小、最便宜、最慢或最怪的那个版本"),
    Disagreement("一场分歧", "a disagreement where both sides are partly right", "一场两边都占点理的分歧"),
    OddHour("不寻常的时刻", "what happens at an unusual hour or in the off season", "在不寻常的钟点或淡季里发生的事"),
    Senses("感官", "what it sounds, smells or feels like up close", "凑近了听到、闻到、摸到的是什么"),
    OneDecision("一个决定", "one decision made under pressure", "一个在压力下做出的决定"),
    Outsider("外人的眼光", "how it looks to an outsider, a newcomer or a child", "在外人、新来的人或小孩眼里它是什么样"),
    Breakdown("坏掉的时候", "what happens when it breaks, runs out or goes wrong", "它坏了、用完了或出岔子的时候会怎样"),
    Waiting("等待", "the waiting, queuing or boredom hidden inside it", "藏在里面的等待、排队或无聊"),
    ;

    companion object {
        /**
         * 抽 [count] 个不同的角度。给模型两个而不是一个：一个角度和主题对不上时
         * （"睡眠"配"钱"）它没有退路，只能硬写；两个里挑更合适的那个，硬写的概率小得多。
         */
        fun pick(count: Int = 2, random: Random = Random.Default): List<ReadingAngle> =
            entries.shuffled(random).take(count.coerceIn(0, entries.size))
    }
}

/**
 * 语气。同一种写法、同一个角度，用冷幽默写和用讲故事的口气写，读起来是两个作者。
 *
 * 语气只管措辞和节奏，不管事实和难度——"带点幽默"不是编段子的许可。
 */
enum class ReadingVoice(
    val labelZh: String,
    val briefEn: String,
    val briefZh: String,
) {
    DryWit(
        "冷幽默",
        "dry, understated humour; nothing that needs explaining to be funny",
        "带一点冷幽默，轻描淡写；不用需要解释才好笑的梗",
    ),
    WarmFriend(
        "朋友聊天",
        "warm and conversational, like telling a friend over coffee",
        "像跟朋友喝着东西聊天，亲近、随口",
    ),
    Crisp(
        "干净利落",
        "plain, brisk and concrete; short sentences, no decoration",
        "平实、利落、具体；句子短，不加修饰",
    ),
    Curious(
        "好奇追问",
        "openly curious; think out loud and ask the next question the reader would ask",
        "带着好奇往下追，把读者下一句想问的替他问出来",
    ),
    Skeptic(
        "有点怀疑",
        "mildly sceptical; test each claim before accepting it",
        "带一点怀疑，每个说法先掂量一下再接受",
    ),
    Storyteller(
        "讲故事",
        "unhurried and vivid; let scenes and small physical details do the work",
        "不紧不慢、有画面，让场景和看得见摸得着的小细节来说话",
    ),
    ;

    companion object {
        fun pick(random: Random = Random.Default): ReadingVoice = entries.random(random)
    }
}
