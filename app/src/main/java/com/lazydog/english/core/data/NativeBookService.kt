package com.lazydog.english.core.data

import android.content.Context
import android.util.Log
import com.lazydog.english.core.database.BookEntity
import com.lazydog.english.core.database.BookGlossaryEntity
import com.lazydog.english.core.database.ReadingExposureEntity
import com.lazydog.english.core.database.ReadingWordDao
import com.lazydog.english.core.database.ReadingWordEntity
import com.lazydog.english.core.model.KnowledgeStage
import com.lazydog.english.domain.books.BookLanguage
import com.lazydog.english.domain.books.ChineseSegmenter
import com.lazydog.english.domain.books.LearnerVocabulary
import com.lazydog.english.domain.books.NativeChapterRenderer
import com.lazydog.english.domain.books.NativeWordPicker
import com.lazydog.english.domain.books.OccurrenceFinder
import com.lazydog.english.domain.books.ProgressivePlanner
import com.lazydog.english.domain.books.ProgressiveRules
import com.lazydog.english.domain.books.ProgressiveSettings
import com.lazydog.english.domain.books.ReadingWordSnapshot
import com.lazydog.english.domain.books.ReadingWordState
import com.lazydog.english.domain.books.RenderedChapter
import com.lazydog.english.domain.books.RenderedReplacement
import com.lazydog.english.domain.books.WordOccurrence
import com.lazydog.english.domain.books.ZhEnIndex
import com.lazydog.english.domain.vocabulary.DictionaryLookup
import com.lazydog.english.domain.vocabulary.PartOfSpeech
import com.lazydog.english.domain.vocabulary.VocabularyCandidates
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * 中文书里的渐进学词（D-100，`读书与网络阅读DESIGN.md` §12；分词与反查见 §5）。
 *
 * 规则全在 `domain/books`（[OccurrenceFinder]、[ProgressivePlanner]），这里只管加载资源、读写进度、把规则串起来。
 * 分词词典和反查表只在第一次用时加载（二十来 MB），离开阅读器调 [release] 放掉。
 */
class NativeBookService(
    private val context: Context,
    private val books: BookRepository,
    private val knowledge: KnowledgeRepository,
    private val preferences: UserPreferences,
    private val dictionary: DictionaryLookup,
    private val words: ReadingWordDao,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val loadLock = Mutex()
    private var segmenter: ChineseSegmenter? = null
    private var index: ZhEnIndex? = null
    /** 书级词表（本书停用、这里不该换）的读写串行。 */
    private val glossaryLock = Mutex()
    private val prepareLock = Mutex()
    /** 每本书每章读到的最远原文位置：只有往前读的新字才攒机会，翻回、跳转不补发（§12.3.2 第 2 条）。 */
    private val furthestRead = ConcurrentHashMap<String, Int>()

    private suspend fun resources(): Pair<ChineseSegmenter, ZhEnIndex> = loadLock.withLock {
        withContext(Dispatchers.IO) {
            val seg = segmenter ?: context.assets.open(SEG_ASSET).bufferedReader(Charsets.UTF_8)
                .useLines { ChineseSegmenter.parse(it) }.also { segmenter = it }
            val idx = index ?: context.assets.open(INDEX_ASSET).bufferedReader(Charsets.UTF_8)
                .useLines { ZhEnIndex.parse(it) }.also { index = it }
            seg to idx
        }
    }

    /** 离开阅读器时放掉分词词典和反查表。 */
    fun release() {
        segmenter = null
        index = null
    }

    /** 这本书能不能做渐进学词：只有中文书。 */
    fun supports(book: BookEntity): Boolean = BookLanguage.fromWire(book.language) == BookLanguage.Chinese

    val settings: Flow<ProgressiveSettings> = preferences.progressiveSettings

    fun observeWords(): Flow<List<ReadingWordEntity>> = words.observeAll()

    /** 从关闭切到开启时先给一次新词机会：首段正常阅读就能见到第一个词（§12.3.2 第 2 条）。 */
    suspend fun saveSettings(settings: ProgressiveSettings) {
        val before = preferences.progressiveSettings.first()
        preferences.saveProgressiveSettings(settings)
        if (!before.enabled && settings.enabled) {
            preferences.updateProgressiveCredit(-ProgressiveRules.OPPORTUNITY_CHARS, ProgressiveRules.OPPORTUNITY_CHARS) {
                ProgressiveRules.OPPORTUNITY_CHARS
            }
        }
    }

    suspend fun setPaused(bookId: Long, paused: Boolean) = books.setProgressivePaused(bookId, paused)

    /** 今天引入了几个新义项、学习池占了几个。 */
    suspend fun usage(): Pair<Int, Int> = words.introducedOn(today()) to words.activeCount()

    /**
     * 渲染一章：找可替换的位置 → 规划放哪些 → 换成英文。资源读不出来时退回原文，书照样能读。
     * 只规划不记账：真正展示出来（[onPageDwelled]）才入池、扣额度。
     */
    suspend fun render(book: BookEntity, chapterIndex: Int, source: String, titleLength: Int): RenderedChapter {
        val settings = preferences.progressiveSettings.first()
        if (!settings.enabled || book.progressivePaused || !supports(book)) return RenderedChapter.plain(source)
        return try {
            prepare()
            val (seg, idx) = resources()
            val learner = learner()
            val occurrences = withContext(Dispatchers.Default) {
                // 标题不换：页眉、目录里显示的都是原标题，换了对不上。
                val tokens = seg.segment(source).filter { it.start >= titleLength }
                OccurrenceFinder.find(source, tokens) { zh, pos -> idx.resolve(zh, pos) }
            }
            val lookahead = lookahead(book.id, chapterIndex, source.length, seg, idx)
            val glossary = books.glossary(book.id)
            val now = now()
            val day = today()
            val snapshots = words.all().associate { it.senseId to it.toSnapshot() }
            val placements = ProgressivePlanner.plan(
                ProgressivePlanner.Input(
                    text = source,
                    occurrences = occurrences,
                    lookahead = lookahead,
                    words = snapshots,
                    shownBefore = words.exposures(contentKey(book.id, chapterIndex, source)).mapTo(HashSet()) { it.sourceStart to it.senseId },
                    disabledZh = glossary.filter { it.disabled }.mapTo(HashSet()) { it.zh },
                    excludedStarts = glossary.flatMap { excluded(it) }.filter { it.first == chapterIndex }.mapTo(HashSet()) { it.second },
                    eligibleNew = { NativeWordPicker.eligible(it, learner) },
                    readingStart = if (book.chapterIndex == chapterIndex) book.chapterOffset.coerceIn(0, source.length) else 0,
                    credit = preferences.progressiveCredit.first(),
                    dailyRemaining = settings.dailyNewCapped - words.introducedOn(day),
                    poolRemaining = settings.poolCapped - words.activeCount(),
                    density = settings.replacementDensity,
                    now = now,
                ),
            )
            withContext(Dispatchers.Default) { NativeChapterRenderer.render(source, placements) }
        } catch (e: Exception) {
            Log.w(TAG, "渐进学词渲染失败，退回原文", e)
            RenderedChapter.plain(source)
        }
    }

    /** 下一章开头一段：只用来估后文复现，不额外加载没取得的正文。 */
    private suspend fun lookahead(bookId: Long, chapterIndex: Int, offset: Int, seg: ChineseSegmenter, idx: ZhEnIndex): List<WordOccurrence> {
        val next = books.chapterParagraphs(bookId, chapterIndex + 1) ?: return emptyList()
        return withContext(Dispatchers.Default) {
            val text = next.joinToString("\n").take(ProgressiveRules.LOOKAHEAD_CHARS)
            OccurrenceFinder.find(text, seg.segment(text)) { zh, pos -> idx.resolve(zh, pos) }
                .map { it.copy(start = it.start + offset + 1, end = it.end + offset + 1, paragraph = it.paragraph + NEXT_CHAPTER_PARAGRAPH) }
        }
    }

    /**
     * 这一屏停够一秒（§12.3.2 末段的展示代理）：攒新词机会，记有效展示。
     *
     * @param from/to 这一屏的原文范围。
     */
    suspend fun onPageDwelled(
        bookId: Long,
        chapterIndex: Int,
        source: String,
        from: Int,
        to: Int,
        replacements: List<RenderedReplacement>,
    ) {
        val settings = preferences.progressiveSettings.first()
        if (!settings.enabled) return
        val key = "$bookId:$chapterIndex"
        val furthest = furthestRead[key] ?: from
        // 往前接着读（含跳过一屏以内）才算新字；跳出去很远的只记位置。
        val fresh = if (from <= furthest + (to - from).coerceAtLeast(1)) {
            ProgressiveRules.countTextChars(source, maxOf(from, furthest), to)
        } else {
            0
        }
        furthestRead[key] = maxOf(furthest, to)
        if (fresh > 0) addCredit(fresh)
        replacements.forEach { show(bookId, chapterIndex, source, it, settings) }
    }

    /** 记一次有效展示；让义项入了池就用掉一次机会，并在本书词表里留一行。 */
    private suspend fun show(bookId: Long, chapterIndex: Int, source: String, r: RenderedReplacement, settings: ProgressiveSettings) {
        val now = now()
        val entered = words.recordShown(
            exposure = ReadingExposureEntity(contentKey(bookId, chapterIndex, source), r.sourceStart, r.senseId, now, r.introduces, lookedUp = false),
            fresh = newWord(r, now, ReadingWordState.Active),
            day = today(),
            poolCap = settings.poolCapped,
            dailyCap = settings.dailyNewCapped,
            intervalMillis = ProgressiveRules::intervalMillis,
            reenterAfter = ProgressiveRules.REENTER_AFTER,
        )
        if (entered) {
            addCredit(-ProgressiveRules.OPPORTUNITY_CHARS)
            glossaryLock.withLock {
                if (books.glossary(bookId).none { it.zh == r.zh }) books.saveGlossary(listOf(glossaryRow(bookId, r, chapterIndex)))
            }
        }
    }

    /** 点开看了意思：确认这次展示，再记一次查义（同一实例只记一次，不降级）。 */
    suspend fun recordLookup(bookId: Long, chapterIndex: Int, source: String, r: RenderedReplacement) {
        val settings = preferences.progressiveSettings.first()
        if (settings.enabled) show(bookId, chapterIndex, source, r, settings)
        words.recordLookup(contentKey(bookId, chapterIndex, source), r.sourceStart, r.senseId, now())
    }

    /** 「本书不换这个词」，或在本书词表里恢复。 */
    suspend fun setIgnoredInBook(bookId: Long, zh: String, lemma: String, pos: PartOfSpeech, senseOrder: Int, chapterIndex: Int, ignored: Boolean) =
        glossaryLock.withLock {
            val row = books.glossary(bookId).firstOrNull { it.zh == zh }
                ?: BookGlossaryEntity(bookId, zh, lemma, pos.wire, senseOrder, SOURCE_LOCAL, chapterIndex, 0, 0, 0, 0, false)
            books.saveGlossary(listOf(row.copy(disabled = ignored)))
        }

    /** 「这里换得不对」：只恢复这一处，不影响学习评估（§12.2）。 */
    suspend fun excludeHere(bookId: Long, chapterIndex: Int, r: RenderedReplacement) = glossaryLock.withLock {
        val row = books.glossary(bookId).firstOrNull { it.zh == r.zh } ?: glossaryRow(bookId, r, chapterIndex)
        val next = excluded(row) + (chapterIndex to r.sourceStart)
        books.saveGlossary(listOf(row.copy(excludedJson = json.encodeToString(EXCLUDED, next.map { "${it.first}:${it.second}" }))))
    }

    /** 「我认识这个意思」：按义项记自评，退出学习池（§12.2）。加熟词本由用户另外选。 */
    suspend fun markKnownSense(r: RenderedReplacement) {
        val now = now()
        val row = words.get(r.senseId) ?: newWord(r, now, ReadingWordState.KnownSelf).copy(introducedDay = -1)
        words.upsert(row.copy(state = ReadingWordState.KnownSelf.ordinal, lastSignalAt = now))
    }

    /** 学习池里的义项重新开始学（本书词表里的「重新学」）。 */
    suspend fun resume(senseId: String) {
        val row = words.get(senseId) ?: return
        val now = now()
        words.upsert(row.copy(state = ReadingWordState.Active.ordinal, step = 0, nextDueAt = now, firstShownAt = now, lastSignalAt = now, pausedAt = 0))
    }

    /**
     * 加入生词本：身份取内置词典（D-095），去重复用现有生词本；之后这个义项离开学习池，复习归生词本。
     * @return 新卡 id；生词本里已经有了返回 null。
     */
    suspend fun addToVocabulary(lemma: String, pos: PartOfSpeech, senseOrder: Int, zh: String): Long? = withContext(Dispatchers.IO) {
        val entry = dictionary.entry(lemma) ?: return@withContext null
        val sense = entry.senses.firstOrNull { it.pos == pos && it.order == senseOrder } ?: return@withContext null
        val tag = DictionarySenseTag(dictionary.pack.id, dictionary.pack.version, sense.pos.wire, sense.order)
        val id = knowledge.addVocabulary(
            term = entry.lemma,
            meaningZh = sense.meaningZh,
            ipa = entry.ipa,
            pos = sense.pos.wire,
            forms = entry.irregularForms,
            senseOrder = sense.order,
            generationMetadataJson = GenerationMetadata.withDictionarySense("", tag),
        )
        val senseId = ProgressiveRules.senseId(lemma, pos, senseOrder)
        val now = now()
        val row = words.get(senseId) ?: ReadingWordEntity(
            senseId, lemma.lowercase(), pos.wire, senseOrder, zh, ReadingWordState.Vocabulary.ordinal,
            firstShownAt = now, lastEffectiveAt = 0, nextDueAt = 0, step = 0, effectiveCount = 0, lookups = 0, lastSignalAt = now, introducedDay = -1,
        )
        words.upsert(row.copy(state = ReadingWordState.Vocabulary.ordinal, vocabularyItemId = id ?: row.vocabularyItemId, lastSignalAt = now))
        id
    }

    /** 这个义项在词典里的中文意思，词卡上显示"此处释义"。 */
    suspend fun meaningOf(lemma: String, pos: PartOfSpeech, senseOrder: Int): String? = withContext(Dispatchers.IO) {
        dictionary.entry(lemma)?.senses?.firstOrNull { it.pos == pos && it.order == senseOrder }?.meaningZh
    }

    // ---- 迁移与维护 ----

    /** 每次渲染前：一次性迁移旧的书级词表，然后释放该出池的词。 */
    private suspend fun prepare() = prepareLock.withLock {
        migrateFromGlossary()
        releaseStale()
    }

    /**
     * §12.6：旧的书级词表 → 义项进度。旧版自动阶段不算回想证据，只迁成"见过"；池放不下的以暂停保存。
     * 有版本标记，重复跑无害（只插不存在的）。不扣当天额度。
     */
    private suspend fun migrateFromGlossary() {
        if (preferences.progressiveMigration.first() >= MIGRATION_VERSION) return
        val known = knowledge.knownLemmas()
        val now = now()
        val pool = preferences.progressiveSettings.first().poolCapped
        val legacy = books.allGlossary()
            .filter { !it.disabled && it.zh !in NativeWordPicker.GENERIC_WORDS && it.lemma.lowercase() !in known }
            .groupBy { ProgressiveRules.senseId(it.lemma, PartOfSpeech.parse(it.pos) ?: PartOfSpeech.Noun, it.senseOrder) }
            .entries
            .sortedByDescending { (_, rows) -> rows.sumOf { it.totalExposures } }
        val rows = legacy.mapIndexed { i, (senseId, group) ->
            val first = group.first()
            ReadingWordEntity(
                senseId = senseId,
                lemma = first.lemma.lowercase(),
                pos = first.pos,
                senseOrder = first.senseOrder,
                zh = first.zh,
                state = if (i < pool) ReadingWordState.Active.ordinal else ReadingWordState.Paused.ordinal,
                firstShownAt = now,
                lastEffectiveAt = 0,
                nextDueAt = now,
                step = 0,
                effectiveCount = 0,
                lookups = 0,
                lastSignalAt = now,
                introducedDay = -1,
                pausedAt = if (i < pool) 0 else now,
                origin = ORIGIN_MIGRATED,
            )
        }
        if (rows.isNotEmpty()) words.insertIgnore(rows)
        // 旧版在哪本书开过母语阅读，就当他已经主动开启过：默认轻量。
        val settings = preferences.progressiveSettings.first()
        if (!settings.enabled && books.allBooks().any { it.nativeEnabled }) {
            saveSettings(settings.copy(density = com.lazydog.english.domain.books.ReplacementDensity.Light.ordinal))
        }
        preferences.setProgressiveMigration(MIGRATION_VERSION)
    }

    /** §12.4「暂停追踪」：入池满 21 天，或连续 14 天没有信号，释放出池，进度留着。 */
    private suspend fun releaseStale() {
        val now = now()
        val stale = words.all().filter {
            it.state == ReadingWordState.Active.ordinal &&
                (now - it.firstShownAt >= ProgressiveRules.RELEASE_AFTER_IN_POOL || now - it.lastSignalAt >= ProgressiveRules.RELEASE_AFTER_IDLE)
        }
        if (stale.isNotEmpty()) words.upsertAll(stale.map { it.copy(state = ReadingWordState.Paused.ordinal, pausedAt = now) })
    }

    // ---- 小工具 ----

    private suspend fun addCredit(delta: Int) =
        preferences.updateProgressiveCredit(-ProgressiveRules.OPPORTUNITY_CHARS, ProgressiveRules.OPPORTUNITY_CHARS) { it + delta }

    private suspend fun learner(): LearnerVocabulary {
        val window = VocabularyCandidates.rankWindow(preferences.vocabLevelScore.first())
        val vocab = knowledge.vocabulary.first()
        val familiarStages = setOf(KnowledgeStage.Familiar.name, KnowledgeStage.Mastered.name)
        return LearnerVocabulary(
            window = window,
            familiar = vocab.filter { it.item.stage in familiarStages }.mapTo(HashSet()) { it.detail.term.lowercase() } +
                knowledge.knownLemmas(),
            learning = vocab.filter { it.item.stage !in familiarStages }.mapTo(HashSet()) { it.detail.term.lowercase() },
        )
    }

    private fun today(): Long = LocalDate.now(ZoneId.systemDefault()).toEpochDay()

    private fun newWord(r: RenderedReplacement, now: Long, state: ReadingWordState) = ReadingWordEntity(
        senseId = r.senseId,
        lemma = r.lemma.lowercase(),
        pos = r.pos.wire,
        senseOrder = r.senseOrder,
        zh = r.zh,
        state = state.ordinal,
        firstShownAt = now,
        lastEffectiveAt = now,
        nextDueAt = now,
        step = 0,
        effectiveCount = 1,
        lookups = 0,
        lastSignalAt = now,
        introducedDay = -1,
    )

    private fun glossaryRow(bookId: Long, r: RenderedReplacement, chapterIndex: Int) =
        BookGlossaryEntity(bookId, r.zh, r.lemma, r.pos.wire, r.senseOrder, SOURCE_LOCAL, chapterIndex, 0, 0, 0, 0, false)

    companion object {
        private const val TAG = "LazyDogNativeBook"
        private const val SEG_ASSET = "zh_seg_dict.txt"
        private const val INDEX_ASSET = "zh_en_index.txt"
        private const val MIGRATION_VERSION = 1
        private const val ORIGIN_MIGRATED = "migrated"
        /** 下一章的段落号加上这个数，和本章的段落分开。 */
        private const val NEXT_CHAPTER_PARAGRAPH = 1_000_000
        const val SOURCE_LOCAL = "local"

        private val json = Json { ignoreUnknownKeys = true }
        private val EXCLUDED = ListSerializer(String.serializer())

        /** 原文实例的内容版本：正文变了（重新下载、换了来源）旧的展示记录就对不上，不会沿用失效的范围。 */
        fun contentKey(bookId: Long, chapterIndex: Int, source: String) =
            "book:$bookId:$chapterIndex:${source.length}-${Integer.toHexString(source.hashCode())}"

        /** 「这里换得不对」的位置：（章, 原文偏移）。 */
        fun excluded(row: BookGlossaryEntity): Set<Pair<Int, Int>> =
            runCatching { json.decodeFromString(EXCLUDED, row.excludedJson) }.getOrDefault(emptyList())
                .mapNotNull { raw ->
                    val parts = raw.split(':')
                    val chapter = parts.getOrNull(0)?.toIntOrNull()
                    val offset = parts.getOrNull(1)?.toIntOrNull()
                    if (parts.size == 2 && chapter != null && offset != null) chapter to offset else null
                }
                .toSet()

        fun ReadingWordEntity.toSnapshot() = ReadingWordSnapshot(
            senseId = senseId,
            state = ReadingWordState.fromOrdinal(state),
            step = step,
            nextDueAt = nextDueAt,
            pausedAt = pausedAt,
        )
    }
}
