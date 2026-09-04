package com.lazydog.english.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ReadingDao {

    @Insert
    suspend fun insert(material: ReadingMaterialEntity): Long

    @Query("SELECT * FROM reading_materials ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 20): Flow<List<ReadingMaterialEntity>>

    @Query("SELECT * FROM reading_materials WHERE id = :id")
    suspend fun getById(id: Long): ReadingMaterialEntity?

    @Query("DELETE FROM reading_materials WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE reading_materials SET completed = 1 WHERE id = :id")
    suspend fun markCompleted(id: Long)

    @Query("UPDATE reading_materials SET liked = :liked WHERE id = :id")
    suspend fun setLiked(id: Long, liked: Boolean)

    @Query("UPDATE reading_materials SET saved = :saved WHERE id = :id")
    suspend fun setSaved(id: Long, saved: Boolean)

    /** 最近几篇，用来避免重复（`引人入胜的阅读材料DESIGN.md` §20）。 */
    @Query("SELECT * FROM reading_materials WHERE source = 'ai' ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recentGenerated(limit: Int): List<ReadingMaterialEntity>

    // ---- 备份 / 恢复（core/backup/BackupRepository）----

    @Query("SELECT * FROM reading_materials")
    suspend fun getAllMaterials(): List<ReadingMaterialEntity>

    @Query("DELETE FROM reading_materials")
    suspend fun clearAll()
}
