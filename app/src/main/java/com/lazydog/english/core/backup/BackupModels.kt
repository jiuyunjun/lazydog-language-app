package com.lazydog.english.core.backup

import com.lazydog.english.core.database.DrillMistakeEntity
import com.lazydog.english.core.database.GrammarDetailEntity
import com.lazydog.english.core.database.KnowledgeItemEntity
import com.lazydog.english.core.database.LearningEventEntity
import com.lazydog.english.core.database.ReadingMaterialEntity
import com.lazydog.english.core.database.ScenarioSessionEntity
import com.lazydog.english.core.database.SpellingAttemptEntity
import com.lazydog.english.core.database.SpellingProgressEntity
import com.lazydog.english.core.database.VocabularyDetailEntity
import com.lazydog.english.core.database.VocabularyMemoryHintEntity
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
    // 拼写事实。旧备份没有这几项，解码成默认值后引擎退回本地启发式。
    val chunksJson: String = "[]",
    val trickyPart: String = "",
    val misspellingsJson: String = "[]",
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

/** 错题记录：错误画像决定之后讲什么，属于长期学习资产，要跟着备份走。 */
@Serializable
data class BackupDrillMistake(
    val itemId: Long? = null,
    val patternEn: String = "",
    val errorTag: String = "",
    val sentenceEn: String = "",
    val chosen: String = "",
    val answer: String = "",
    val occurredAt: Long = 0,
)

@Serializable
data class BackupSpellingProgress(
    val itemId: Long,
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

@Serializable
data class BackupSpellingAttempt(
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
 * 词汇记忆提示。可以重新生成，但每一条都花过一次调用，而且带着这个人当时的薄弱片段，
 * 不跟着备份走就等于换手机之后全部重来一遍（词汇记忆提示DESIGN.md §6）。
 */
@Serializable
data class BackupMemoryHint(
    val itemId: Long,
    val term: String = "",
    val payloadJson: String = "",
    val model: String = "",
    val promptVersion: Int = 0,
    val schemaVersion: Int = 0,
    val droppedNotes: String = "",
    val createdAt: Long = 0,
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
    /** 分技能能力值；旧备份没有这些字段，解码后为 null，恢复时回退到总等级。 */
    val skillVocab: Double? = null,
    val skillGrammar: Double? = null,
    val skillReading: Double? = null,
    val skillPragmatics: Double? = null,
    val skillExpression: Double? = null,
    val reminderTime: String = "",
    val themeMode: String = "system",
    val ttsVoice: String = "",
    val autoReadWords: Boolean = true,
    val speechRateName: String = "",
)

@Serializable
data class BackupPayload(
    val schemaVersion: Int = 2,
    val exportedAt: Long,
    val knowledgeItems: List<BackupKnowledgeItem> = emptyList(),
    val vocabularyDetails: List<BackupVocabularyDetail> = emptyList(),
    val grammarDetails: List<BackupGrammarDetail> = emptyList(),
    val learningEvents: List<BackupLearningEvent> = emptyList(),
    val readingMaterials: List<BackupReadingMaterial> = emptyList(),
    val scenarioSessions: List<BackupScenarioSession> = emptyList(),
    val drillMistakes: List<BackupDrillMistake> = emptyList(),
    val spellingProgress: List<BackupSpellingProgress> = emptyList(),
    val spellingAttempts: List<BackupSpellingAttempt> = emptyList(),
    /** 旧备份没有这一项，解码成空列表；恢复后页面按"还没生成过"处理，可以重新生成。 */
    val memoryHints: List<BackupMemoryHint> = emptyList(),
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
    chunksJson, trickyPart, misspellingsJson,
)

fun BackupVocabularyDetail.toEntity(newItemId: Long) = VocabularyDetailEntity(
    newItemId, term, ipa, meaningZh, exampleEn, exampleZh, pos, collocationsJson, memoryHintZh,
    chunksJson, trickyPart, misspellingsJson,
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

fun DrillMistakeEntity.toBackup() = BackupDrillMistake(
    itemId = itemId,
    patternEn = patternEn,
    errorTag = errorTag,
    sentenceEn = sentenceEn,
    chosen = chosen,
    answer = answer,
    occurredAt = occurredAt,
)

/** [newItemId] 是恢复后重新分配的知识项 id；对不上的记录仍然保留，只是不再关联知识项。 */
fun BackupDrillMistake.toEntity(newItemId: Long?) = DrillMistakeEntity(
    id = 0,
    itemId = newItemId,
    patternEn = patternEn,
    errorTag = errorTag,
    sentenceEn = sentenceEn,
    chosen = chosen,
    answer = answer,
    occurredAt = occurredAt,
)

fun SpellingProgressEntity.toBackup() = BackupSpellingProgress(
    itemId = itemId,
    stage = stage,
    recognitionScore = recognitionScore,
    partialRecallScore = partialRecallScore,
    chunkRecallScore = chunkRecallScore,
    phonemeGraphemeScore = phonemeGraphemeScore,
    freeRecallScore = freeRecallScore,
    retentionScore = retentionScore,
    successStreak = successStreak,
    failureStreak = failureStreak,
    stageSuccessCount = stageSuccessCount,
    freeRecallSuccessCount = freeRecallSuccessCount,
    successfulRecallDatesJson = successfulRecallDatesJson,
    longestSuccessfulIntervalDays = longestSuccessfulIntervalDays,
    currentIntervalDays = currentIntervalDays,
    weakSegmentsJson = weakSegmentsJson,
    lastAttemptAt = lastAttemptAt,
)

fun BackupSpellingProgress.toEntity(newItemId: Long) = SpellingProgressEntity(
    itemId = newItemId,
    stage = stage,
    recognitionScore = recognitionScore,
    partialRecallScore = partialRecallScore,
    chunkRecallScore = chunkRecallScore,
    phonemeGraphemeScore = phonemeGraphemeScore,
    freeRecallScore = freeRecallScore,
    retentionScore = retentionScore,
    successStreak = successStreak,
    failureStreak = failureStreak,
    stageSuccessCount = stageSuccessCount,
    freeRecallSuccessCount = freeRecallSuccessCount,
    successfulRecallDatesJson = successfulRecallDatesJson,
    longestSuccessfulIntervalDays = longestSuccessfulIntervalDays,
    currentIntervalDays = currentIntervalDays,
    weakSegmentsJson = weakSegmentsJson,
    lastAttemptAt = lastAttemptAt,
)

fun SpellingAttemptEntity.toBackup() = BackupSpellingAttempt(
    itemId = itemId,
    questionType = questionType,
    expected = expected,
    answer = answer,
    correct = correct,
    hintLevel = hintLevel,
    responseTimeMillis = responseTimeMillis,
    errorTypesJson = errorTypesJson,
    weakSegment = weakSegment,
    weakStart = weakStart,
    weakEndExclusive = weakEndExclusive,
    masteryCredit = masteryCredit,
    occurredAt = occurredAt,
)

fun BackupSpellingAttempt.toEntity(newItemId: Long) = SpellingAttemptEntity(
    id = 0,
    itemId = newItemId,
    questionType = questionType,
    expected = expected,
    answer = answer,
    correct = correct,
    hintLevel = hintLevel,
    responseTimeMillis = responseTimeMillis,
    errorTypesJson = errorTypesJson,
    weakSegment = weakSegment,
    weakStart = weakStart,
    weakEndExclusive = weakEndExclusive,
    masteryCredit = masteryCredit,
    occurredAt = occurredAt,
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

fun VocabularyMemoryHintEntity.toBackup() = BackupMemoryHint(
    itemId = itemId,
    term = term,
    payloadJson = payloadJson,
    model = model,
    promptVersion = promptVersion,
    schemaVersion = schemaVersion,
    droppedNotes = droppedNotes,
    createdAt = createdAt,
)

fun BackupMemoryHint.toEntity(newItemId: Long) = VocabularyMemoryHintEntity(
    itemId = newItemId,
    term = term,
    payloadJson = payloadJson,
    model = model,
    promptVersion = promptVersion,
    schemaVersion = schemaVersion,
    droppedNotes = droppedNotes,
    createdAt = createdAt,
)
