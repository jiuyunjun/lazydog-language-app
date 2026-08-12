package com.lazydog.english.core.data

import com.lazydog.english.core.database.AppDatabase
import com.lazydog.english.core.database.DrillMistakeEntity
import com.lazydog.english.domain.generation.GrammarDrillItem
import com.lazydog.english.domain.practice.DrillMistake
import com.lazydog.english.domain.practice.GrammarErrorTag
import com.lazydog.english.domain.practice.MistakeProfile
import com.lazydog.english.domain.practice.MistakeSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 错题记录：练习题做错时写一条，之后按错误类型聚合，决定接下来讲什么语法点。
 * 聚合逻辑在 [MistakeProfile]，这里只负责存取和保留期。
 */
class MistakeRepository(private val database: AppDatabase) {

    private val dao = database.drillMistakeDao()

    /** 记一次做错。[itemId] 是对应的知识项，出题的语法点还没入库时可以为 null。 */
    suspend fun recordGrammarMistake(
        itemId: Long?,
        patternEn: String,
        item: GrammarDrillItem,
        chosenIndex: Int,
        nowMillis: Long = System.currentTimeMillis(),
    ) = recordMistake(
        itemId = itemId,
        patternEn = patternEn,
        errorTag = item.errorTag,
        sentenceEn = item.sentenceEn,
        chosen = item.options.getOrElse(chosenIndex) { "" },
        answer = item.answer,
        nowMillis = nowMillis,
    )

    /**
     * 通用入口：选择题、中译英产出等任何"错在某类形式"的场合都记到同一张表，
     * 这样错题画像不分来源，讲什么由累计的错误决定。
     */
    suspend fun recordMistake(
        itemId: Long?,
        patternEn: String,
        errorTag: String,
        sentenceEn: String,
        chosen: String,
        answer: String,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        dao.insert(
            DrillMistakeEntity(
                itemId = itemId,
                patternEn = patternEn.trim(),
                errorTag = GrammarErrorTag.normalize(errorTag),
                sentenceEn = sentenceEn.trim(),
                chosen = chosen.trim(),
                answer = answer.trim(),
                occurredAt = nowMillis,
            ),
        )
        // 顺手清掉过保留期的老错题，避免这张表无限增长。
        dao.deleteBefore(MistakeProfile.windowStart(nowMillis, MistakeProfile.KEEP_DAYS))
    }

    /** 最近窗口期内错得最多的几类形式，多的在前。 */
    suspend fun weakSpots(
        nowMillis: Long = System.currentTimeMillis(),
        limit: Int = 3,
    ): List<MistakeSummary> = MistakeProfile.summarize(
        mistakes = dao.getSince(MistakeProfile.windowStart(nowMillis)).map { it.toDomain() },
        nowMillis = nowMillis,
        limit = limit,
    )

    /** 给界面用的实时版本。 */
    fun observeWeakSpots(
        nowMillis: Long = System.currentTimeMillis(),
        limit: Int = 3,
    ): Flow<List<MistakeSummary>> =
        dao.observeSince(MistakeProfile.windowStart(nowMillis)).map { rows ->
            MistakeProfile.summarize(rows.map { it.toDomain() }, nowMillis = nowMillis, limit = limit)
        }
}

private fun DrillMistakeEntity.toDomain() = DrillMistake(patternEn, errorTag, occurredAt)
