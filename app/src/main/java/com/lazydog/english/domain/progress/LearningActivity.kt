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
    /** 当前连续学习天数。按每周允许休一天算，见 [learningActivity]。 */
    val currentStreak: Int,
    /** 当前这段连续里，用掉了几天"每周一休"。用来在界面上说清楚连续为什么没断。 */
    val restDaysUsed: Int,
    /** 距上一次学习过去了几天。今天学过就是 0，从来没学过也是 0。 */
    val daysAway: Int,
) {
    companion object {
        val None = LearningActivity(
            journeyDays = 0,
            activeDaysIn30 = 0,
            currentStreak = 0,
            restDaysUsed = 0,
            daysAway = 0,
        )
    }
}

/** 最近多少天算"最近"（§7.1 的 27 / 30）。 */
const val RECENT_WINDOW_DAYS = 30

/**
 * 每周允许休一天：两次"休息"之间至少隔这么多天（§7.2）。
 *
 * 不做冻结券、不做补签：那是一种要攒、要花、还要有界面管的虚拟货币，
 * 和文档 §27 的奖励优先级（能力反馈 > … > 虚拟货币）正好相反。宽容应该是默认就在的，
 * 不该是一件需要经营的事。
 */
const val REST_EVERY_DAYS = 7

/**
 * [activeDays] 是学过的日期集合（本地时区），[today] 是今天。
 *
 * 连续天数有两处是故意的：
 *
 * 1. **今天还没学不算断**，从昨天往回数。否则每天零点一过 streak 就归零，
 *    用户睁眼看到的是"连续 0 天"——那是在为难人，不是在记录事实。
 * 2. **每七天允许缺一天**（§7.2）。一次中断不该摧毁长期积累；但两天连着缺就是真的断了，
 *    因为第二天离上一次休息不足七天。
 */
fun learningActivity(activeDays: Set<LocalDate>, today: LocalDate): LearningActivity {
    if (activeDays.isEmpty()) return LearningActivity.None

    val first = activeDays.min()
    val journeyDays = (ChronoUnit.DAYS.between(first, today) + 1).toInt().coerceAtLeast(1)

    val windowStart = today.minusDays(RECENT_WINDOW_DAYS - 1L)
    val activeIn30 = activeDays.count { !it.isBefore(windowStart) && !it.isAfter(today) }

    val lastActive = activeDays.filter { !it.isAfter(today) }.maxOrNull()
    val daysAway = lastActive?.let { ChronoUnit.DAYS.between(it, today).toInt() } ?: 0

    var cursor = if (today in activeDays) today else today.minusDays(1)
    var streak = 0
    var rests = 0
    /** 已经用掉、但还没被后面的学习日"接住"的休息，见下面为什么要分开算。 */
    var pendingRests = 0
    var lastRest: LocalDate? = null
    while (true) {
        if (cursor in activeDays) {
            streak += 1
            // 休息只有在它后面还接着学习日时才算数：走到头那次休息什么都没保住，
            // 报出来会变成"连续 1 天 · 休过 1 天"这种看不懂的话。
            rests += pendingRests
            pendingRests = 0
            cursor = cursor.minusDays(1)
            continue
        }
        // 缺了一天：离上一次休息够远就当成"这周的休息"，否则连续到此为止。
        // 这里不能要求"已经数到了至少一天"——周一到周六学了、周日休息、今天周一还没学，
        // 那六天的连续正是这条规则要保住的东西。
        val canRest = lastRest == null || ChronoUnit.DAYS.between(cursor, lastRest) >= REST_EVERY_DAYS
        if (!canRest) break
        pendingRests += 1
        lastRest = cursor
        cursor = cursor.minusDays(1)
    }

    return LearningActivity(
        journeyDays = journeyDays,
        activeDaysIn30 = activeIn30,
        currentStreak = streak,
        // 连续已经是 0 了就没有"用掉的休息"可言——那只是很久没学，不是休了一天。
        restDaysUsed = if (streak == 0) 0 else rests,
        daysAway = daysAway,
    )
}
