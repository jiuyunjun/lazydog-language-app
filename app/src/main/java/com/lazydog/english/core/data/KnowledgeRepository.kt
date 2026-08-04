package com.lazydog.english.core.data

import androidx.room.withTransaction
import com.lazydog.english.core.database.AppDatabase
import com.lazydog.english.core.database.GrammarRecord
import com.lazydog.english.core.database.GrammarDetailEntity
import com.lazydog.english.core.database.KnowledgeItemEntity
import com.lazydog.english.core.database.LearningEventEntity
import com.lazydog.english.core.database.VocabularyDetailEntity
import com.lazydog.english.core.database.VocabularyRecord
import com.lazydog.english.core.model.KnowledgeStage
import com.lazydog.english.core.model.KnowledgeType
import com.lazydog.english.core.model.ReviewGrade
import com.lazydog.english.domain.scheduling.MemoryState
import com.lazydog.english.domain.scheduling.ReviewScheduler
import com.lazydog.english.domain.scheduling.deriveStage
import java.time.Instant
import kotlinx.coroutines.flow.Flow

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

    val vocabulary: Flow<List<VocabularyRecord>> = dao.observeVocabulary()
    val grammar: Flow<List<GrammarRecord>> = dao.observeGrammar()

    fun observeDueCount(at: Instant = now()): Flow<Int> = dao.observeDueCount(at.toEpochMilli())

    /** @return 新知识项 id；同名词已存在时返回 null。 */
    suspend fun addVocabulary(
        term: String,
        meaningZh: String,
        ipa: String = "",
        exampleEn: String = "",
        exampleZh: String = "",
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
                ),
            )
            id
        }
    }

    /** @return 新知识项 id；同名语法点已存在时返回 null。 */
    suspend fun addGrammar(name: String, explanationZh: String = "", exampleEn: String = ""): Long? {
        val cleanName = name.trim()
        if (dao.grammarNameExists(cleanName)) return null
        return database.withTransaction {
            val id = insertNewItem(KnowledgeType.Grammar)
            dao.insertGrammarDetail(
                GrammarDetailEntity(
                    itemId = id,
                    name = cleanName,
                    explanationZh = explanationZh.trim(),
                    exampleEn = exampleEn.trim(),
                ),
            )
            id
        }
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
