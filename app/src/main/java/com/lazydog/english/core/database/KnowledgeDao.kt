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

/** 一个词条和它入库的时间（进步挑战按"多久以前学的"挑词）。 */
data class LearnedTerm(val itemId: Long, val term: String, val createdAt: Long)

@Dao
interface KnowledgeDao {

    @Transaction
    @Query("SELECT * FROM knowledge_items WHERE type = 'Vocabulary' ORDER BY nextReviewAt ASC")
    fun observeVocabulary(): Flow<List<VocabularyRecord>>

    @Transaction
    @Query("SELECT * FROM knowledge_items WHERE type = 'Grammar' ORDER BY nextReviewAt ASC")
    fun observeGrammar(): Flow<List<GrammarRecord>>

    @Query(
        "SELECT COUNT(*) FROM knowledge_items AS item " +
            "INNER JOIN vocabulary_details AS detail ON detail.itemId = item.id " +
            "WHERE item.type = 'Vocabulary' " +
            "AND LOWER(detail.pos) NOT IN ('expression', 'phrase') " +
            "AND item.nextReviewAt IS NOT NULL AND item.nextReviewAt <= :now",
    )
    fun observeDueVocabularyCount(now: Long): Flow<Int>

    @Query(
        "SELECT COUNT(*) FROM knowledge_items " +
            "WHERE type = 'Grammar' AND nextReviewAt IS NOT NULL AND nextReviewAt <= :now",
    )
    fun observeDueGrammarCount(now: Long): Flow<Int>

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

    /** 按身份键查重（`grammarPointKey`）。空键不参与，否则老数据会互相撞上。 */
    @Query("SELECT EXISTS(SELECT 1 FROM grammar_details WHERE canonicalKey != '' AND canonicalKey = :key)")
    suspend fun grammarKeyExists(key: String): Boolean

    @Query("UPDATE grammar_details SET category = :category, canonicalKey = :key WHERE itemId = :itemId")
    suspend fun updateGrammarKey(itemId: Long, category: String, key: String)

    @Query("DELETE FROM knowledge_items WHERE id = :id")
    suspend fun deleteItem(id: Long)

    // ---- 进步证据（core/data/ProgressRepository）----

    /** [since] 之后的学习事件，按时间升序。战报和"重新记住了"都从这一串推。 */
    @Query("SELECT * FROM learning_events WHERE occurredAt >= :since ORDER BY occurredAt ASC")
    fun observeEventsSince(since: Long): Flow<List<LearningEventEntity>>

    /**
     * 学过的日期，本地时区，升序去重。
     *
     * 日期在 SQL 里算：把几万条时间戳拉回内存只为了数有多少个不同的日子，不划算。
     * `localtime` 用的是查询当时的设备时区——跨时区旅行时昨天的边界会跟着走，
     * 这正是用户对"昨天"的理解。
     */
    @Query(
        "SELECT DISTINCT date(occurredAt / 1000, 'unixepoch', 'localtime') " +
            "FROM learning_events ORDER BY 1 ASC",
    )
    fun observeActiveDays(): Flow<List<String>>

    /** 这些知识项显示成什么：词条给词，语法给名字。查不到的（已删掉）不返回。 */
    @Query("SELECT term FROM vocabulary_details WHERE itemId IN (:itemIds)")
    suspend fun vocabularyTerms(itemIds: List<Long>): List<String>

    @Query("SELECT name FROM grammar_details WHERE itemId IN (:itemIds)")
    suspend fun grammarNames(itemIds: List<Long>): List<String>

    // ---- 进步挑战（`持续学习DESIGN.md` §15）----

    /** 这段时间里学的词条，用来出"你还记得两周前那几个词吗"。 */
    @Query(
        "SELECT v.itemId AS itemId, v.term AS term, i.createdAt AS createdAt " +
            "FROM vocabulary_details v JOIN knowledge_items i ON i.id = v.itemId " +
            "WHERE i.createdAt BETWEEN :from AND :to ORDER BY i.createdAt ASC",
    )
    suspend fun itemsLearnedBetween(from: Long, to: Long): List<LearnedTerm>

    /** 关键词识别的干扰项：随便挑几个不在这次句子里的词。 */
    @Query(
        "SELECT term FROM vocabulary_details WHERE itemId NOT IN (:excludedItemIds) " +
            "ORDER BY RANDOM() LIMIT :limit",
    )
    suspend fun randomTermsExcept(excludedItemIds: List<Long>, limit: Int): List<String>

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
