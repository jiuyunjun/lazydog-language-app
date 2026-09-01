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
}
