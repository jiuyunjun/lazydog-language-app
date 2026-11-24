package com.lazydog.english.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 熟词本（D-099）：你说你认识的英文词。
 *
 * 两种来源：
 * - 只是一个词（在书里、别处点了「我认识」）：[itemId] 为空，没有词卡，也不复习；
 * - 从生词本归档进来的：[itemId] 指向原来那张卡，卡和学习记录都留着，只是暂停复习；移出熟词本就回到复习里。
 *
 * 不论哪种，效果都一样：书里不再换这个词、每日新词不再推它、阅读难度算它认识。
 */
@Entity(tableName = "known_words")
data class KnownWordEntity(
    /** 英文原型，小写。 */
    @PrimaryKey val lemma: String,
    val itemId: Long?,
    /** `book`（书里标的）、`vocabulary`（从生词本归档）、`other`。 */
    val source: String,
    val addedAt: Long,
)

@Dao
interface KnownWordDao {

    @Query("SELECT * FROM known_words ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<KnownWordEntity>>

    @Query("SELECT * FROM known_words")
    suspend fun getAll(): List<KnownWordEntity>

    @Query("SELECT * FROM known_words WHERE lemma = :lemma")
    suspend fun get(lemma: String): KnownWordEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(word: KnownWordEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(words: List<KnownWordEntity>)

    @Query("DELETE FROM known_words WHERE lemma = :lemma")
    suspend fun delete(lemma: String)

    @Query("DELETE FROM known_words")
    suspend fun clearAll()
}
