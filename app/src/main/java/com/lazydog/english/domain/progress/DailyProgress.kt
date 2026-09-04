package com.lazydog.english.domain.progress

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 学习过程中留下的一条痕迹，是 `learning_events` 去掉存储细节之后的样子。
 *
 * 领域层只关心"这一刻发生了什么"，不关心它来自单词卡还是拼写练习——
 * 进步是同一件事，不该按功能分开算（`持续学习DESIGN.md` §14）。
 */
data class ProgressEvent(
    val itemId: Long,
    val activity: ProgressActivity,
    /** 复习才有：这次算想起来了吗（[com.lazydog.english.core.model.ReviewGrade.Forgot] 之外都算）。 */
    val remembered: Boolean?,
    val at: Instant,
)

enum class ProgressActivity {
    /** 新学了一个知识点。 */
    Create,

    /** 一次主动提取：想起来了或者没想起来。 */
    Review,

    /** 在语境里又遇见了，但没有考。遇见不等于想起来（AGENTS.md §6），所以不计入战报。 */
    Exposure,
}

/**
 * 今天的战报（§14.1、§22）。
 *
 * 刻意不含 XP、积分、时长这类和能力无关的数字：结束页要回答的是
 * "我今天到底会了什么"，而不是"我今天点了多少下"（§31）。
 */
data class DailyProgress(
    /** 今天新学的知识点数。 */
    val learned: Int,
    /** 今天做过的主动提取次数。 */
    val reviewed: Int,
    /** 其中想起来了的次数。 */
    val remembered: Int,
    /** 上次忘了、今天重新想起来的知识点——最值得说的一类进步（§14.1）。 */
    val recovered: List<Long>,
) {

    /** 有没有值得报的东西。全零时不该弹一张空卡片充数。 */
    val hasAnything: Boolean get() = learned > 0 || reviewed > 0

    /** 无提示正确率，百分比；今天还没考过就是 null，而不是 0——没考过不等于全错。 */
    val rememberedPercent: Int? get() = if (reviewed == 0) null else remembered * 100 / reviewed

    companion object {
        val Empty = DailyProgress(learned = 0, reviewed = 0, remembered = 0, recovered = emptyList())
    }
}

/**
 * 算出 [day] 这一天的战报。[events] 要按时间升序，且至少覆盖到这些知识点上一次复习
 * ——[DailyProgress.recovered] 需要知道"上次是不是忘了"。
 *
 * 事件不够久远时只会少报几个 recovered，不会报错：报少了是遗憾，报错了是撒谎。
 */
fun dailyProgress(events: List<ProgressEvent>, day: LocalDate, zone: ZoneId): DailyProgress {
    var learned = 0
    var reviewed = 0
    var remembered = 0
    /** 每个知识点在今天之前最后一次复习的结果。 */
    val lastBefore = mutableMapOf<Long, Boolean>()
    val recovered = mutableSetOf<Long>()

    for (event in events) {
        val onDay = event.at.atZone(zone).toLocalDate() == day
        when (event.activity) {
            ProgressActivity.Create -> if (onDay) learned += 1
            ProgressActivity.Review -> {
                val ok = event.remembered ?: continue
                if (!onDay) {
                    lastBefore[event.itemId] = ok
                    continue
                }
                reviewed += 1
                if (ok) {
                    remembered += 1
                    // 上一次栽在这个词上、今天想起来了：这才是"重新记住了"。
                    // 只认第一次翻身，同一天里反复做对同一个词不该重复报。
                    if (lastBefore[event.itemId] == false) {
                        recovered += event.itemId
                        lastBefore[event.itemId] = true
                    }
                }
            }
            ProgressActivity.Exposure -> Unit
        }
    }
    return DailyProgress(learned, reviewed, remembered, recovered.toList())
}

/**
 * 每日最低目标（§6）：门槛必须低到"今天再累也能过"。
 *
 * 五次主动提取大约两分钟。达标之后 App 只说"今天到这里也够了"，不再劝学，
 * 更不能让"今天到这里"带上罪恶感——那会把低门槛重新变成负担。
 */
const val MINIMUM_RETRIEVALS = 5

fun reachedDailyMinimum(progress: DailyProgress, doneStepCount: Int): Boolean =
    doneStepCount > 0 || progress.reviewed >= MINIMUM_RETRIEVALS
