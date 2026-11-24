package com.lazydog.english.core.data

import android.content.Context
import android.util.Log
import com.lazydog.english.core.books.EpubParser
import com.lazydog.english.core.network.Ao3Client
import com.lazydog.english.core.network.CatalogDownload
import com.lazydog.english.core.network.GutendexClient
import com.lazydog.english.core.network.WikisourceClient
import com.lazydog.english.domain.books.BookLanguage
import com.lazydog.english.domain.books.CatalogEntry
import com.lazydog.english.domain.books.CatalogResult
import com.lazydog.english.domain.books.CatalogSource
import com.lazydog.english.domain.books.ParsedBook
import com.lazydog.english.domain.books.ParsedChapter
import com.lazydog.english.domain.books.TraditionalToSimplified
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 在线书库（D-098 R3）：检索、下载、进书架。页面只管状态。
 *
 * 维基文库的书是逐章取的，一部一百二十回要发一百多个请求，所以下载报进度；
 * 繁体在进书架前整本转简体。AO3 对自动下载限流时返回 [Downloaded.Busy]，页面让用户去浏览器下再导入。
 */
class BookCatalogService(
    private val context: Context,
    private val gutendex: GutendexClient,
    private val wikisource: WikisourceClient,
    private val ao3: Ao3Client,
    private val books: BookRepository,
) {

    /** 繁转简的对照表只在下维基文库的书时用到，懒加载。读不出来就不转，书照样能读。 */
    private val t2s: TraditionalToSimplified by lazy {
        try {
            context.assets.open(T2S_ASSET).bufferedReader(Charsets.UTF_8).useLines { TraditionalToSimplified.parse(it) }
        } catch (e: Exception) {
            Log.w(TAG, "繁简对照表读不出来，维基文库的书不转简体", e)
            TraditionalToSimplified(emptyMap())
        }
    }

    sealed interface Downloaded {
        data class Success(val bookId: Long) : Downloaded
        /** 对方忙或限流：给出网页地址，让用户在浏览器里下好再导入。 */
        data class Busy(val reason: String, val webUrl: String) : Downloaded
        data class Failure(val reason: String) : Downloaded
    }

    suspend fun search(source: CatalogSource, query: String): CatalogResult = when (source) {
        CatalogSource.Gutenberg -> gutendex.search(query)
        CatalogSource.Wikisource -> wikisource.search(query).let { result ->
            // 标题给人看的转简体；id 仍是原标题，取章节要用它。
            result.copy(entries = result.entries.map { it.copy(title = t2sConvert(it.title), summary = t2sConvert(it.summary)) })
        }
        CatalogSource.Ao3 -> ao3.search(query)
    }

    private suspend fun t2sConvert(text: String) = withContext(Dispatchers.Default) { t2s.convert(text) }

    /** 下载进书架。已经下过的直接返回那一本。[onProgress] 报「第 3 / 120 回」这类进度。 */
    suspend fun download(entry: CatalogEntry, onProgress: (String) -> Unit = {}): Downloaded {
        books.existing(entry.source.book, entry.id)?.let { return Downloaded.Success(it) }
        return when (entry.source) {
            CatalogSource.Gutenberg -> fromEpub(entry, gutendex.download(entry, gutendex.epubUrlOf(entry.id)))
            CatalogSource.Ao3 -> fromEpub(entry, ao3.download(entry))
            CatalogSource.Wikisource -> fromWikisource(entry, onProgress)
        }
    }

    private suspend fun fromEpub(entry: CatalogEntry, download: CatalogDownload): Downloaded = when (download) {
        is CatalogDownload.Busy -> Downloaded.Busy(download.reason, entry.webUrl)
        is CatalogDownload.Failed -> Downloaded.Failure(download.reason)
        is CatalogDownload.Epub -> withContext(Dispatchers.IO) {
            val temp = File.createTempFile("catalog", ".epub", context.cacheDir)
            try {
                temp.writeBytes(download.bytes)
                val parsed = EpubParser.parse(temp) ?: return@withContext Downloaded.Failure("这本书的 EPUB 读不出正文")
                Downloaded.Success(books.store(parsed, entry.source.book, entry.id))
            } finally {
                temp.delete()
            }
        }
    }

    private suspend fun fromWikisource(entry: CatalogEntry, onProgress: (String) -> Unit): Downloaded {
        val pages = wikisource.chapterPages(entry.id) ?: return Downloaded.Failure("取不到这部作品的目录")
        val chapters = mutableListOf<ParsedChapter>()
        pages.forEachIndexed { index, page ->
            onProgress("正在取第 ${index + 1} / ${pages.size} 章")
            // 取不到的章跳过，不让一章的网络抖动毁掉整本。
            wikisource.chapter(page)?.let { chapters += it }
        }
        if (chapters.isEmpty()) return Downloaded.Failure("这部作品没有取到正文")
        onProgress("正在转成简体…")
        val book = withContext(Dispatchers.Default) {
            t2s.convert(ParsedBook(title = entry.id, author = "", language = BookLanguage.Chinese, chapters = chapters))
        }
        return Downloaded.Success(books.store(book, entry.source.book, entry.id))
    }

    private companion object {
        const val TAG = "LazyDogCatalog"
        const val T2S_ASSET = "zh_t2s.txt"
    }
}
