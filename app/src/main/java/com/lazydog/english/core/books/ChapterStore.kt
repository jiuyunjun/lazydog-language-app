package com.lazydog.english.core.books

import java.io.File

/**
 * 书的正文文件（设计文档 §6）：`<root>/<bookId>/<章节序号>.txt`，段落之间一个换行。
 * 正文不进数据库：一本长篇几 MB，进库会拖慢备份和迁移，查询也用不上 SQL。
 */
class ChapterStore(private val root: File) {

    fun bookDir(bookId: Long) = File(root, bookId.toString())

    fun write(bookId: Long, chapterIndex: Int, paragraphs: List<String>) {
        val dir = bookDir(bookId).apply { mkdirs() }
        File(dir, "$chapterIndex.txt").writeText(paragraphs.joinToString(SEPARATOR), Charsets.UTF_8)
    }

    /** 文件不在（恢复了备份、还没重新导入）返回 null。 */
    fun read(bookId: Long, chapterIndex: Int): String? =
        File(bookDir(bookId), "$chapterIndex.txt").takeIf { it.exists() }?.readText(Charsets.UTF_8)

    fun hasContent(bookId: Long): Boolean = File(bookDir(bookId), "0.txt").exists()

    fun writeCover(bookId: Long, bytes: ByteArray, extension: String): String {
        val name = "cover." + extension.lowercase().ifBlank { "jpg" }
        File(bookDir(bookId).apply { mkdirs() }, name).writeBytes(bytes)
        return name
    }

    fun coverFile(bookId: Long, name: String): File? =
        name.takeIf { it.isNotBlank() }?.let { File(bookDir(bookId), it) }?.takeIf { it.exists() }

    fun delete(bookId: Long) {
        bookDir(bookId).deleteRecursively()
    }

    companion object {
        /** 段落分隔。读回来按它切段；正文里的段落本身不含换行（解析时已按行切开）。 */
        const val SEPARATOR = "\n"
    }
}
