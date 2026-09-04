package com.lazydog.english.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ListeningMaterialDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(material: ListeningMaterialEntity): Long

    @Query(
        "UPDATE listening_materials SET payloadJson = :payloadJson, sceneZh = :sceneZh, " +
            "lastHeardAt = :heardAt, playCount = playCount + 1 WHERE normalizedText = :normalizedText",
    )
    suspend fun recordPlay(
        normalizedText: String,
        payloadJson: String,
        sceneZh: String,
        heardAt: Long,
    )

    @Query(
        "UPDATE listening_materials SET model = :model, promptVersion = :promptVersion, " +
            "schemaVersion = :schemaVersion, generationRequestJson = :requestJson " +
            "WHERE normalizedText IN (:normalizedTexts)",
    )
    suspend fun attachGenerationMetadata(
        normalizedTexts: List<String>,
        model: String,
        promptVersion: Int,
        schemaVersion: Int,
        requestJson: String,
    )

    @Query("SELECT * FROM listening_materials ORDER BY lastHeardAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 20): Flow<List<ListeningMaterialEntity>>

    @Query("SELECT textEn FROM listening_materials ORDER BY lastHeardAt DESC LIMIT :limit")
    suspend fun recentTexts(limit: Int): List<String>

    @Query("SELECT * FROM listening_materials")
    suspend fun getAll(): List<ListeningMaterialEntity>

    @Query("DELETE FROM listening_materials")
    suspend fun clearAll()

    // ---- 作答记录（听力画像、跨轮次统计）----

    @Insert
    suspend fun insertAttempt(attempt: ListeningAttemptEntity): Long

    @Query("SELECT * FROM listening_attempts ORDER BY occurredAt ASC")
    fun observeAttempts(): Flow<List<ListeningAttemptEntity>>

    @Query("SELECT * FROM listening_attempts")
    suspend fun getAllAttempts(): List<ListeningAttemptEntity>

    @Query("DELETE FROM listening_attempts")
    suspend fun clearAllAttempts()
}
