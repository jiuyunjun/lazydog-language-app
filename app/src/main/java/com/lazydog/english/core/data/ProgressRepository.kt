package com.lazydog.english.core.data

import com.lazydog.english.core.database.AppDatabase
import com.lazydog.english.core.database.LearningEventEntity
import com.lazydog.english.core.database.SpellingAttemptEntity
import com.lazydog.english.core.model.ReviewGrade
import com.lazydog.english.domain.progress.DailyProgress
import com.lazydog.english.domain.progress.DifficultyBias
import com.lazydog.english.domain.progress.Fatigue
import com.lazydog.english.domain.progress.LearningActivity
import com.lazydog.english.domain.progress.LongTermProof
import com.lazydog.english.domain.progress.PROOF_MIN_GAP_DAYS
import com.lazydog.english.domain.progress.SpellingMoment
import com.lazydog.english.domain.progress.ProgressActivity
import com.lazydog.english.domain.progress.ProgressEvent
import com.lazydog.english.domain.progress.dailyProgress
import com.lazydog.english.domain.progress.difficultyBias
import com.lazydog.english.domain.progress.fatigue
import com.lazydog.english.domain.progress.learningActivity
import com.lazydog.english.domain.progress.longTermProof
import com.lazydog.english.domain.progress.recentAccuracy
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 进步证据的数据来源（`持续学习DESIGN.md` §14、§7.1）。
 *
 * 不新增任何表：战报和活跃度全部从既有的 `learning_events` 推出来。学习行为本来就在落库，
 * 再存一份"今天学了什么"只会多一处可能和事实对不上的地方。
 *
 * 算法都在 `domain/progress`，这里只负责取数和把 Room 的行翻译成领域事件。
 */
class ProgressRepository(
    database: AppDatabase,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val today: () -> LocalDate = { LocalDate.now(ZoneId.systemDefault()) },
) {

    private val dao = database.knowledgeDao()
    private val spellingDao = database.spellingDao()

    /**
     * 今天的战报。查询窗口回看 [RECOVERY_LOOKBACK_DAYS] 天，因为"重新记住了"要知道
     * 这个词上一次是不是栽了——只查今天的话，永远算不出翻身。
     */
    fun observeToday(): Flow<TodayReport> {
        val since = Instant.now().minusSeconds(RECOVERY_LOOKBACK_DAYS * 86_400L)
        return dao.observeEventsSince(since.toEpochMilli()).map { rows ->
            val events = rows.map { it.toProgressEvent() }
            val progress = dailyProgress(events, today(), zone())
            val todayEvents = events.filter { it.at.atZone(zone()).toLocalDate() == today() }
            TodayReport(
                progress = progress,
                recoveredNames = namesOf(progress.recovered),
                fatigue = fatigue(todayEvents),
                proof = longTermProof(),
            )
        }
    }

    /**
     * 最近的提取成功率给出的难度偏置（§11）。
     *
     * 只回看 [ACCURACY_LOOKBACK_DAYS] 天：更早的表现说明不了今天的状态，
     * 而调难度要的正是"今天状态如何"。
     */
    fun observeDifficulty(): Flow<DifficultyBias> {
        val since = Instant.now().minusSeconds(ACCURACY_LOOKBACK_DAYS * 86_400L)
        return dao.observeEventsSince(since.toEpochMilli()).map { rows ->
            difficultyBias(recentAccuracy(rows.map { it.toProgressEvent() }))
        }
    }

    /** 学习旅程 / 最近 30 天 / 当前连续（§7.1）。 */
    fun observeActivity(): Flow<LearningActivity> =
        dao.observeActiveDays().map { days ->
            learningActivity(days.mapNotNull(::parseDay).toSet(), today())
        }

    /**
     * "你以前不会，现在会了"（§14.3）。
     *
     * 两条查询都很窄：最近几天无提示写对的那些词，加上这些词在更早以前写错的记录。
     * 配不上就返回 null——没有证据时什么都不说，比说一句空话强。
     */
    private suspend fun longTermProof(): LongTermProof? {
        val now = Instant.now()
        val successes = spellingDao.recentUnaidedSuccesses(
            since = now.minusSeconds(PROOF_SUCCESS_WINDOW_DAYS * 86_400L).toEpochMilli(),
        )
        if (successes.isEmpty()) return null
        val mistakes = spellingDao.earlierMistakes(
            itemIds = successes.map { it.itemId }.distinct(),
            before = now.minusSeconds(PROOF_MIN_GAP_DAYS * 86_400L).toEpochMilli(),
        )
        return longTermProof(
            recentSuccesses = successes.map { it.toSpellingMoment() },
            olderMistakes = mistakes.map { it.toSpellingMoment() },
            now = now,
        )
    }

    /** 翻身的那几个知识点显示成什么。查不到就不显示，宁可少说一个也不要显示一个空名字。 */
    private suspend fun namesOf(itemIds: List<Long>): List<String> {
        if (itemIds.isEmpty()) return emptyList()
        return dao.vocabularyTerms(itemIds) + dao.grammarNames(itemIds)
    }

    /** SQLite 给的是 `yyyy-MM-dd`；万一是别的形状（时区异常）就丢掉这一天，不让它拖垮整列。 */
    private fun parseDay(raw: String): LocalDate? = runCatching { LocalDate.parse(raw) }.getOrNull()

    private companion object {
        const val RECOVERY_LOOKBACK_DAYS = 120L
        const val ACCURACY_LOOKBACK_DAYS = 21L

        /** 长期证明只认最近几天刚写对的：太久以前的成功说明不了"现在会了"。 */
        const val PROOF_SUCCESS_WINDOW_DAYS = 7L
    }
}

/** 今天的战报加上可以直接显示的名字。 */
data class TodayReport(
    val progress: DailyProgress,
    val recoveredNames: List<String>,
    /** 今天看起来累不累（§25）。今天的事件本来就取到了，顺手算，不额外查一次。 */
    val fatigue: Fatigue = Fatigue.Fine,
    /** "你以前不会，现在会了"（§14.3）。配不出来就是 null。 */
    val proof: LongTermProof? = null,
) {
    companion object {
        val Empty = TodayReport(DailyProgress.Empty, emptyList())
    }
}

private fun SpellingAttemptEntity.toSpellingMoment() = SpellingMoment(
    itemId = itemId,
    expected = expected,
    answer = answer,
    correct = correct,
    hintLevel = hintLevel,
    at = Instant.ofEpochMilli(occurredAt),
)

private fun LearningEventEntity.toProgressEvent() = ProgressEvent(
    itemId = itemId,
    activity = when (activity) {
        "create" -> ProgressActivity.Create
        "review" -> ProgressActivity.Review
        else -> ProgressActivity.Exposure
    },
    // 四档里只有 Forgot 算没想起来；Hard 是想起来了但费劲，仍然是一次成功的提取。
    remembered = rating?.let { it != ReviewGrade.Forgot.name },
    at = Instant.ofEpochMilli(occurredAt),
)
