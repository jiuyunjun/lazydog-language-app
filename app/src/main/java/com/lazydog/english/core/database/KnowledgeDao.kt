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

    @Query("SELECT * FROM vocabulary_details WHERE itemId = :itemId")
    suspend fun getVocabularyDetail(itemId: Long): VocabularyDetailEntity?

    /**
     * 大小写不敏感：`Went` 和 `went` 是同一个词，各存一条的话它们会各自走一遍
     * S0～S6、各攒一份画像，用户以为在练一个词，系统当成两个。
     */
    @Query("SELECT EXISTS(SELECT 1 FROM vocabulary_details WHERE term = :term COLLATE NOCASE)")
    suspend fun vocabularyTermExists(term: String): Boolean

    /**
     * 同一个词条（lemma + 词性）下已经存了哪些词义。
     *
     * 身份键是 (lemma, pos) 而不是 term（单词记忆DESIGN.md §3、§12）：`record/NOUN`
     * 和 `record/VERB` 是两个词条，而 `run/VERB` 的"跑"和"经营"是同一个词条的两个词义。
     */
    @Query(
        "SELECT * FROM vocabulary_details WHERE term = :lemma COLLATE NOCASE " +
            "AND pos = :pos COLLATE NOCASE ORDER BY senseOrder",
    )
    suspend fun getSensesOf(lemma: String, pos: String): List<VocabularyDetailEntity>

    @Transaction
    @Query(
        "SELECT * FROM knowledge_items WHERE id = " +
            "(SELECT itemId FROM vocabulary_details WHERE term = :term COLLATE NOCASE LIMIT 1)",
    )
    suspend fun getVocabularyByTerm(term: String): VocabularyRecord?

    @Query("SELECT EXISTS(SELECT 1 FROM grammar_details WHERE name = :name)")
    suspend fun grammarNameExists(name: String): Boolean

    @Query("DELETE FROM knowledge_items WHERE id = :id")
    suspend fun deleteItem(id: Long)

    // ---- 备份 / 恢复（core/backup/BackupRepository）----

    @Query("SELECT * FROM knowledge_items")
    suspend fun getAllItems(): List<KnowledgeItemEntity>

    @Query("SELECT * FROM vocabulary_details")
    suspend fun getAllVocabularyDetails(): List<VocabularyDetailEntity>

    @Query("SELECT * FROM grammar_details")
    suspend fun getAllGrammarDetails(): List<GrammarDetailEntity>

    @Query("SELECT * FROM learning_events")
    suspend fun getAllEvents(): List<LearningEventEntity>

    /** 级联删除 vocabulary_details / grammar_details / learning_events（外键 CASCADE）。 */
    @Query("DELETE FROM knowledge_items")
    suspend fun clearAll()
}
