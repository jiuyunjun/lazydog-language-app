package com.lazydog.english.core.data

import android.content.Context
import android.util.Log
import com.lazydog.english.core.database.BookEntity
import com.lazydog.english.core.database.BookGlossaryEntity
import com.lazydog.english.core.model.KnowledgeStage
import com.lazydog.english.domain.books.BookLanguage
import com.lazydog.english.domain.books.ChapterText
import com.lazydog.english.domain.books.ChineseSegmenter
import com.lazydog.english.domain.books.EnglishDose
import com.lazydog.english.domain.books.GlossaryWord
import com.lazydog.english.domain.books.LearnerVocabulary
import com.lazydog.english.domain.books.NativeChapterRenderer
import com.lazydog.english.domain.books.NativeWordPicker
import com.lazydog.english.domain.books.RenderedChapter
import com.lazydog.english.domain.books.Scaffold
import com.lazydog.english.domain.books.ScaffoldStage
import com.lazydog.english.domain.books.ZhEnIndex
import com.lazydog.english.domain.vocabulary.DictionaryLookup
import com.lazydog.english.domain.vocabulary.PartOfSpeech
import com.lazydog.english.domain.vocabulary.VocabularyCandidates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * 中文书的母语阅读（D-098 R4，`读书与网络阅读DESIGN.md` §5）：本地分词 → 反查 → 选生词 → 全书替换。
 *
 * 分词词典和反查表只在第一次用时加载（二十来 MB），离开阅读器调 [release] 放掉。
 * 规则全在 `domain/books`，这里只管加载资源、读写词表、把规则串起来。
 */
class NativeBookService(
    private val context: Context,
    private val books: BookRepository,
    private val knowledge: KnowledgeRepository,
    private val preferences: UserPreferences,
    private val dictionary: DictionaryLookup,
) {
    private val loadLock = Mutex()
    private var segmenter: ChineseSegmenter? = null
    private var index: ZhEnIndex? = null
    /** 同一本书的词表读写串行：翻页记曝光和换章规划新词会同时发生。 */
    private val glossaryLock = Mutex()

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

    /** 这本书能不能做母语阅读：只有中文书。 */
    fun supports(book: BookEntity): Boolean = BookLanguage.fromWire(book.language) == BookLanguage.Chinese

    /**
     * 渲染一章：第一次打开这一章时先按英文量挑新词进词表（§5.6），再按词表全章替换（§5.7）。
     * 资源读不出来时退回原文，书照样能读。
     */
    suspend fun render(book: BookEntity, chapterIndex: Int, source: String, titleLength: Int): RenderedChapter {
        if (!book.nativeEnabled || !supports(book)) return RenderedChapter.plain(source)
        return try {
            val (seg, idx) = resources()
            // 标题不换：页眉、目录里显示的都是原标题，换了对不上。
            val tokens = withContext(Dispatchers.Default) { seg.segment(source).filter { it.start >= titleLength } }
            val glossary = glossaryLock.withLock {
                val existing = books.glossary(book.id).associateBy { it.zh }
                val planned = plannedChapters(book.id)
                if (chapterIndex !in planned) {
                    val picked = NativeWordPicker.pick(
                        chapterTokens = tokens,
                        index = idx,
                        learner = learner(),
                        // 全书统计还没算完就先按本章次数挑，不让第一章等整本书分完词。
                        bookSpread = cachedSpread(book.id).orEmpty(),
                        known = existing.keys,
                        count = EnglishDose.fromOrdinal(book.englishDose).newWordsPerChapter,
                    )
                    books.saveGlossary(
                        picked.map {
                            BookGlossaryEntity(
                                bookId = book.id, zh = it.zh, lemma = it.lemma, pos = it.pos.wire, senseOrder = it.senseOrder,
                                source = SOURCE_LOCAL, introducedChapter = chapterIndex, stage = 0, exposuresInStage = 0,
                                totalExposures = 0, reveals = 0, disabled = false,
                            )
                        },
                    )
                    savePlanned(book.id, planned + chapterIndex)
                }
                books.glossary(book.id).associate { it.zh to it.toDomain() }
            }
            withContext(Dispatchers.Default) { NativeChapterRenderer.render(source, tokens, glossary, chapterIndex) }
        } catch (e: Exception) {
            Log.w(TAG, "母语阅读渲染失败，退回原文", e)
            RenderedChapter.plain(source)
        }
    }

    /** 这些替换被看到了：各记一次曝光，推进支架（§5.7）。 */
    suspend fun recordExposures(bookId: Long, zhWords: Collection<String>) = update(bookId, zhWords) { Scaffold.onExposure(it) }

    /** 点开看了中文：退回上一阶段。 */
    suspend fun recordReveal(bookId: Long, zh: String) = update(bookId, listOf(zh)) { Scaffold.onReveal(it) }

    /** 「这里不该换」：只记这一处。 */
    suspend fun excludeHere(bookId: Long, zh: String, chapterIndex: Int, sourceOffset: Int) =
        update(bookId, listOf(zh)) { it.copy(excluded = it.excluded + (chapterIndex to sourceOffset)) }

    /** 「这本书都别换」或者恢复。 */
    suspend fun setDisabled(bookId: Long, zh: String, disabled: Boolean) = update(bookId, listOf(zh)) { it.copy(disabled = disabled) }

    private suspend fun update(bookId: Long, zhWords: Collection<String>, change: (GlossaryWord) -> GlossaryWord) {
        if (zhWords.isEmpty()) return
        glossaryLock.withLock {
            val rows = books.glossary(bookId).filter { it.zh in zhWords }
            books.saveGlossary(rows.map { row -> row.withDomain(change(row.toDomain())) })
        }
    }

    /** 把书里的这个词加进生词本：身份取内置词典，和每日新词同一个口径（D-095），之后后台补全。 */
    suspend fun addToVocabulary(row: BookGlossaryEntity): Long? = withContext(Dispatchers.IO) {
        val entry = dictionary.entry(row.lemma) ?: return@withContext null
        val pos = PartOfSpeech.parse(row.pos)
        val sense = entry.senses.firstOrNull { it.pos == pos && it.order == row.senseOrder } ?: return@withContext null
        val tag = DictionarySenseTag(dictionary.pack.id, dictionary.pack.version, sense.pos.wire, sense.order)
        knowledge.addVocabulary(
            term = entry.lemma,
            meaningZh = sense.meaningZh,
            ipa = entry.ipa,
            pos = sense.pos.wire,
            forms = entry.irregularForms,
            senseOrder = sense.order,
            generationMetadataJson = GenerationMetadata.withDictionarySense("", tag),
        )
    }

    private suspend fun learner(): LearnerVocabulary {
        val window = VocabularyCandidates.rankWindow(preferences.vocabLevelScore.first())
        val vocab = knowledge.vocabulary.first()
        val familiarStages = setOf(KnowledgeStage.Familiar.name, KnowledgeStage.Mastered.name)
        return LearnerVocabulary(
            window = window,
            familiar = vocab.filter { it.item.stage in familiarStages }.mapTo(HashSet()) { it.detail.term.lowercase() },
            learning = vocab.filter { it.item.stage !in familiarStages }.mapTo(HashSet()) { it.detail.term.lowercase() },
        )
    }

    /**
     * 每个中文词在全书多少章出现过（§5.5「优先全书反复出现的词」）。第一次开母语阅读时把全书分一遍词，
     * 结果存在书的目录下，之后直接读。只数能被换的实词，表不大。
     */
    suspend fun spread(bookId: Long): Map<String, Int> {
        cachedSpread(bookId)?.let { return it }
        val (seg, _) = resources()
        val chapters = books.chapters(bookId)
        val counts = HashMap<String, Int>()
        withContext(Dispatchers.Default) {
            for (chapter in chapters) {
                val paragraphs = books.chapterParagraphs(bookId, chapter.chapterIndex) ?: continue
                seg.segment(paragraphs.joinToString("\n"))
                    .filter { it.word.length >= 2 && it.pos in NativeWordPicker.REPLACEABLE_POS }
                    .mapTo(HashSet()) { it.word }
                    .forEach { counts[it] = (counts[it] ?: 0) + 1 }
            }
        }
        // 只出现在一章的词对"全书复现"没有贡献，不存，省空间。
        val kept = counts.filterValues { it >= 2 }
        books.writeBookFile(bookId, SPREAD_FILE, json.encodeToString(SPREAD_SERIALIZER, kept))
        return kept
    }

    private suspend fun cachedSpread(bookId: Long): Map<String, Int>? =
        books.readBookFile(bookId, SPREAD_FILE)?.let { raw -> runCatching { json.decodeFromString(SPREAD_SERIALIZER, raw) }.getOrNull() }

    private suspend fun plannedChapters(bookId: Long): Set<Int> =
        books.readBookFile(bookId, PLANNED_FILE)
            ?.let { raw -> runCatching { json.decodeFromString(PLANNED_SERIALIZER, raw) }.getOrNull() }
            ?.toSet()
            .orEmpty()

    private suspend fun savePlanned(bookId: Long, planned: Set<Int>) =
        books.writeBookFile(bookId, PLANNED_FILE, json.encodeToString(PLANNED_SERIALIZER, planned.sorted()))

    companion object {
        private const val TAG = "LazyDogNativeBook"
        private const val SEG_ASSET = "zh_seg_dict.txt"
        private const val INDEX_ASSET = "zh_en_index.txt"
        private const val SPREAD_FILE = "native_spread.json"
        private const val PLANNED_FILE = "native_planned.json"
        const val SOURCE_LOCAL = "local"

        private val json = Json { ignoreUnknownKeys = true }
        private val SPREAD_SERIALIZER = MapSerializer(String.serializer(), Int.serializer())
        private val PLANNED_SERIALIZER = ListSerializer(Int.serializer())

        fun BookGlossaryEntity.toDomain() = GlossaryWord(
            zh = zh,
            lemma = lemma,
            pos = PartOfSpeech.parse(pos) ?: PartOfSpeech.Noun,
            senseOrder = senseOrder,
            introducedChapter = introducedChapter,
            stage = ScaffoldStage.entries.getOrElse(stage) { ScaffoldStage.Introduced },
            exposuresInStage = exposuresInStage,
            totalExposures = totalExposures,
            reveals = reveals,
            disabled = disabled,
            excluded = runCatching { json.decodeFromString(ListSerializer(String.serializer()), excludedJson) }
                .getOrDefault(emptyList())
                .mapNotNull { it.split(':').takeIf { p -> p.size == 2 }?.let { p -> p[0].toIntOrNull()?.let { c -> p[1].toIntOrNull()?.let { o -> c to o } } } }
                .toSet(),
        )

        fun BookGlossaryEntity.withDomain(word: GlossaryWord) = copy(
            stage = word.stage.ordinal,
            exposuresInStage = word.exposuresInStage,
            totalExposures = word.totalExposures,
            reveals = word.reveals,
            disabled = word.disabled,
            excludedJson = json.encodeToString(ListSerializer(String.serializer()), word.excluded.map { "${it.first}:${it.second}" }),
        )
    }
}
