package com.lazydog.english.core.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.lazydog.english.core.books.ChapterStore
import com.lazydog.english.core.books.EpubParser
import com.lazydog.english.core.books.TextBookParser
import com.lazydog.english.core.database.AppDatabase
import com.lazydog.english.core.database.BookBookmarkEntity
import com.lazydog.english.core.database.BookChapterEntity
import com.lazydog.english.core.database.BookEntity
import com.lazydog.english.core.database.BookGlossaryEntity
import com.lazydog.english.domain.books.BookLocator
import com.lazydog.english.domain.books.BookSource
import com.lazydog.english.domain.books.ParsedBook
import java.io.File
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * 书的读写入口（D-098 R2）：导入、书架、章节正文、进度、书签、删除。页面不碰文件和 DAO。
 */
class BookRepository(
    private val context: Context,
    database: AppDatabase,
    private val now: () -> Instant = Instant::now,
) {
    private val dao = database.bookDao()
    private val store = ChapterStore(File(context.filesDir, "books"))

    val books: Flow<List<BookEntity>> = dao.observeBooks()

    fun observeBook(id: Long): Flow<BookEntity?> = dao.observeBook(id)

    sealed interface Imported {
        data class Success(val bookId: Long) : Imported
        data class Failure(val reason: String) : Imported
    }

    /** 从系统文件选择器选中的文件导入。按扩展名认 EPUB / TXT，原文件不动。 */
    suspend fun importFrom(uri: Uri): Imported = withContext(Dispatchers.IO) {
        val name = displayName(uri)
        val extension = name.substringAfterLast('.', "").lowercase()
        val parsed = when (extension) {
            "epub" -> {
                // ZipFile 要随机读，先拷到缓存目录。
                val temp = File.createTempFile("import", ".epub", context.cacheDir)
                try {
                    context.contentResolver.openInputStream(uri)?.use { input -> temp.outputStream().use { input.copyTo(it) } }
                        ?: return@withContext Imported.Failure("打不开这个文件")
                    EpubParser.parse(temp) ?: return@withContext Imported.Failure("这个 EPUB 读不出正文（可能有 DRM 加密，或者文件坏了）")
                } finally {
                    temp.delete()
                }
            }
            "txt" -> {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: return@withContext Imported.Failure("打不开这个文件")
                TextBookParser.parse(bytes, name) ?: return@withContext Imported.Failure("这个 TXT 是空的")
            }
            else -> return@withContext Imported.Failure("只支持 EPUB 和 TXT")
        }
        Imported.Success(store(parsed, BookSource.Imported, sourceId = ""))
    }

    /** 解析好的书进库：先插行拿到 id，再写章节文件和封面。写文件失败就把这本删掉，不留半本。 */
    suspend fun store(parsed: ParsedBook, source: BookSource, sourceId: String): Long = withContext(Dispatchers.IO) {
        if (sourceId.isNotBlank()) dao.findBySource(source.wire, sourceId)?.let { return@withContext it.id }
        val id = dao.insertBookWithChapters(
            BookEntity(
                title = parsed.title,
                author = parsed.author,
                language = parsed.language.wire,
                source = source.wire,
                sourceId = sourceId,
                chapterCount = parsed.chapters.size,
                totalChars = parsed.chapters.sumOf { it.charCount },
                addedAt = now().toEpochMilli(),
            ),
        ) { bookId ->
            parsed.chapters.mapIndexed { index, chapter -> BookChapterEntity(bookId, index, chapter.title, chapter.charCount) }
        }
        try {
            parsed.chapters.forEachIndexed { index, chapter -> store.write(id, index, chapter.paragraphs) }
            parsed.cover?.let { dao.setCover(id, store.writeCover(id, it, parsed.coverExtension)) }
        } catch (e: Exception) {
            store.delete(id)
            dao.deleteBook(id)
            throw e
        }
        id
    }

    suspend fun book(id: Long): BookEntity? = dao.getBook(id)

    /** 在线书库里这本已经下过了吗（按来源和书库编号认）。 */
    suspend fun existing(source: BookSource, sourceId: String): Long? =
        sourceId.takeIf { it.isNotBlank() }?.let { dao.findBySource(source.wire, it)?.id }

    suspend fun chapters(bookId: Long): List<BookChapterEntity> = dao.chapters(bookId)

    /** 一章的段落；正文文件不在（恢复备份后还没重新导入）返回 null。 */
    suspend fun chapterParagraphs(bookId: Long, chapterIndex: Int): List<String>? = withContext(Dispatchers.IO) {
        store.read(bookId, chapterIndex)?.split(ChapterStore.SEPARATOR)
    }

    suspend fun hasContent(bookId: Long): Boolean = withContext(Dispatchers.IO) { store.hasContent(bookId) }

    fun coverFile(book: BookEntity): File? = store.coverFile(book.id, book.coverFile)

    suspend fun saveProgress(bookId: Long, locator: BookLocator, progress: Double) =
        dao.saveProgress(bookId, locator.chapter, locator.offset, progress.coerceIn(0.0, 1.0), now().toEpochMilli())

    suspend fun addReadingTime(bookId: Long, millis: Long) {
        if (millis > 0) dao.addReadingTime(bookId, millis)
    }

    fun bookmarks(bookId: Long): Flow<List<BookBookmarkEntity>> = dao.observeBookmarks(bookId)

    suspend fun addBookmark(bookId: Long, locator: BookLocator, excerpt: String) {
        dao.insertBookmark(
            BookBookmarkEntity(
                bookId = bookId,
                chapterIndex = locator.chapter,
                chapterOffset = locator.offset,
                excerpt = excerpt.take(EXCERPT_LENGTH),
                createdAt = now().toEpochMilli(),
            ),
        )
    }

    suspend fun deleteBookmark(id: Long) = dao.deleteBookmark(id)

    // ---- 母语阅读（D-098 R4）----

    suspend fun setProgressivePaused(bookId: Long, paused: Boolean) = dao.setProgressivePaused(bookId, paused)

    suspend fun allBooks(): List<BookEntity> = dao.allBooks()

    suspend fun allGlossary(): List<BookGlossaryEntity> = dao.allGlossary()

    suspend fun glossary(bookId: Long): List<BookGlossaryEntity> = dao.glossary(bookId)

    fun observeGlossary(bookId: Long): Flow<List<BookGlossaryEntity>> = dao.observeGlossary(bookId)

    suspend fun saveGlossary(words: List<BookGlossaryEntity>) {
        if (words.isNotEmpty()) dao.upsertGlossary(words)
    }

    /** 书目录下的小文件（母语阅读的全书词频统计、已规划章节）；不存在返回 null。 */
    suspend fun readBookFile(bookId: Long, name: String): String? = withContext(Dispatchers.IO) {
        File(store.bookDir(bookId), name).takeIf { it.exists() }?.readText(Charsets.UTF_8)
    }

    suspend fun writeBookFile(bookId: Long, name: String, text: String) = withContext(Dispatchers.IO) {
        File(store.bookDir(bookId).apply { mkdirs() }, name).writeText(text, Charsets.UTF_8)
    }

    suspend fun delete(bookId: Long) = withContext(Dispatchers.IO) {
        dao.deleteBook(bookId)
        store.delete(bookId)
    }

    private fun displayName(uri: Uri): String =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment.orEmpty()

    companion object {
        const val EXCERPT_LENGTH = 80
    }
}
