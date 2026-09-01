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
import com.lazydog.english.domain.spelling.SpellingAttemptSummary
import com.lazydog.english.domain.spelling.SpellingProfile
import com.lazydog.english.domain.spelling.SpellingProfiles
import com.lazydog.english.domain.spelling.SpellingProgress
import com.lazydog.english.domain.spelling.SpellingQuestionType
import com.lazydog.english.domain.spelling.SpellingStage
import com.lazydog.english.domain.spelling.WeakSegment
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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
            spellingDao.saveProgress(SpellingProgress().toEntity(id))
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
        return defaultSpellingProgress(item)
    }

    /**
     * 还没练过拼写的老词的起点。按通用掌握阶段猜一档，但只猜到"认得"这一侧：
     * 通用阶段说明的是认不认得，不是写不写得出，所以宁可从低一点的阶段起考。
     */
    private fun defaultSpellingProgress(item: KnowledgeItemEntity) = SpellingProgress(
        stage = when (item.stageOrDefault()) {
            KnowledgeStage.Unseen, KnowledgeStage.Exposed -> SpellingStage.Seen
            KnowledgeStage.Learning -> SpellingStage.PartialRecall
            KnowledgeStage.Familiar -> SpellingStage.GuidedRecall
            KnowledgeStage.Mastered -> SpellingStage.FreeRecall
        },
    )

    /**
     * 复习优先级 = 遗忘风险 + 薄弱片段分 + 错误频次 + 没练过的补一次。
     * 分数只用于排序，绝对值没有意义，所以不做归一化。
     */
    private fun spellingPriority(
        nextReviewAt: Long?,
        progress: SpellingProgress,
        neverPracticed: Boolean,
        at: Instant,
    ): Double {
        val overdueDays = nextReviewAt
            ?.let { (at.toEpochMilli() - it).toDouble() / MILLIS_PER_DAY }
            ?.coerceAtLeast(0.0)
            ?: 0.0
        val weakScore = progress.weakSegments.sumOf { it.errorCount }.toDouble()
        return overdueDays.coerceAtMost(30.0) +
            weakScore * 1.5 +
            progress.failureStreak * 2.0 +
            if (neverPracticed) 3.0 else 0.0
    }

    /**
     * 记录一次真实的拼写提交。
     *
     * 每次提交都更新拼写画像和错误记录——同一张卡答错后还能重来，那几次也是真实数据。
     * 但通用复习时间只在这张卡真正翻篇时更新一次（写对了，或者提示已经拉到底、
     * 答案摆在脸上），否则一张卡来回试三次会被记成三轮复习，把 lapseCount 撑得虚高。
     */
    suspend fun recordSpellingAttempt(
        itemId: Long,
        expected: String,
        answer: String,
        questionType: SpellingQuestionType,
        hintLevel: Int,
        responseTimeMillis: Long,
        audioPrompted: Boolean = false,
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
            responseTimeMillis = responseTimeMillis,
            audioPrompted = audioPrompted,
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
        val resolvesCard = evaluation.correct || hintLevel >= SpellingEngine.MAX_HINT_LEVEL
        if (resolvesCard) {
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

    /**
     * 组一轮拼写练习的队列（单词记忆DESIGN.md §14 的复习优先级）。
     *
     * 排序参考四件事：到期多久（遗忘风险）、这个词有多少薄弱片段、错误次数、
     * 以及还没练过拼写的词优先来一次。表达（整句）不进拼写练习——
     * 让人默写一整句不是拼写训练。
     */
    suspend fun spellingQueue(limit: Int = DEFAULT_SPELLING_SESSION_SIZE): List<SpellingQueueEntry> {
        val at = now()
        val words = vocabulary.first()
        if (words.isEmpty()) return emptyList()
        val progressById = spellingDao.getAllProgress().associateBy { it.itemId }
        return words
            .mapNotNull { record ->
                val term = record.detail.term.trim()
                // 多词条目走不了字母级训练，跳过。
                if (term.isBlank() || term.any { it.isWhitespace() }) return@mapNotNull null
                val progress = progressById[record.item.id]?.toDomain() ?: defaultSpellingProgress(record.item)
                SpellingQueueEntry(
                    itemId = record.item.id,
                    term = term,
                    ipa = record.detail.ipa,
                    meaningZh = record.detail.meaningZh,
                    pos = record.detail.pos,
                    exampleEn = record.detail.exampleEn,
                    exampleZh = record.detail.exampleZh,
                    progress = progress,
                    priority = spellingPriority(record.item.nextReviewAt, progress, progress.lastAttemptAt == null, at),
                )
            }
            .sortedByDescending { it.priority }
            .take(limit)
    }

    /** 画像页要的全量聚合。数据量是"这个人练过的拼写次数"，直接全读没问题。 */
    suspend fun spellingProfile(): SpellingProfile = SpellingProfiles.build(
        progress = spellingDao.getAllProgress().map { it.toDomain() },
        attempts = spellingDao.getAllAttempts().map { attempt ->
            SpellingAttemptSummary(
                correct = attempt.correct,
                questionType = SpellingQuestionType.entries
                    .firstOrNull { it.name == attempt.questionType } ?: SpellingQuestionType.FreeRecall,
                errorTypes = SpellingJson.decodeErrorTypes(attempt.errorTypesJson),
                responseTimeMillis = attempt.responseTimeMillis,
                hintLevel = attempt.hintLevel,
            )
        },
    )

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

    companion object {
        private const val EXPRESSION_POS = "expression"
        private const val MILLIS_PER_DAY = 24.0 * 60 * 60 * 1000

        /** 一轮拼写练习的题量，对齐设计稿顶部的「拼写练习 · n / 12」。 */
        const val DEFAULT_SPELLING_SESSION_SIZE = 12
    }
}

/** 拼写练习队列里的一个词，带上出题需要的全部内容和当前阶段。 */
data class SpellingQueueEntry(
    val itemId: Long,
    val term: String,
    val ipa: String,
    val meaningZh: String,
    val pos: String,
    val exampleEn: String,
    val exampleZh: String,
    val progress: SpellingProgress,
    val priority: Double,
)

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

    /** 认不出来的名字直接丢掉：老备份里可能有已经改名的类型，不该让整条记录读不出来。 */
    fun decodeErrorTypes(raw: String): Set<SpellingErrorType> = runCatching {
        json.decodeFromString(strings, raw)
            .mapNotNull { name -> SpellingErrorType.entries.firstOrNull { it.name == name } }
            .toSet()
    }.getOrDefault(emptySet())
}
