package com.lazydog.english.domain.progress

/**
 * 中断之后回来的状态（`持续学习DESIGN.md` §26）。
 *
 * 这一节的核心是一句反例：**不要说"你已经落后 74 个复习"**。那句话把回来这件事本身
 * 变成了惩罚，而人已经为中断付出过代价了，不需要 App 再补一刀。
 */
enum class Mood {
    /** 照常。 */
    Normal,

    /** 中断几天之后第一次回来：今天先热身，不补债。 */
    Comeback,

    /** 今天做得够多、而且开始连着错了：该收工了，不是该加练。 */
    Tired,
}

/** 隔了这么多天没学，回来时就走热身流程，而不是把积压的到期量摊在脸上。 */
const val COMEBACK_AFTER_DAYS = 3

/**
 * 今天该用什么口径招呼用户。
 *
 * 顺序是有讲究的：**中断优先于疲劳**。刚回来的人看到的第一句话应该是"欢迎回来"，
 * 而不是"你看起来有点累"——他今天还没答几道题，谈不上累。
 */
fun mood(daysAway: Int, fatigue: Fatigue): Mood = when {
    daysAway >= COMEBACK_AFTER_DAYS -> Mood.Comeback
    fatigue == Fatigue.Tired -> Mood.Tired
    else -> Mood.Normal
}
