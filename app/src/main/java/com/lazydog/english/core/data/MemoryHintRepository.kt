package com.lazydog.english.core.data

import com.lazydog.english.core.database.AppDatabase
import com.lazydog.english.core.database.VocabularyMemoryHintEntity
import com.lazydog.english.domain.generation.GenerationResult
import com.lazydog.english.domain.generation.GenerationStage
import com.lazydog.english.domain.generation.LearningContentGenerator
import com.lazydog.english.domain.generation.MemoryAssistance
import com.lazydog.english.domain.generation.MemoryAssistanceRequest
import com.lazydog.english.domain.generation.MemoryWordLevel
import com.lazydog.english.domain.spelling.SpellingStage
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/**
 * 词汇记忆提示的读写和生成入口（词汇记忆提示DESIGN.md）。
 *
 * 生成放在这里而不是页面里，是因为「再来一条」要用到的东西全在数据层：
 * 上一条提示是什么（要避开）、这个词的薄弱片段、这个人真写错过的形式。
 * 页面只需要说"给这个词来一条"或者"这条不行，换一条"。
 */
class MemoryHintRepository(
    private val database: AppDatabase,
    private val generator: LearningContentGenerator,
    private val now: () -> Instant = Instant::now,
) {
    private val dao = database.memoryHintDao()
    private val spellingDao = database.spellingDao()
    private val knowledgeDao = database.knowledgeDao()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** 存着的提示；没有或解不出来时是 null，页面按"还没生成过"处理。 */
    fun observe(itemId: Long): Flow<MemoryAssistance?> =
        dao.observeHint(itemId).map { it?.decode() }

    suspend fun get(itemId: Long): MemoryAssistance? = dao.getHint(itemId)?.decode()

    suspend fun delete(itemId: Long) = dao.deleteHint(itemId)

    /**
     * 生成一条记忆提示并存下来。
     *
     * [regenerate] 为 true 时是「再来一条」：把现有那条的钩子和策略作为"要避开的"发出去，
     * 因为它已经摆在用户面前而对方没记住——同一个角度再写一遍没有意义。
     *
     * 失败不动数据库：原来那条提示还留着。生成失败就把已有的内容也清掉，
     * 等于因为一次网络抖动惩罚用户。
     */
    suspend fun generate(
        itemId: Long,
        learnerLevel: String,
        regenerate: Boolean = false,
        onStage: ((GenerationStage) -> Unit)? = null,
        onPartialHook: ((String) -> Unit)? = null,
    ): GenerationResult<MemoryAssistance> {
        val record = knowledgeDao.getVocabularyDetail(itemId)
            ?: return GenerationResult.Failure("这个词已经不在知识库里了")
        val previous = if (regenerate) get(itemId) else null
        // 同一个词条的别的词义已经写过构词/词形/发音的话，这次直接沿用（§16.2）。
        val shared = sharedWordLevel(record.term, record.pos, exceptItemId = itemId)

        val result = generator.generateMemoryAssistance(
            request = buildRequest(
                itemId, record.term, record.meaningZh, record.pos, learnerLevel, previous, shared,
            ),
            onStage = onStage,
            onPartialHook = onPartialHook,
        )
        if (result is GenerationResult.Success) {
            // 词条级那三样以已有的为准：模型即使不听话又写了一遍，也不让两个词义各说一套。
            val merged = if (shared != null) result.data.withWordLevel(shared) else result.data
            dao.saveHint(
                VocabularyMemoryHintEntity(
                    itemId = itemId,
                    term = record.term,
                    payloadJson = json.encodeToString(MemoryAssistance.serializer(), merged),
                    model = result.model,
                    promptVersion = result.promptVersion,
                    schemaVersion = SCHEMA_VERSION,
                    droppedNotes = result.droppedNotes.joinToString("；"),
                    createdAt = now().toEpochMilli(),
                ),
            )
            return GenerationResult.Success(
                data = merged,
                model = result.model,
                promptVersion = result.promptVersion,
                droppedNotes = result.droppedNotes,
            )
        }
        return result
    }

    /**
     * 同一个词条（lemma + 词性）下别的词义已经写过的词条级材料。
     *
     * 构词、易错段、发音属于词形，和是哪个意思无关，所以一个词条只该有一份
     * （`词汇记忆提示DESIGN.md` §16.2）。多义词各自成条之后，不这么做的话
     * `run` 的五个词义会各生成一遍，费 token 不说，五份之间还可能互相矛盾。
     */
    private suspend fun sharedWordLevel(
        term: String,
        pos: String,
        exceptItemId: Long,
    ): MemoryWordLevel? {
        if (term.isBlank()) return null
        for (sense in knowledgeDao.getSensesOf(term, pos)) {
            if (sense.itemId == exceptItemId) continue
            val wordLevel = dao.getHint(sense.itemId)?.decode()?.wordLevel() ?: continue
            if (!wordLevel.isEmpty) return wordLevel
        }
        return null
    }

    /**
     * 把这个人在这个词上的实际表现拼进请求（§11 与拼写系统联动、§12 按薄弱维度调整）。
     * 没练过的词就只带词义和水平，不硬凑上下文。
     */
    private suspend fun buildRequest(
        itemId: Long,
        term: String,
        meaningZh: String,
        pos: String,
        learnerLevel: String,
        previous: MemoryAssistance?,
        shared: MemoryWordLevel?,
    ): MemoryAssistanceRequest {
        val progress = spellingDao.getProgress(itemId)?.let { entity ->
            SpellingJson.decodeWeakSegments(entity.weakSegmentsJson) to entity.stage
        }
        val weakSegments = progress?.first.orEmpty()
            .sortedByDescending { it.errorCount }
            .map { it.segment }
            .filter { it.isNotBlank() }
            .take(3)
        val observedErrors = spellingDao.recentWrongAttempts(itemId, RECENT_ERROR_LIMIT)
            .map { it.answer.trim() }
            .filter { it.isNotEmpty() && !it.equals(term, ignoreCase = true) }
            .distinctBy { it.lowercase() }
            .take(3)

        return MemoryAssistanceRequest(
            term = term,
            meaningZh = meaningZh,
            pos = pos,
            learnerLevel = learnerLevel,
            weakSegments = weakSegments,
            observedErrors = observedErrors,
            focusZh = focusFor(progress?.second, weakSegments.isNotEmpty()),
            avoidHookZh = previous?.memoryHookZh.orEmpty(),
            avoidTypes = listOfNotNull(previous?.primaryType, previous?.secondaryType),
            sharedWordLevel = shared,
        )
    }

    /**
     * §12：认得出和写得出是两回事，提示该讲哪一头由拼写阶段决定。
     * 已经在默写阶段还在错的词，再讲一遍中文释义没有意义。
     */
    private fun focusFor(stage: String?, hasWeakSegments: Boolean): String {
        val spellingStage = SpellingStage.entries.firstOrNull { it.name == stage }
        return when {
            hasWeakSegments -> "认得出但写不对，卡在拼写上"
            spellingStage == null || spellingStage == SpellingStage.Seen -> ""
            spellingStage >= SpellingStage.GuidedRecall -> "已经能大致想起这个词，差在写得准不准"
            else -> ""
        }
    }

    /** 备份用：整表导出。 */
    suspend fun exportAll(): List<VocabularyMemoryHintEntity> = dao.getAllHints()

    private fun VocabularyMemoryHintEntity.decode(): MemoryAssistance? =
        runCatching { json.decodeFromString(MemoryAssistance.serializer(), payloadJson) }.getOrNull()

    companion object {
        /** 存进 payloadJson 的结构版本；将来改了字段靠它区分老数据。 */
        const val SCHEMA_VERSION = 1
        private const val RECENT_ERROR_LIMIT = 6
    }
}
