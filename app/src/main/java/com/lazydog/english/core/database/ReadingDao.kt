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

    // ---- 备份 / 恢复（core/backup/BackupRepository）----

    @Query("SELECT * FROM reading_materials")
    suspend fun getAllMaterials(): List<ReadingMaterialEntity>

    @Query("DELETE FROM reading_materials")
    suspend fun clearAll()
}
