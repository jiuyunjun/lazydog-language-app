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
    /** 记忆方法：词根词缀拆解或联想记忆。老数据为空，UI 按"没有就不显示"处理。 */
    @ColumnInfo(defaultValue = "''") val memoryHintZh: String = "",
    /**
     * 词块拆分，JSON 字符串数组，按顺序拼起来等于 term。
     * 这是词固有的属性，不是从用户历史推出来的，所以和释义一起在第一次落库时就写全；
     * 为空时本地启发式兜底，但启发式拆出来的东西不能拿去当"这里最容易错"讲。
     */
    @ColumnInfo(defaultValue = "'[]'") val chunksJson: String = "[]",
    /** 这个词最容易拼错的那一段，必须是 term 的子串；挖空题优先挖它。 */
    @ColumnInfo(defaultValue = "''") val trickyPart: String = "",
    /** 真人常写错的形式，JSON 字符串数组，用作四选一的干扰项。 */
    @ColumnInfo(defaultValue = "'[]'") val misspellingsJson: String = "[]",
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

/** 单词的多维拼写掌握状态；与通用复习调度分开，避免“认得”冒充“写得出”。 */
@Entity(
    tableName = "spelling_progress",
    foreignKeys = [
        ForeignKey(
            entity = KnowledgeItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["itemId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class SpellingProgressEntity(
    @PrimaryKey val itemId: Long,
    val stage: String,
    val recognitionScore: Double,
    val partialRecallScore: Double,
    val chunkRecallScore: Double,
    val phonemeGraphemeScore: Double,
    val freeRecallScore: Double,
    val retentionScore: Double,
    val successStreak: Int,
    val failureStreak: Int,
    val stageSuccessCount: Int,
    val freeRecallSuccessCount: Int,
    val successfulRecallDatesJson: String,
    val longestSuccessfulIntervalDays: Int,
    val currentIntervalDays: Int,
    val weakSegmentsJson: String,
    val lastAttemptAt: Long?,
)

/** 每次拼写提交都追加保存，包括同一张卡逐级要提示时的错误尝试。 */
@Entity(
    tableName = "spelling_attempts",
    foreignKeys = [
        ForeignKey(
            entity = KnowledgeItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["itemId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["itemId"]), Index(value = ["occurredAt"]), Index(value = ["questionType"])],
)
data class SpellingAttemptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val itemId: Long,
    val questionType: String,
    val expected: String,
    val answer: String,
    val correct: Boolean,
    val hintLevel: Int,
    val responseTimeMillis: Long,
    val errorTypesJson: String,
    val weakSegment: String,
    val weakStart: Int?,
    val weakEndExclusive: Int?,
    val masteryCredit: Double,
    val occurredAt: Long,
)

/**
 * 做错的练习题。独立成表而不是塞进 learning_events：
 * 这里要按"错误类型"聚合，用来决定接下来讲什么语法点，
 * 而且知识项被删掉之后这份错误画像仍然有意义，所以不设外键、冗余存一份语法结构。
 */
@Entity(
    tableName = "drill_mistakes",
    indices = [Index(value = ["occurredAt"]), Index(value = ["errorTag"])],
)
data class DrillMistakeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 对应的知识项；出题时那条语法还没入库或已被删除时为 null。 */
    val itemId: Long?,
    val patternEn: String,
    /** 见 GrammarErrorTag，AI 给的标签经本地校验，不认识的一律归到 other。 */
    val errorTag: String,
    val sentenceEn: String,
    val chosen: String,
    val answer: String,
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

/**
 * 一个词的记忆提示（词汇记忆提示DESIGN.md §6）。
 *
 * 一词一条，整取整存成 JSON：这些字段只随提示整条生成、整条替换、整条重来，
 * 拆成十几列除了让"再来一条"变成一次大更新之外没有别的好处（和 reading_materials 同一个理由）。
 *
 * 独立成表而不是往 vocabulary_details 加列，是因为它和释义的生命周期不一样：
 * 释义是词固有的事实，一次写好就不再动；记忆提示是可以推翻重来的——这一条没帮上忙，
 * 换个策略再要一条，原来那份词义、例句、拼写事实一个字都不该跟着重新生成。
 *
 * 生成参数一并保存（AGENTS.md §6）：[model] / [promptVersion] / [createdAt] 让"当时是怎么生成的"
 * 可复现，[droppedNotes] 记下校验时删掉了哪几项，排查"为什么没有构词提示"时不用猜。
 */
@Entity(
    tableName = "vocabulary_memory_hints",
    foreignKeys = [
        ForeignKey(
            entity = KnowledgeItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["itemId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class VocabularyMemoryHintEntity(
    @PrimaryKey val itemId: Long,
    val term: String,
    /** 整条 MemoryAssistance 的 JSON。解不出来时按"还没有提示"处理，不炸页面。 */
    val payloadJson: String,
    val model: String,
    val promptVersion: Int,
    val schemaVersion: Int,
    /** 校验时删掉了哪几项，分号分隔。 */
    val droppedNotes: String,
    val createdAt: Long,
)
