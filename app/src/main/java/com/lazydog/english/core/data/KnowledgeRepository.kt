package com.lazydog.english.core.data

import androidx.room.withTransaction
import com.lazydog.english.core.database.AppDatabase
import com.lazydog.english.core.database.GrammarRecord
import com.lazydog.english.core.database.GrammarDetailEntity
import com.lazydog.english.core.database.KnowledgeItemEntity
import com.lazydog.english.core.database.LearningEventEntity
import com.lazydog.english.core.database.SpellingAttemptEntity
import com.lazydog.english.core.database.SpellingProgressEntity
import com.lazydog.english.core.database.VocabularyDetailEntity
import com.lazydog.english.core.database.VocabularyRecord
import com.lazydog.english.core.model.KnowledgeStage
import com.lazydog.english.core.model.KnowledgeType
import com.lazydog.english.core.model.ReviewGrade
import com.lazydog.english.domain.scheduling.MemoryState
import com.lazydog.english.domain.scheduling.ReviewScheduler
import com.lazydog.english.domain.scheduling.deriveStage
import com.lazydog.english.domain.spelling.SpellingEngine
import com.lazydog.english.domain.spelling.SpellingErrorType
import com.lazydog.english.domain.spelling.SpellingEvaluation
import com.lazydog.english.domain.spelling.SpellingProgress
import com.lazydog.english.domain.spelling.SpellingQuestionType
import com.lazydog.english.domain.spelling.SpellingStage
import com.lazydog.english.domain.spelling.WeakSegment
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 知识库读写入口。调度状态只在这里更新：
 * recordReview = 追加事件 + 用 [ReviewScheduler] 算新状态 + 更新知识项，单事务。
 */
class KnowledgeRepository(
    private val database: AppDatabase,
    private val scheduler: ReviewScheduler,
    private val now: () -> Instant = Instant::now,
) {
    private val dao = database.knowledgeDao()
    private val spellingDao = database.spellingDao()

    private val vocabularyRecords: Flow<List<VocabularyRecord>> = dao.observeVocabulary()
    /** 真正的单词；完整短语和句子单独显示在“表达”里。 */
    val vocabulary: Flow<List<VocabularyRecord>> = vocabularyRecords.map { records ->
        records.filterNot { it.detail.isExpression() }
    }
    val expressions: Flow<List<VocabularyRecord>> = vocabularyRecords.map { records ->
        records.filter { it.detail.isExpression() }
    }
    val grammar: Flow<List<GrammarRecord>> = dao.observeGrammar()

    fun observeDueCount(at: Instant = now()): Flow<Int> = dao.observeDueCount(at.toEpochMilli())

    /** @return 新知识项 id；同名词已存在时返回 null。 */
    suspend fun addVocabulary(
        term: String,
        meaningZh: String,
        ipa: String = "",
        exampleEn: String = "",
        exampleZh: String = "",
        pos: String = "",
        collocations: List<String> = emptyList(),
        memoryHintZh: String = "",
    ): Long? {
        val cleanTerm = term.trim()
        if (dao.vocabularyTermExists(cleanTerm)) return null
        return database.withTransaction {
            val id = insertNewItem(KnowledgeType.Vocabulary)
            dao.insertVocabularyDetail(
                VocabularyDetailEntity(
                    itemId = id,
                    term = cleanTerm,
                    ipa = ipa.trim(),
                    meaningZh = meaningZh.trim(),
                    exampleEn = exampleEn.trim(),
                    exampleZh = exampleZh.trim(),
                    pos = pos.trim(),
                    collocationsJson = VocabularyJson.encodeCollocations(collocations),
                    memoryHintZh = memoryHintZh.trim(),
                ),
            )
            id
        }
    }

    /** @return 新知识项 id；同名语法点已存在时返回 null。 */
    suspend fun addGrammar(
        patternEn: String,
        labelZh: String = "",
        summaryZh: String = "",
        explanationZh: String = "",
        exampleEn: String = "",
        exampleZh: String = "",
        badExampleEn: String = "",
        badExampleNoteZh: String = "",
        tipZh: String = "",
    ): Long? {
        val cleanPattern = patternEn.trim()
        if (cleanPattern.isBlank() || !cleanPattern.any { it in 'A'..'Z' || it in 'a'..'z' } ||
            cleanPattern.any { it.code in 0x4E00..0x9FFF }
        ) return null
        if (dao.grammarNameExists(cleanPattern)) return null
        return database.withTransaction {
            val id = insertNewItem(KnowledgeType.Grammar)
            dao.insertGrammarDetail(
                GrammarDetailEntity(
                    itemId = id,
                    name = cleanPattern,
                    patternEn = cleanPattern,
                    labelZh = labelZh.trim(),
                    summaryZh = summaryZh.trim(),
                    explanationZh = explanationZh.trim(),
                    exampleEn = exampleEn.trim(),
                    exampleZh = exampleZh.trim(),
                    badExampleEn = badExampleEn.trim(),
                    badExampleNoteZh = badExampleNoteZh.trim(),
                    tipZh = tipZh.trim(),
                ),
            )
            id
        }
    }

    /** 把可复用短语或完整句子存为“表达”，不混进单词列表。 */
    suspend fun addExpression(
        expressionEn: String,
        meaningZh: String,
    ): Long? {
        val clean = expressionEn.trim()
        if (clean.isBlank()) return null
        return addVocabulary(
            term = clean,
            meaningZh = meaningZh.trim(),
            exampleEn = "",
            exampleZh = meaningZh.trim(),
            pos = EXPRESSION_POS,
        )
    }

    /**
     * 把情景演练里留下的可复用表达放进统一复习计划。
     * 表达复用 vocabulary_details，pos 标为 expression；已存在时复用原知识项。
     */
    suspend fun saveScenarioExpression(
        expressionEn: String,
        meaningZh: String,
    ): Long? {
        val clean = expressionEn.trim()
        if (clean.isBlank()) return null
        val existing = dao.getVocabularyByTerm(clean)
        val id = existing?.item?.id ?: addExpression(clean, meaningZh)
            ?: dao.getVocabularyByTerm(clean)?.item?.id
        if (id != null) recordReview(id, ReviewGrade.Good, source = "scenario")
        return id
    }

    /** 记录一次四档自评复习，返回更新后的状态；知识项不存在返回 null。 */
    suspend fun recordReview(
        itemId: Long,
        grade: ReviewGrade,
        source: String = "card",
        responseMillis: Long? = null,
    ): MemoryState? = database.withTransaction {
        val item = dao.getItem(itemId) ?: return@withTransaction null
        val at = now()
        val next = scheduler.schedule(item.toMemoryState(), grade, at)

        dao.insertEvent(
            LearningEventEntity(
                itemId = itemId,
                source = source,
                activity = "review",
                rating = grade.name,
                responseMillis = responseMillis,
                occurredAt = at.toEpochMilli(),
            ),
        )
        dao.updateItem(next.applyTo(item, updatedAt = at))
        next
    }

    /** 读取拼写进度；旧数据按通用掌握阶段给一个保守起点，首次提交时才真正建行。 */
    suspend fun spellingProgress(itemId: Long): SpellingProgress {
        spellingDao.getProgress(itemId)?.let { return it.toDomain() }
        val item = dao.getItem(itemId) ?: return SpellingProgress()
        return SpellingProgress(
            stage = when (item.stageOrDefault()) {
                KnowledgeStage.Unseen, KnowledgeStage.Exposed -> SpellingStage.Seen
                KnowledgeStage.Learning -> SpellingStage.PartialRecall
                KnowledgeStage.Familiar -> SpellingStage.GuidedRecall
                KnowledgeStage.Mastered -> SpellingStage.FreeRecall
            },
        )
    }

    /**
     * 记录一次真实的拼写提交。同一张卡答错后可以继续要提示，所以每次提交都更新拼写画像；
     * 只有 [finishReview] 为 true 时才更新通用复习时间，避免一次卡片被算成多轮复习。
     */
    suspend fun recordSpellingAttempt(
        itemId: Long,
        expected: String,
        answer: String,
        questionType: SpellingQuestionType,
        hintLevel: Int,
        responseTimeMillis: Long,
        finishReview: Boolean,
    ): SpellingEvaluation? = database.withTransaction {
        val item = dao.getItem(itemId) ?: return@withTransaction null
        val at = now()
        val previous = spellingDao.getProgress(itemId)?.toDomain() ?: spellingProgress(itemId)
        val evaluation = SpellingEngine.evaluate(
            progress = previous,
            expected = expected,
            answer = answer,
            questionType = questionType,
            hintLevel = hintLevel,
            attemptedAt = at,
        )
        spellingDao.saveProgress(evaluation.nextProgress.toEntity(itemId))
        spellingDao.insertAttempt(
            SpellingAttemptEntity(
                itemId = itemId,
                questionType = questionType.name,
                expected = expected,
                answer = answer,
                correct = evaluation.correct,
                hintLevel = hintLevel.coerceIn(0, 5),
                responseTimeMillis = responseTimeMillis.coerceAtLeast(0),
                errorTypesJson = SpellingJson.encodeErrorTypes(evaluation.errorTypes),
                weakSegment = evaluation.weakSegment?.segment.orEmpty(),
                weakStart = evaluation.weakSegment?.start,
                weakEndExclusive = evaluation.weakSegment?.endExclusive,
                masteryCredit = evaluation.masteryCredit,
                occurredAt = at.toEpochMilli(),
            ),
        )
        if (finishReview) {
            val memory = scheduler.schedule(item.toMemoryState(), evaluation.reviewGrade, at)
            dao.insertEvent(
                LearningEventEntity(
                    itemId = itemId,
                    source = "spelling",
                    activity = "review",
                    rating = evaluation.reviewGrade.name,
                    responseMillis = responseTimeMillis,
                    occurredAt = at.toEpochMilli(),
                ),
            )
            dao.updateItem(memory.applyTo(item, updatedAt = at))
        }
        evaluation
    }

    suspend fun deleteItem(itemId: Long) = dao.deleteItem(itemId)

    /**
     * 记录一次“在语境里遇见”事件（如阅读中出现了到期复习词）。
     * 只追加事件、不改复习计划——遇见不等于想起来（AGENTS.md §6）。
     */
    suspend fun recordExposure(itemId: Long, source: String) {
        dao.insertEvent(
            LearningEventEntity(
                itemId = itemId,
                source = source,
                activity = "exposure",
                rating = null,
                responseMillis = null,
                occurredAt = now().toEpochMilli(),
            ),
        )
    }

    private suspend fun insertNewItem(type: KnowledgeType): Long {
        val at = now()
        val state = MemoryState.initial(at)
        val id = dao.insertItem(
            KnowledgeItemEntity(
                type = type.name,
                stage = deriveStage(state).name,
                stability = state.stability,
                difficulty = state.difficulty,
                reviewCount = state.reviewCount,
                lapseCount = state.lapseCount,
                lastReviewedAt = null,
                nextReviewAt = state.nextReviewAt?.toEpochMilli(),
                createdAt = at.toEpochMilli(),
                updatedAt = at.toEpochMilli(),
            ),
        )
        dao.insertEvent(
            LearningEventEntity(
                itemId = id,
                source = "card",
                activity = "create",
                rating = null,
                responseMillis = null,
                occurredAt = at.toEpochMilli(),
            ),
        )
        return id
    }

    private companion object {
        const val EXPRESSION_POS = "expression"
    }
}

private fun VocabularyDetailEntity.isExpression(): Boolean =
    pos.equals("expression", ignoreCase = true) || pos.equals("phrase", ignoreCase = true)

/** 新数据直接读分层字段；旧数据尽量从混合标题和说明中提取一个可读回退。 */
fun GrammarDetailEntity.displayPattern(): String {
    if (patternEn.isNotBlank()) return patternEn
    val englishPrefix = name.substringBeforeFirstHan().trim().trimEnd('-', '—', ':', '：')
    return englishPrefix.ifBlank { name }
}

fun GrammarDetailEntity.displaySummary(): String {
    if (summaryZh.isNotBlank()) return summaryZh
    val titleChinese = name.dropWhile { it.code !in 0x4E00..0x9FFF }.trim()
    if (titleChinese.isNotBlank()) return titleChinese.take(36)
    return explanationZh.substringBefore('。').substringBefore('\n').trim().take(36)
}

private fun String.substringBeforeFirstHan(): String {
    val index = indexOfFirst { it.code in 0x4E00..0x9FFF }
    return if (index < 0) this else substring(0, index)
}

fun KnowledgeItemEntity.toMemoryState(): MemoryState = MemoryState(
    stability = stability,
    difficulty = difficulty,
    reviewCount = reviewCount,
    lapseCount = lapseCount,
    lastReviewedAt = lastReviewedAt?.let(Instant::ofEpochMilli),
    nextReviewAt = nextReviewAt?.let(Instant::ofEpochMilli),
)

fun MemoryState.applyTo(item: KnowledgeItemEntity, updatedAt: Instant): KnowledgeItemEntity =
    item.copy(
        stage = deriveStage(this).name,
        stability = stability,
        difficulty = difficulty,
        reviewCount = reviewCount,
        lapseCount = lapseCount,
        lastReviewedAt = lastReviewedAt?.toEpochMilli(),
        nextReviewAt = nextReviewAt?.toEpochMilli(),
        updatedAt = updatedAt.toEpochMilli(),
    )

fun KnowledgeItemEntity.stageOrDefault(): KnowledgeStage =
    KnowledgeStage.entries.firstOrNull { it.name == stage } ?: KnowledgeStage.Exposed

/** 单词详情里 collocationsJson 字段的编解码。解码失败返回空列表，坏数据不炸页面。 */
object VocabularyJson {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(String.serializer())

    fun encodeCollocations(collocations: List<String>): String = json.encodeToString(serializer, collocations)

    fun decodeCollocations(raw: String): List<String> =
        runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
}

private fun SpellingProgressEntity.toDomain() = SpellingProgress(
    stage = SpellingStage.entries.firstOrNull { it.name == stage } ?: SpellingStage.Seen,
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
    successfulRecallDates = SpellingJson.decodeDates(successfulRecallDatesJson),
    longestSuccessfulIntervalDays = longestSuccessfulIntervalDays,
    currentIntervalDays = currentIntervalDays,
    weakSegments = SpellingJson.decodeWeakSegments(weakSegmentsJson),
    lastAttemptAt = lastAttemptAt?.let(Instant::ofEpochMilli),
)

private fun SpellingProgress.toEntity(itemId: Long) = SpellingProgressEntity(
    itemId = itemId,
    stage = stage.name,
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
    successfulRecallDatesJson = SpellingJson.encodeDates(successfulRecallDates),
    longestSuccessfulIntervalDays = longestSuccessfulIntervalDays,
    currentIntervalDays = currentIntervalDays,
    weakSegmentsJson = SpellingJson.encodeWeakSegments(weakSegments),
    lastAttemptAt = lastAttemptAt?.toEpochMilli(),
)

@Serializable
private data class StoredWeakSegment(
    val segment: String,
    val start: Int,
    val endExclusive: Int,
    val errorCount: Int,
)

object SpellingJson {
    private val json = Json { ignoreUnknownKeys = true }
    private val strings = ListSerializer(String.serializer())
    private val weakSegments = ListSerializer(StoredWeakSegment.serializer())

    fun encodeDates(values: Set<String>): String = json.encodeToString(strings, values.sorted())
    fun decodeDates(raw: String): Set<String> = runCatching { json.decodeFromString(strings, raw).toSet() }.getOrDefault(emptySet())

    fun encodeWeakSegments(values: List<WeakSegment>): String = json.encodeToString(
        weakSegments,
        values.map { StoredWeakSegment(it.segment, it.start, it.endExclusive, it.errorCount) },
    )
    fun decodeWeakSegments(raw: String): List<WeakSegment> = runCatching {
        json.decodeFromString(weakSegments, raw).map { WeakSegment(it.segment, it.start, it.endExclusive, it.errorCount) }
    }.getOrDefault(emptyList())

    fun encodeErrorTypes(values: Set<SpellingErrorType>): String =
        json.encodeToString(strings, values.map { it.name })
}
