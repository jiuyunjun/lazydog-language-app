package com.lazydog.english.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryHintDao {

    @Query("SELECT * FROM vocabulary_memory_hints WHERE itemId = :itemId")
    fun observeHint(itemId: Long): Flow<VocabularyMemoryHintEntity?>

    @Query("SELECT * FROM vocabulary_memory_hints WHERE itemId = :itemId")
    suspend fun getHint(itemId: Long): VocabularyMemoryHintEntity?

    /** 「再来一条」是覆盖，不是追加：一个词同时留两条提示，等于让用户自己挑一条更好的。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveHint(hint: VocabularyMemoryHintEntity)

    @Query("DELETE FROM vocabulary_memory_hints WHERE itemId = :itemId")
    suspend fun deleteHint(itemId: Long)

    @Query("SELECT * FROM vocabulary_memory_hints")
    suspend fun getAllHints(): List<VocabularyMemoryHintEntity>
}
