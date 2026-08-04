package com.lazydog.english.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 知识项主表：单词和语法共用身份与调度字段（ARCHITECTURE.md §5）。
 * 详细内容分别放 vocabulary_details / grammar_details，不做超宽表。
 * 时间一律存 epoch 毫秒；stage 由调度状态推导后冗余存储，方便列表查询。
 */
@Entity(tableName = "knowledge_items")
data class KnowledgeItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** KnowledgeType.name */
    val type: String,
    /** KnowledgeStage.name */
    val stage: String,
    val stability: Double,
    val difficulty: Double,
    val reviewCount: Int,
    val lapseCount: Int,
    val lastReviewedAt: Long?,
    val nextReviewAt: Long?,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "vocabulary_details",
    foreignKeys = [
        ForeignKey(
            entity = KnowledgeItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["itemId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["term"], unique = true)],
)
data class VocabularyDetailEntity(
    @PrimaryKey val itemId: Long,
    val term: String,
    val ipa: String,
    val meaningZh: String,
    val exampleEn: String,
    val exampleZh: String,
)

@Entity(
    tableName = "grammar_details",
    foreignKeys = [
        ForeignKey(
            entity = KnowledgeItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["itemId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["name"], unique = true)],
)
data class GrammarDetailEntity(
    @PrimaryKey val itemId: Long,
    val name: String,
    val explanationZh: String,
    val exampleEn: String,
)

/**
 * 追加式学习事件。掌握状态要能从事件流重算（AGENTS.md §6），
 * 所以事件只增不改不删。
 */
@Entity(
    tableName = "learning_events",
    foreignKeys = [
        ForeignKey(
            entity = KnowledgeItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["itemId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["itemId"]), Index(value = ["occurredAt"])],
)
data class LearningEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val itemId: Long,
    /** 来源：card / reading / speaking / quiz（M1 只有 card）。 */
    val source: String,
    /** 活动类型：review / create 等。 */
    val activity: String,
    /** ReviewGrade.name；创建等非复习事件为 null。 */
    val rating: String?,
    @ColumnInfo(defaultValue = "NULL") val responseMillis: Long?,
    val occurredAt: Long,
)
