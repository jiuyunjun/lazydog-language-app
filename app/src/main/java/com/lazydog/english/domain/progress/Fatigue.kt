package com.lazydog.english.domain.progress

/**
 * 疲劳信号（`持续学习DESIGN.md` §25）。
 *
 * 文档列了六种信号（退出次数、连续答错、反应时间变长、跳过增多、提示使用率上升、
 * 最近七天学习时间下降）。这里只用**今天做了多少次**和**最近连着错了几次**两个——
 * 其余几个要么没有在记（退出、跳过），要么需要一条个人基线才有意义（反应时间：
 * 慢下来多少算慢，得先知道这个人平时多快，而这需要长期数据）。
 *
 * 宁可信号少而准：误判成"你累了"然后劝人收工，比不判更糟。
 */
enum class Fatigue { Fine, Tired }

/** 今天至少做到这么多次提取，才谈得上累。 */
const val FATIGUE_MIN_RETRIEVALS = 12

/** 看最近这几次的手感。 */
const val FATIGUE_RECENT_WINDOW = 6

/** 最近这几次里错到这个数，就是明显撑不住了。 */
const val FATIGUE_RECENT_MISSES = 3

/**
 * [todayEvents] 是今天的事件，按时间升序。
 *
 * 两个条件必须同时满足：做得够多、而且**最近**开始连着错。只看错误率会把
 * "今天状态不好"和"这批词本来就难"混在一起；加上"今天已经做了不少"这个前提，
 * 更接近真的累了。
 */
fun fatigue(todayEvents: List<ProgressEvent>): Fatigue {
    val retrievals = todayEvents.filter { it.activity == ProgressActivity.Review && it.remembered != null }
    if (retrievals.size < FATIGUE_MIN_RETRIEVALS) return Fatigue.Fine
    val recentMisses = retrievals.takeLast(FATIGUE_RECENT_WINDOW).count { it.remembered == false }
    return if (recentMisses >= FATIGUE_RECENT_MISSES) Fatigue.Tired else Fatigue.Fine
}
