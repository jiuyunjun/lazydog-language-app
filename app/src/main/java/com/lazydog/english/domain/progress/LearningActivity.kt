package com.lazydog.english.domain.progress

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 学习活跃度的三个数（`持续学习DESIGN.md` §7.1）。
 *
 * 只显示一个 🔥 连续天数的问题是：断一次就归零，前面攒的全看不见了，
 * 而人恰恰是在断掉的那天最需要一个回来的理由。所以三个数一起给——
 * 连续断了，旅程和最近三十天还在。
 */
data class LearningActivity(
    /** 从第一次学习那天到今天，走了多少天。断过也算在里面，这是"旅程"不是"连续"。 */
    val journeyDays: Int,
    /** 最近 30 天里有多少天学过。 */
    val activeDaysIn30: Int,
    /** 当前连续学习天数。 */
    val currentStreak: Int,
) {
    companion object {
        val None = LearningActivity(journeyDays = 0, activeDaysIn30 = 0, currentStreak = 0)
    }
}

/** 最近多少天算"最近"（§7.1 的 27 / 30）。 */
const val RECENT_WINDOW_DAYS = 30

/**
 * [activeDays] 是学过的日期集合（本地时区），[today] 是今天。
 *
 * 连续天数的算法有一处是故意的：**今天还没学不算断**。
 * 今天没学就从昨天往回数，否则每天零点一过 streak 就归零，用户睁眼看到的是"连续 0 天"——
 * 那是在为难人，不是在记录事实。今天和昨天都没学，才真的断了。
 */
fun learningActivity(activeDays: Set<LocalDate>, today: LocalDate): LearningActivity {
    if (activeDays.isEmpty()) return LearningActivity.None

    val first = activeDays.min()
    val journeyDays = (ChronoUnit.DAYS.between(first, today) + 1).toInt().coerceAtLeast(1)

    val windowStart = today.minusDays(RECENT_WINDOW_DAYS - 1L)
    val activeIn30 = activeDays.count { !it.isBefore(windowStart) && !it.isAfter(today) }

    var cursor = if (today in activeDays) today else today.minusDays(1)
    var streak = 0
    while (cursor in activeDays) {
        streak += 1
        cursor = cursor.minusDays(1)
    }

    return LearningActivity(
        journeyDays = journeyDays,
        activeDaysIn30 = activeIn30,
        currentStreak = streak,
    )
}
