package com.lazydog.english.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SpellingDao {
    @Query("SELECT * FROM spelling_progress WHERE itemId = :itemId")
    suspend fun getProgress(itemId: Long): SpellingProgressEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveProgress(progress: SpellingProgressEntity)

    @Insert
    suspend fun insertAttempt(attempt: SpellingAttemptEntity): Long

    @Query("SELECT * FROM spelling_progress")
    suspend fun getAllProgress(): List<SpellingProgressEntity>

    @Query("SELECT * FROM spelling_attempts ORDER BY occurredAt ASC")
    suspend fun getAllAttempts(): List<SpellingAttemptEntity>

    /**
     * 这个词最近写错的几次。生成记忆提示时要把人**真写出来过**的错法带上，
     * 泛泛一句"注意拼写"挡不住 recieve（词汇记忆提示DESIGN.md §11）。
     */
    @Query(
        "SELECT * FROM spelling_attempts WHERE itemId = :itemId AND correct = 0 " +
            "ORDER BY occurredAt DESC LIMIT :limit",
    )
    suspend fun recentWrongAttempts(itemId: Long, limit: Int): List<SpellingAttemptEntity>

    // ---- 长期证明（core/data/ProgressRepository，`持续学习DESIGN.md` §14.3）----

    /**
     * 最近**没用提示**写对的作答。提示答对不算会了，拿它当"你现在会了"的证据
     * 经不起用户自己回想。
     */
    @Query(
        "SELECT * FROM spelling_attempts WHERE correct = 1 AND hintLevel = 0 " +
            "AND occurredAt >= :since ORDER BY occurredAt DESC",
    )
    suspend fun recentUnaidedSuccesses(since: Long): List<SpellingAttemptEntity>

    /** 这些词更早写错的作答，用来和上面那批配对。 */
    @Query(
        "SELECT * FROM spelling_attempts WHERE correct = 0 AND itemId IN (:itemIds) " +
            "AND occurredAt <= :before ORDER BY occurredAt ASC",
    )
    suspend fun earlierMistakes(itemIds: List<Long>, before: Long): List<SpellingAttemptEntity>
}
