package com.lazydog.english.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface VocabularyImageDao {

    @Query("SELECT * FROM vocabulary_sense_images WHERE senseKey = :senseKey")
    fun observe(senseKey: String): Flow<VocabularySenseImageEntity?>

    @Query("SELECT * FROM vocabulary_sense_images WHERE senseKey = :senseKey")
    suspend fun get(senseKey: String): VocabularySenseImageEntity?

    @Query("SELECT senseKey FROM vocabulary_sense_images WHERE senseKey IN (:senseKeys)")
    suspend fun existing(senseKeys: List<String>): List<String>

    /** 重搜是覆盖：一个词义留两批候选，等于让用户自己挑一批更好的。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(entity: VocabularySenseImageEntity)

    @Query("DELETE FROM vocabulary_sense_images WHERE senseKey = :senseKey")
    suspend fun delete(senseKey: String)

    @Query("SELECT * FROM vocabulary_sense_images")
    suspend fun getAll(): List<VocabularySenseImageEntity>
}
