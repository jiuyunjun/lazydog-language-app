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
}
