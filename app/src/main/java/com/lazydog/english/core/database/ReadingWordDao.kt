package com.lazydog.english.core.database

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * 渐进学词的义项级阅读进度（D-100，`读书与网络阅读DESIGN.md` §12.5）。全局一份，书和网页共用。
 *
 * 键是稳定义项标识 `原型#词性#义项序号`（`ProgressiveRules.senseId`）。「见过」和「证明过」分开记：
 * 有效展示推进复现计划，只有主动回想（P2）能确认熟悉。
 */
@Entity(tableName = "reading_words")
data class ReadingWordEntity(
    @PrimaryKey val senseId: String,
    val lemma: String,
    /** `PartOfSpeech.wire`。 */
    val pos: String,
    val senseOrder: Int,
    /** 第一次引入时的中文写法，词表里显示用。 */
    val zh: String,
    /** `ReadingWordState` 的序号。 */
    val state: Int,
    val firstShownAt: Long,
    val lastEffectiveAt: Long,
    val nextDueAt: Long,
    /** 复现计划推进到第几步。 */
    val step: Int,
    val effectiveCount: Int,
    val lookups: Int,
    /** 最近一次出现、查义或回想的时间：连续 14 天没有就释放出池。 */
    val lastSignalAt: Long,
    /** 作为新义项引入的那一天（epoch day）；迁移来的、回池的为 -1，不算当天额度。 */
    val introducedDay: Long,
    @ColumnInfo(defaultValue = "0") val pausedAt: Long = 0,
    @ColumnInfo(defaultValue = "0") val recallSuccesses: Int = 0,
    @ColumnInfo(defaultValue = "0") val recallFailures: Int = 0,
    @ColumnInfo(defaultValue = "0") val lastRecallAt: Long = 0,
    /** 转生词本后对应的知识项。 */
    val vocabularyItemId: Long? = null,
    /** `migrated`：从书级词表迁来，只说明"曾见过"，不算回想证据。 */
    @ColumnInfo(defaultValue = "''") val origin: String = "",
)

/**
 * 一次有效展示的原文实例（§12.5「原文实例」）：同一内容版本、同一位置、同一义项只记一次，翻回、重绘不重复计。
 * 也用来在回到这一章时原样放回已经展示过的替换。
 */
@Entity(tableName = "reading_exposures", primaryKeys = ["contentKey", "sourceStart", "senseId"])
data class ReadingExposureEntity(
    /** `book:<书 id>:<章>:<正文版本>`。 */
    val contentKey: String,
    val sourceStart: Int,
    val senseId: String,
    val shownAt: Long,
    /** 这一处是不是作为新义项引入的。 */
    val introduced: Boolean,
    /** 这一处点开看过意思：同一实例重复点只记一次查义。 */
    val lookedUp: Boolean,
)

@Dao
interface ReadingWordDao {

    @Query("SELECT * FROM reading_words")
    suspend fun all(): List<ReadingWordEntity>

    @Query("SELECT * FROM reading_words")
    fun observeAll(): Flow<List<ReadingWordEntity>>

    @Query("SELECT * FROM reading_words WHERE senseId = :senseId")
    suspend fun get(senseId: String): ReadingWordEntity?

    @Query("SELECT COUNT(*) FROM reading_words WHERE state = 0")
    suspend fun activeCount(): Int

    @Query("SELECT COUNT(*) FROM reading_words WHERE introducedDay = :day")
    suspend fun introducedOn(day: Long): Int

    @Upsert
    suspend fun upsert(word: ReadingWordEntity)

    @Upsert
    suspend fun upsertAll(words: List<ReadingWordEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(words: List<ReadingWordEntity>)

    @Query("SELECT * FROM reading_exposures WHERE contentKey = :contentKey")
    suspend fun exposures(contentKey: String): List<ReadingExposureEntity>

    @Query("SELECT * FROM reading_exposures WHERE contentKey = :contentKey AND sourceStart = :sourceStart AND senseId = :senseId")
    suspend fun exposure(contentKey: String, sourceStart: Int, senseId: String): ReadingExposureEntity?

    @Upsert
    suspend fun upsertExposure(exposure: ReadingExposureEntity)

    @Query("DELETE FROM reading_words")
    suspend fun clearAll()

    /**
     * 记一次有效展示（§12.3.2 第 3 条、§12.4）。入池、扣额度、推进复现计划在一个事务里：
     * 同一实例只记一次；新义项要池和当天额度都有空才入池；回池的不算当天新义项；到期才推进。
     *
     * @return 这次展示让一个义项进了学习池（用掉一次新词机会）。
     */
    @Transaction
    suspend fun recordShown(
        exposure: ReadingExposureEntity,
        fresh: ReadingWordEntity,
        day: Long,
        poolCap: Int,
        dailyCap: Int,
        intervalMillis: (Int) -> Long,
        reenterAfter: Long,
    ): Boolean {
        if (exposure(exposure.contentKey, exposure.sourceStart, exposure.senseId) != null) return false
        val now = exposure.shownAt
        val existing = get(exposure.senseId)
        var entered = false
        when {
            existing == null -> {
                if (exposure.introduced && activeCount() < poolCap && introducedOn(day) < dailyCap) {
                    upsert(fresh.copy(introducedDay = day, nextDueAt = now + intervalMillis(0)))
                    entered = true
                }
            }
            existing.state == STATE_PAUSED && exposure.introduced && now - existing.pausedAt >= reenterAfter -> {
                if (activeCount() < poolCap) {
                    upsert(existing.copy(state = STATE_ACTIVE, step = 0, nextDueAt = now + intervalMillis(0), lastSignalAt = now, firstShownAt = now, pausedAt = 0))
                    entered = true
                }
            }
            existing.state == STATE_ACTIVE && now >= existing.nextDueAt -> {
                val step = existing.step + 1
                upsert(
                    existing.copy(
                        step = step,
                        effectiveCount = existing.effectiveCount + 1,
                        lastEffectiveAt = now,
                        nextDueAt = now + intervalMillis(step),
                        lastSignalAt = now,
                    ),
                )
            }
            existing.state == STATE_ACTIVE -> upsert(existing.copy(lastSignalAt = now))
        }
        upsertExposure(exposure.copy(introduced = entered))
        return entered
    }

    /** 查义：同一实例只记一次（§12.4）。不降级。 */
    @Transaction
    suspend fun recordLookup(contentKey: String, sourceStart: Int, senseId: String, now: Long) {
        val row = exposure(contentKey, sourceStart, senseId)
        if (row?.lookedUp == true) return
        if (row != null) upsertExposure(row.copy(lookedUp = true))
        get(senseId)?.let { upsert(it.copy(lookups = it.lookups + 1, lastSignalAt = now)) }
    }

    companion object {
        const val STATE_ACTIVE = 0
        const val STATE_PAUSED = 1
    }
}
