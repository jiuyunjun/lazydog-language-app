package com.lazydog.english.core.backup

import com.lazydog.english.core.database.GrammarDetailEntity
import com.lazydog.english.core.database.KnowledgeItemEntity
import com.lazydog.english.core.database.LearningEventEntity
import com.lazydog.english.core.database.ReadingMaterialEntity
import com.lazydog.english.core.database.ScenarioSessionEntity
import com.lazydog.english.core.database.VocabularyDetailEntity
import kotlinx.serialization.Serializable

/**
 * 导出/导入的 JSON 结构（ARCHITECTURE.md §9：导出格式带 schema 版本，不含密钥）。
 * knowledgeItems.id 是导出时的旧 id，仅用于把 details/events 接回正确的知识项——
 * 导入时会重新分配 id（见 BackupRepository.restore），不能假设导入后 id 不变。
 */
@Serializable
data class BackupKnowledgeItem(
    val id: Long,
    val type: String,
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

@Serializable
data class BackupVocabularyDetail(
    val itemId: Long,
    val term: String,
    val ipa: String,
    val meaningZh: String,
    val exampleEn: String,
    val exampleZh: String,
    val pos: String = "",
    val collocationsJson: String = "[]",
    val memoryHintZh: String = "",
)

@Serializable
data class BackupGrammarDetail(
    val itemId: Long,
    val name: String,
    val patternEn: String = "",
    val labelZh: String = "",
    val summaryZh: String = "",
    val explanationZh: String,
    val exampleEn: String,
    val exampleZh: String = "",
    val badExampleEn: String = "",
    val badExampleNoteZh: String = "",
    val tipZh: String = "",
)

@Serializable
data class BackupLearningEvent(
    val itemId: Long,
    val source: String,
    val activity: String,
    val rating: String?,
    val responseMillis: Long?,
    val occurredAt: Long,
)

@Serializable
data class BackupReadingMaterial(
    val title: String,
    val body: String,
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

@Serializable
data class BackupScenarioSession(
    val scenarioId: String,
    val titleZh: String,
    val stage: String,
    val snapshotJson: String,
    val createdAt: Long,
    val updatedAt: Long,
)

/** 只备份学习偏好；AI/Speech 密钥和 Base URL 一律不导出（AGENTS.md §6）。 */
@Serializable
data class BackupPreferences(
    val learningGoal: String = "",
    val topics: Set<String> = emptySet(),
    val dailyMinutes: Int = 12,
    val maxNewWords: Int = 5,
    val learnerLevel: String = "",
    val learnerLevelConfidence: Int = 0,
    val reminderTime: String = "",
    val themeMode: String = "system",
    val ttsVoice: String = "",
    val autoReadWords: Boolean = true,
    val speechRateName: String = "",
)

@Serializable
data class BackupPayload(
    val schemaVersion: Int = 1,
    val exportedAt: Long,
    val knowledgeItems: List<BackupKnowledgeItem> = emptyList(),
    val vocabularyDetails: List<BackupVocabularyDetail> = emptyList(),
    val grammarDetails: List<BackupGrammarDetail> = emptyList(),
    val learningEvents: List<BackupLearningEvent> = emptyList(),
    val readingMaterials: List<BackupReadingMaterial> = emptyList(),
    val scenarioSessions: List<BackupScenarioSession> = emptyList(),
    val preferences: BackupPreferences = BackupPreferences(),
)

// ---- 实体 <-> 备份结构映射 ----

fun KnowledgeItemEntity.toBackup() = BackupKnowledgeItem(
    id = id,
    type = type,
    stage = stage,
    stability = stability,
    difficulty = difficulty,
    reviewCount = reviewCount,
    lapseCount = lapseCount,
    lastReviewedAt = lastReviewedAt,
    nextReviewAt = nextReviewAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

/** 恢复用：id 固定为 0 让 Room 重新分配（autoGenerate）。 */
fun BackupKnowledgeItem.toEntity() = KnowledgeItemEntity(
    id = 0,
    type = type,
    stage = stage,
    stability = stability,
    difficulty = difficulty,
    reviewCount = reviewCount,
    lapseCount = lapseCount,
    lastReviewedAt = lastReviewedAt,
    nextReviewAt = nextReviewAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun VocabularyDetailEntity.toBackup() = BackupVocabularyDetail(
    itemId, term, ipa, meaningZh, exampleEn, exampleZh, pos, collocationsJson, memoryHintZh,
)

fun BackupVocabularyDetail.toEntity(newItemId: Long) = VocabularyDetailEntity(
    newItemId, term, ipa, meaningZh, exampleEn, exampleZh, pos, collocationsJson, memoryHintZh,
)

fun GrammarDetailEntity.toBackup() = BackupGrammarDetail(
    itemId = itemId,
    name = name,
    patternEn = patternEn,
    labelZh = labelZh,
    summaryZh = summaryZh,
    explanationZh = explanationZh,
    exampleEn = exampleEn,
    exampleZh = exampleZh,
    badExampleEn = badExampleEn,
    badExampleNoteZh = badExampleNoteZh,
    tipZh = tipZh,
)

fun BackupGrammarDetail.toEntity(newItemId: Long) =
    GrammarDetailEntity(
        itemId = newItemId,
        name = name,
        patternEn = patternEn,
        labelZh = labelZh,
        summaryZh = summaryZh,
        explanationZh = explanationZh,
        exampleEn = exampleEn,
        exampleZh = exampleZh,
        badExampleEn = badExampleEn,
        badExampleNoteZh = badExampleNoteZh,
        tipZh = tipZh,
    )

fun LearningEventEntity.toBackup() = BackupLearningEvent(itemId, source, activity, rating, responseMillis, occurredAt)

fun BackupLearningEvent.toEntity(newItemId: Long) =
    LearningEventEntity(id = 0, itemId = newItemId, source = source, activity = activity, rating = rating, responseMillis = responseMillis, occurredAt = occurredAt)

fun ReadingMaterialEntity.toBackup() = BackupReadingMaterial(
    title = title,
    body = body,
    source = source,
    topic = topic,
    estimatedCefr = estimatedCefr,
    targetWordsJson = targetWordsJson,
    grammarJson = grammarJson,
    questionsJson = questionsJson,
    model = model,
    promptVersion = promptVersion,
    schemaVersion = schemaVersion,
    validationNotes = validationNotes,
    createdAt = createdAt,
)

fun BackupReadingMaterial.toEntity() = ReadingMaterialEntity(
    id = 0,
    title = title,
    body = body,
    source = source,
    topic = topic,
    estimatedCefr = estimatedCefr,
    targetWordsJson = targetWordsJson,
    grammarJson = grammarJson,
    questionsJson = questionsJson,
    model = model,
    promptVersion = promptVersion,
    schemaVersion = schemaVersion,
    validationNotes = validationNotes,
    createdAt = createdAt,
)

fun ScenarioSessionEntity.toBackup() = BackupScenarioSession(
    scenarioId = scenarioId,
    titleZh = titleZh,
    stage = stage,
    snapshotJson = snapshotJson,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun BackupScenarioSession.toEntity() = ScenarioSessionEntity(
    id = 0,
    scenarioId = scenarioId,
    titleZh = titleZh,
    stage = stage,
    snapshotJson = snapshotJson,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
