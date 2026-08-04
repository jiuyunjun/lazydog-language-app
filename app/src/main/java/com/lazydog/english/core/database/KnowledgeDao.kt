package com.lazydog.english.core.database

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

data class VocabularyRecord(
    @Embedded val item: KnowledgeItemEntity,
    @Relation(parentColumn = "id", entityColumn = "itemId")
    val detail: VocabularyDetailEntity,
)

data class GrammarRecord(
    @Embedded val item: KnowledgeItemEntity,
    @Relation(parentColumn = "id", entityColumn = "itemId")
    val detail: GrammarDetailEntity,
)

@Dao
interface KnowledgeDao {

    @Transaction
    @Query("SELECT * FROM knowledge_items WHERE type = 'Vocabulary' ORDER BY nextReviewAt ASC")
    fun observeVocabulary(): Flow<List<VocabularyRecord>>

    @Transaction
    @Query("SELECT * FROM knowledge_items WHERE type = 'Grammar' ORDER BY nextReviewAt ASC")
    fun observeGrammar(): Flow<List<GrammarRecord>>

    @Query("SELECT COUNT(*) FROM knowledge_items WHERE nextReviewAt IS NOT NULL AND nextReviewAt <= :now")
    fun observeDueCount(now: Long): Flow<Int>

    @Query("SELECT * FROM knowledge_items WHERE id = :id")
    suspend fun getItem(id: Long): KnowledgeItemEntity?

    @Insert
    suspend fun insertItem(item: KnowledgeItemEntity): Long

    @Insert
    suspend fun insertVocabularyDetail(detail: VocabularyDetailEntity)

    @Insert
    suspend fun insertGrammarDetail(detail: GrammarDetailEntity)

    @Update
    suspend fun updateItem(item: KnowledgeItemEntity)

    @Insert
    suspend fun insertEvent(event: LearningEventEntity): Long

    @Query("SELECT EXISTS(SELECT 1 FROM vocabulary_details WHERE term = :term)")
    suspend fun vocabularyTermExists(term: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM grammar_details WHERE name = :name)")
    suspend fun grammarNameExists(name: String): Boolean

    @Query("DELETE FROM knowledge_items WHERE id = :id")
    suspend fun deleteItem(id: Long)
}
