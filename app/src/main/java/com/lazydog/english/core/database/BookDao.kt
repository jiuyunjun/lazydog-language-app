package com.lazydog.english.core.database

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * 一本书（D-098 R2，`读书与网络阅读DESIGN.md` §6）。正文不在这里，在 `filesDir/books/<id>/` 下按章存文件。
 */
@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val author: String,
    /** `BookLanguage.wire`。 */
    val language: String,
    /** `BookSource.wire`。 */
    val source: String,
    /** 在线书库里的 id（古腾堡书号、AO3 作品号）；本地导入为空。恢复备份后按它重新下载。 */
    @ColumnInfo(defaultValue = "''") val sourceId: String = "",
    /** 封面文件名（在书的目录下）；没有封面为空，书架上生成纯色封面。 */
    @ColumnInfo(defaultValue = "''") val coverFile: String = "",
    val chapterCount: Int,
    val totalChars: Int,
    /** 阅读位置：章节序号和章内字符偏移。 */
    @ColumnInfo(defaultValue = "0") val chapterIndex: Int = 0,
    @ColumnInfo(defaultValue = "0") val chapterOffset: Int = 0,
    /** 读到全书的几分之几，0..1；书架上显示百分比用，按字数算。 */
    @ColumnInfo(defaultValue = "0") val progress: Double = 0.0,
    @ColumnInfo(defaultValue = "0") val readingMillis: Long = 0,
    val addedAt: Long,
    @ColumnInfo(defaultValue = "0") val lastOpenedAt: Long = 0,
    /** 旧版（R4）这本书是否开母语阅读；渐进学词（D-100）改成全局开关加按书暂停，这列只作迁移来源。 */
    @ColumnInfo(defaultValue = "0") val nativeEnabled: Boolean = false,
    /** 旧版英文量档位（R4），渐进学词（D-100）不再用；留作迁移来源和回退。 */
    @ColumnInfo(defaultValue = "1") val englishDose: Int = 1,
    /** 渐进学词在这本书里暂停（D-100 §12.2）。开关本身是全局的。 */
    @ColumnInfo(defaultValue = "0") val progressivePaused: Boolean = false,
)

/**
 * 书级词表（D-098 R4，设计文档 §5）：这本书里哪个中文词换成哪个英文词，以及支架撤到哪一步。
 * 每本书一个中文词一行；随书级联删除。
 */
@Entity(
    tableName = "book_glossary",
    primaryKeys = ["bookId", "zh"],
    foreignKeys = [ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)],
)
data class BookGlossaryEntity(
    val bookId: Long,
    val zh: String,
    val lemma: String,
    /** `PartOfSpeech.wire`。 */
    val pos: String,
    val senseOrder: Int,
    /** `local`：本地反查定的；`ai`：AI 消歧定的。 */
    val source: String,
    val introducedChapter: Int,
    /** `ScaffoldStage` 的序号。 */
    val stage: Int,
    val exposuresInStage: Int,
    val totalExposures: Int,
    val reveals: Int,
    val disabled: Boolean,
    /** 「这里不该换」的位置，`["章:偏移", …]`。 */
    @ColumnInfo(defaultValue = "'[]'") val excludedJson: String = "[]",
)

@Entity(
    tableName = "book_chapters",
    primaryKeys = ["bookId", "chapterIndex"],
    foreignKeys = [ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)],
)
data class BookChapterEntity(
    val bookId: Long,
    val chapterIndex: Int,
    val title: String,
    val charCount: Int,
)

@Entity(
    tableName = "book_bookmarks",
    foreignKeys = [ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["bookId"])],
)
data class BookBookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val chapterIndex: Int,
    val chapterOffset: Int,
    /** 书签那一句的摘录，书签列表里显示。 */
    val excerpt: String,
    val createdAt: Long,
)

@Dao
interface BookDao {

    @Query("SELECT * FROM books ORDER BY CASE WHEN lastOpenedAt > 0 THEN lastOpenedAt ELSE addedAt END DESC")
    fun observeBooks(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun getBook(id: Long): BookEntity?

    @Query("SELECT * FROM books WHERE id = :id")
    fun observeBook(id: Long): Flow<BookEntity?>

    @Query("SELECT * FROM books WHERE source = :source AND sourceId = :sourceId AND sourceId != '' LIMIT 1")
    suspend fun findBySource(source: String, sourceId: String): BookEntity?

    @Insert
    suspend fun insertBook(book: BookEntity): Long

    @Insert
    suspend fun insertChapters(chapters: List<BookChapterEntity>)

    @Transaction
    suspend fun insertBookWithChapters(book: BookEntity, chapters: (Long) -> List<BookChapterEntity>): Long {
        val id = insertBook(book)
        insertChapters(chapters(id))
        return id
    }

    @Query("SELECT * FROM book_chapters WHERE bookId = :bookId ORDER BY chapterIndex")
    suspend fun chapters(bookId: Long): List<BookChapterEntity>

    @Query("UPDATE books SET chapterIndex = :chapter, chapterOffset = :offset, progress = :progress, lastOpenedAt = :at WHERE id = :id")
    suspend fun saveProgress(id: Long, chapter: Int, offset: Int, progress: Double, at: Long)

    @Query("UPDATE books SET readingMillis = readingMillis + :millis WHERE id = :id")
    suspend fun addReadingTime(id: Long, millis: Long)

    @Query("UPDATE books SET coverFile = :coverFile WHERE id = :id")
    suspend fun setCover(id: Long, coverFile: String)

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun deleteBook(id: Long)

    @Query("UPDATE books SET progressivePaused = :paused WHERE id = :id")
    suspend fun setProgressivePaused(id: Long, paused: Boolean)

    @Query("SELECT * FROM book_glossary WHERE bookId = :bookId")
    suspend fun glossary(bookId: Long): List<BookGlossaryEntity>

    @Query("SELECT * FROM book_glossary WHERE bookId = :bookId ORDER BY introducedChapter, zh")
    fun observeGlossary(bookId: Long): Flow<List<BookGlossaryEntity>>

    @androidx.room.Upsert
    suspend fun upsertGlossary(words: List<BookGlossaryEntity>)

    @Query("SELECT * FROM book_glossary")
    suspend fun allGlossary(): List<BookGlossaryEntity>

    @Query("SELECT * FROM book_bookmarks WHERE bookId = :bookId ORDER BY chapterIndex, chapterOffset")
    fun observeBookmarks(bookId: Long): Flow<List<BookBookmarkEntity>>

    @Insert
    suspend fun insertBookmark(bookmark: BookBookmarkEntity): Long

    @Query("DELETE FROM book_bookmarks WHERE id = :id")
    suspend fun deleteBookmark(id: Long)

    // ---- 备份 / 恢复 ----

    @Query("SELECT * FROM books")
    suspend fun allBooks(): List<BookEntity>

    @Query("SELECT * FROM book_chapters")
    suspend fun allChapters(): List<BookChapterEntity>

    @Query("SELECT * FROM book_bookmarks")
    suspend fun allBookmarks(): List<BookBookmarkEntity>

    @Insert
    suspend fun insertBookmarks(bookmarks: List<BookBookmarkEntity>)

    @Query("DELETE FROM books")
    suspend fun clearAll()
}
