package com.lazydog.english.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ScenarioSessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(entity: ScenarioSessionEntity): Long

    @Query("SELECT * FROM scenario_sessions WHERE id = :id")
    suspend fun getById(id: Long): ScenarioSessionEntity?

    @Query("SELECT * FROM scenario_sessions ORDER BY updatedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 20): Flow<List<ScenarioSessionEntity>>

    @Query("SELECT * FROM scenario_sessions")
    suspend fun getAll(): List<ScenarioSessionEntity>

    @Query("DELETE FROM scenario_sessions")
    suspend fun clearAll()
}
