package com.lazydog.english.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DrillMistakeDao {

    @Insert
    suspend fun insert(mistake: DrillMistakeEntity): Long

    /** 最近一段时间的错题，按时间倒序；聚合在领域层做，SQL 只管取。 */
    @Query("SELECT * FROM drill_mistakes WHERE occurredAt >= :since ORDER BY occurredAt DESC LIMIT :limit")
    fun observeSince(since: Long, limit: Int = 200): Flow<List<DrillMistakeEntity>>

    @Query("SELECT * FROM drill_mistakes WHERE occurredAt >= :since ORDER BY occurredAt DESC LIMIT :limit")
    suspend fun getSince(since: Long, limit: Int = 200): List<DrillMistakeEntity>

    @Query("SELECT * FROM drill_mistakes")
    suspend fun getAll(): List<DrillMistakeEntity>

    /** 错题只保留一段时间，避免半年前的老毛病一直左右今天讲什么。 */
    @Query("DELETE FROM drill_mistakes WHERE occurredAt < :before")
    suspend fun deleteBefore(before: Long)

    @Query("DELETE FROM drill_mistakes")
    suspend fun clearAll()
}
