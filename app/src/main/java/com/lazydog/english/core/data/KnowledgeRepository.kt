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
import com.lazydog.english.domain.generation.Collocation
import com.lazydog.english.domain.scheduling.MemoryState
import com.lazydog.english.domain.scheduling.ReviewScheduler
import com.lazydog.english.domain.scheduling.deriveStage
import com.lazydog.english.domain.spelling.SpellingEngine
import com.lazydog.english.domain.spelling.SpellingErrorType
import com.lazydog.english.domain.spelling.SpellingEvaluation
import com.lazydog.english.domain.spelling.SpellingFacts
import com.lazydog.english.domain.spelling.SpellingAttemptSummary
import com.lazydog.english.domain.spelling.SpellingProfile
import com.lazydog.english.domain.spelling.SpellingProfiles
import com.lazydog.english.domain.spelling.SpellingProgress
import com.lazydog.english.domain.spelling.SpellingQuestionType
import com.lazydog.english.domain.spelling.SpellingStage
import com.lazydog.english.domain.spelling.WeakSegment
import com.lazydog.english.domain.grammar.GrammarCategory
import com.lazydog.english.domain.grammar.grammarPointKey
import com.lazydog.english.domain.vocabulary.PartOfSpeech
import com.lazydog.english.domain.vocabulary.normalizePos
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
        collocations: List<Collocation> = emptyList(),
        memoryHintZh: String = "",
        facts: SpellingFacts = SpellingFacts.None,
        /** 用户当初遇到它时的形态（双击查词点的是 `went`，存的是 `go`）。同形时留空。 */
        seenAs: String = "",
        /** 不规则变形（go → went/gone）。规则变形不存，见 `VocabularyDetailEntity.formsJson`。 */
        forms: List<String> = emptyList(),
        /**
         * 这是这个词条的**另一个词义**，不是重复。
         *
         * 默认 false：同一个 (lemma, 词性) 再存一条会被挡掉，挡的是重复入库。
         * 但"run = 跑"和"run = 经营"是两个词义，该各自复习（单词记忆DESIGN.md §5），
         * 那种情况由用户在查词面板上明说，本地不拿中文释义做模糊比对——
         * 释义像不像和是不是同一个词义，是两回事。
         */
        asNewSense: Boolean = false,
    ): Long? {
        // 存进来的必须已经是原型：双击查词那条路由 AI 结合句子还原（`WordExplanation.headword`），
        // 生成新词那条由提示词要求词典形式。本地不做词形还原——saw / left / found 这类
        // 离开句子根本判不了，猜出来的词条会污染整个复习队列。
        val cleanTerm = term.trim()
        val cleanPos = normalizePos(pos)
        val siblings = dao.getSensesOf(cleanTerm, cleanPos)
        // 词性认不出来时退回老口径（整个词只能有一条），否则一个没标注词性的词
        // 会和任何一个同名词条撞在一起，或者反过来无限重复入库。
        val known = PartOfSpeech.parse(cleanPos) != null
        val duplicate = if (known) siblings.isNotEmpty() else dao.vocabularyTermExists(cleanTerm)
        if (duplicate && !(asNewSense && known)) return null
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
                    pos = cleanPos,
                    collocationsJson = VocabularyJson.encodeCollocations(collocations),
                    memoryHintZh = memoryHintZh.trim(),
                    chunksJson = VocabularyJson.encodeList(facts.chunks),
                    trickyPart = facts.trickyPart.trim(),
                    misspellingsJson = VocabularyJson.encodeList(facts.misspellings),
                    seenAs = seenAs.trim().takeIf { !it.equals(cleanTerm, ignoreCase = true) }.orEmpty(),
                    formsJson = VocabularyJson.encodeList(
                        forms.map { it.trim() }
                            .filter { it.isNotBlank() && !it.equals(cleanTerm, ignoreCase = true) }
                            .distinctBy { it.lowercase() },
                    ),
                    senseOrder = (siblings.maxOfOrNull { it.senseOrder } ?: -1) + 1,
                ),
            )
            id
        }
    }

    /**
     * 库里已经存着的、和这个词形对得上的词条。
     *
     * 先按词形本身找，再按不规则变形找：点 `went` 要能命中 `go`（§4.1
     * 「Word Form != 独立生词」）。命中就不用再花一次讲解，也不会又存一条。
     */
    suspend fun findByWordForm(form: String): List<VocabularyRecord> {
        val clean = form.trim()
        if (clean.isBlank()) return emptyList()
        val all = vocabulary.first()
        val direct = all.filter { it.detail.term.equals(clean, ignoreCase = true) }
        if (direct.isNotEmpty()) return direct
        return all.filter { record ->
            VocabularyJson.decodeList(record.detail.formsJson).any { it.equals(clean, ignoreCase = true) }
        }
    }

    /** 同一个词条（lemma + 词性）下已经存下的词义，按顺序。 */
    suspend fun sensesOf(lemma: String, pos: String): List<VocabularyDetailEntity> =
        dao.getSensesOf(lemma.trim(), normalizePos(pos))

    /** @return 新知识项 id；同名语法点已存在时返回 null。 */
    suspend fun addGrammar(
        patternEn: String,
        /** 语法大类（`GrammarCategory.wire`）。给不出来时判重退回老口径。 */
        category: String = "",
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
        // 老口径先挡一道：字面完全一样的肯定是同一条。
        if (dao.grammarNameExists(cleanPattern)) return null
        val parsedCategory = GrammarCategory.parse(category)
        val key = grammarPointKey(parsedCategory, cleanPattern)
        if (key.isNotEmpty()) {
            backfillGrammarKeys()
            if (dao.grammarKeyExists(key)) return null
        }
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
                    category = parsedCategory?.wire.orEmpty(),
                    canonicalKey = key,
                ),
            )
            id
        }
    }

    /**
     * 给还没有身份键的老语法点补算一次。
     *
     * 归一化规则写在 Kotlin 里（`grammarPointKey`），SQL 迁移里没法调用，
     * 在迁移脚本里照抄一份的话两处会慢慢跑偏。语法表只有几十行，
     * 第一次判重时顺手补完，之后每次都是空转。
     */
    private suspend fun backfillGrammarKeys() {
        for (detail in dao.getAllGrammarDetails()) {
            if (detail.canonicalKey.isNotEmpty()) continue
            val pattern = detail.patternEn.ifBlank { detail.name }
            // 老数据没存大类，只能按公式本身归一化：同类里的写法差异照样能挡住，
            // 跨类的重名（很少见）留给字面判重。
            val category = GrammarCategory.parse(detail.category)
            val key = grammarPointKey(category, pattern)
            if (key.isNotEmpty()) {
                dao.updateGrammarKey(detail.itemId, category?.wire.orEmpty(), key)
            }
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

    /** 读取拼写进度；还没真正练过的按通用掌握阶段给一个起点，首次提交后才认存下来的那一行。 */
    suspend fun spellingProgress(itemId: Long): SpellingProgress {
        val item = dao.getItem(itemId)
        val stored = spellingDao.getProgress(itemId)?.toDomain()
        if (stored != null && stored.lastAttemptAt != null) return stored
        return item?.let(::defaultSpellingProgress) ?: stored ?: SpellingProgress()
    }

    /**
     * 还没练过拼写的老词的起点。按通用掌握阶段猜一档，但只猜到"认得"这一侧：
     * 通用阶段说明的是认不认得，不是写不写得出，所以宁可从低一点的阶段起考。
     */
    private fun defaultSpellingProgress(item: KnowledgeItemEntity) =
        SpellingProgress(stage = SpellingEngine.initialStageFor(item.stageOrDefault()))

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
        // 拼写自己的到期时间优先：一个词的拼写阶梯走到哪儿，和它作为词条什么时候复习，
        // 本来就是两回事（拼写训练DESIGN.md §13）。没练过拼写的才回退到通用复习时间。
        val dueAt = progress.nextSpellingAt?.toEpochMilli() ?: nextReviewAt
        val overdueDays = dueAt
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
        /**
         * 这次作答算不算一次到期复习。加练没到期的词时传 false：
         * 拼写画像照记，但不推动通用复习时间——两个入口共用一套 nextReviewAt，
         * 加练把没到期的词往后推，等于让拼写练习悄悄改写单词复习的队列。
         */
        advanceReviewSchedule: Boolean = true,
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
        if (resolvesCard && advanceReviewSchedule) {
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
     * 组一轮拼写练习的队列（拼写训练DESIGN.md §14 的复习优先级）。
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
                val stored = progressById[record.item.id]?.toDomain()
                // 存下来但一次没练过的行不算数：一个已经复习过几轮的词，
                // 不该因为建表时留了一行 Seen 就永远停在四选一。
                val progress = stored?.takeIf { it.lastAttemptAt != null }
                    ?: defaultSpellingProgress(record.item)
                SpellingQueueEntry(
                    itemId = record.item.id,
                    term = term,
                    ipa = record.detail.ipa,
                    meaningZh = record.detail.meaningZh,
                    pos = record.detail.pos,
                    exampleEn = record.detail.exampleEn,
                    exampleZh = record.detail.exampleZh,
                    seenAs = record.detail.seenAs,
                    facts = record.detail.spellingFacts(),
                    progress = progress,
                    dueNow = spellingDueAt(progress, record.item.nextReviewAt) <= at.toEpochMilli(),
                    dueForReview = (record.item.nextReviewAt ?: Long.MAX_VALUE) <= at.toEpochMilli(),
                    priority = spellingPriority(record.item.nextReviewAt, progress, progress.lastAttemptAt == null, at),
                )
            }
            // 到期的先排完，不够一轮再拿没到期的补位——补位的那些算加练，不动复习时间。
            .sortedWith(compareByDescending<SpellingQueueEntry> { it.dueNow }.thenByDescending { it.priority })
            .take(limit)
    }

    /** 这个词下一次该考拼写的时刻：先看拼写阶梯，没练过拼写就跟通用复习时间走。 */
    private fun spellingDueAt(progress: SpellingProgress, nextReviewAt: Long?): Long =
        progress.nextSpellingAt?.toEpochMilli() ?: nextReviewAt ?: Long.MAX_VALUE

    /**
     * 记一次 S0 接触：用户把这张接触卡看完了。
     *
     * 不是一次作答，所以不写 spelling_attempts、不动通用复习时间、不进拼写画像——
     * 看过一个词的词形和读音不构成任何"写得出"的证据，只是让它有资格进入 S1。
     */
    suspend fun recordSpellingExposure(itemId: Long): SpellingProgress {
        val at = now()
        val previous = spellingDao.getProgress(itemId)?.toDomain() ?: spellingProgress(itemId)
        val next = SpellingEngine.afterExposure(previous, at)
        spellingDao.saveProgress(next.toEntity(itemId))
        return next
    }

    /** 复习卡要按各自的拼写阶段出题，进页面时一次取齐，别一张卡查一次库。 */
    suspend fun spellingProgressByItem(): Map<Long, SpellingProgress> =
        spellingDao.getAllProgress()
            // 没练过的那些行不作数，交给 defaultSpellingProgress 按通用阶段推。
            .filter { it.lastAttemptAt != null }
            .associate { it.itemId to it.toDomain() }

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
        /** 表达和固定短语在词性这一列上的取值。 */
        private val EXPRESSION_POS = PartOfSpeech.Phrase.wire
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
    /** 例句里这个词实际出现的形态；空表示就是 term 本身。语境默写按它挖空。 */
    val seenAs: String,
    val facts: SpellingFacts,
    val progress: SpellingProgress,
    /** 这个词此刻到期了没有。没到期的是加练，判分照记但不推动复习时间。 */
    /** 拼写阶梯说这个词该考了。决定它排在这一轮的哪个位置。 */
    val dueNow: Boolean,
    /**
     * 这个**词条**本身也到期了。只有它才决定要不要推动通用 `nextReviewAt`。
     *
     * 两个到期不是一回事：拼写阶梯管"下次以多强的提示考写"，通用复习时间管
     * "这个词条什么时候再出现"。拼写到期而词条没到期的词照样进队列（加练），
     * 但答完不能去改单词复习的排期——那正是 D-029 那道闸门要挡的事。
     */
    val dueForReview: Boolean,
    val priority: Double,
)

/** 落库时写好的拼写事实。都为空说明这条是老数据，引擎会退回本地启发式。 */
fun VocabularyDetailEntity.spellingFacts() = SpellingFacts(
    chunks = VocabularyJson.decodeList(chunksJson),
    trickyPart = trickyPart,
    misspellings = VocabularyJson.decodeList(misspellingsJson),
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

    @Serializable
    private data class CollocationRow(val en: String, val zh: String = "")

    private val collocationSerializer = ListSerializer(CollocationRow.serializer())

    fun encodeCollocations(collocations: List<Collocation>): String =
        json.encodeToString(collocationSerializer, collocations.map { CollocationRow(it.en, it.zh) })

    /**
     * 老数据是一串裸字符串（`["curb traffic"]`），新数据是 `[{"en":..,"zh":..}]`。
     * 两种都认，字符串按"只有英文、还没翻译"处理——不为了加一个字段去写数据库迁移。
     */
    fun decodeCollocations(raw: String): List<Collocation> {
        val rows = runCatching { json.decodeFromString(collocationSerializer, raw) }.getOrNull()
        if (rows != null) return rows.map { Collocation(it.en, it.zh) }
        return decodeList(raw).map { Collocation(it) }
    }

    fun encodeList(values: List<String>): String = json.encodeToString(serializer, values)

    /** 坏数据返回空列表：拼写事实缺了退回启发式，不该让一条坏 JSON 炸掉整页。 */
    fun decodeList(raw: String): List<String> =
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
    currentIntervalMinutes = currentIntervalMinutes,
    nextSpellingAt = nextSpellingAt?.let(Instant::ofEpochMilli),
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
    currentIntervalMinutes = currentIntervalMinutes,
    nextSpellingAt = nextSpellingAt?.toEpochMilli(),
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
