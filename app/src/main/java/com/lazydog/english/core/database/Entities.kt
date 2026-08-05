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
    /** 词性（如 "v."/"n."/"adj."），CEFR 设计文档 §6.2 的词义单位字段。 */
    @ColumnInfo(defaultValue = "''") val pos: String = "",
    /** 1~2 个高价值搭配，JSON 字符串数组（§6.5"每个附带 1~2 个高价值搭配"）。 */
    @ColumnInfo(defaultValue = "'[]'") val collocationsJson: String = "[]",
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
    /** 唯一键；新数据等于 patternEn，旧数据保留原 name。 */
    val name: String,
    @ColumnInfo(defaultValue = "''") val patternEn: String = "",
    @ColumnInfo(defaultValue = "''") val labelZh: String = "",
    @ColumnInfo(defaultValue = "''") val summaryZh: String = "",
    val explanationZh: String,
    val exampleEn: String,
    @ColumnInfo(defaultValue = "''") val exampleZh: String = "",
    @ColumnInfo(defaultValue = "''") val badExampleEn: String = "",
    @ColumnInfo(defaultValue = "''") val badExampleNoteZh: String = "",
    @ColumnInfo(defaultValue = "''") val tipZh: String = "",
)

/**
 * 追加式学习事件。掌握状态要能从事件流重算（AGENTS.md §6），
 * 所以事件只增不改不删。
 */
/**
 * 阅读材料（ARCHITECTURE.md §5 ReadingMaterial）。
 * 目标词/语法/题目以 JSON 内嵌存储：它们只随材料整取整存，不值得拆表。
 * 生成参数（model / promptVersion / schemaVersion / 校验备注）一并保存，便于复现排错。
 */
@Entity(tableName = "reading_materials")
data class ReadingMaterialEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val body: String,
    /** ai / pasted */
    val source: String,
    val topic: String,
    val estimatedCefr: String,
    val targetWordsJson: String,
    val grammarJson: String,
    val questionsJson: String,
    val model: String,
    val promptVersion: Int,
    val schemaVersion: Int,
    val validationNotes: String,
    val createdAt: Long,
)

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

/** 可中断恢复的情景演练。具体快照作为一个整体存 JSON，避免每轮对话拆成多张表。 */
@Entity(
    tableName = "scenario_sessions",
    indices = [Index(value = ["updatedAt"]), Index(value = ["scenarioId"])],
)
data class ScenarioSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val scenarioId: String,
    val titleZh: String,
    /** Brief / Conversation / Summary / Replay / Finished */
    val stage: String,
    val snapshotJson: String,
    val createdAt: Long,
    val updatedAt: Long,
)
