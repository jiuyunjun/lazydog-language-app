package com.lazydog.english.domain.books

/**
 * 读书的领域模型（`读书与网络阅读DESIGN.md` §3、§4，D-098 R2）。纯 Kotlin，不碰文件和 Android。
 */

/** 书的正文语言：决定排版（中文首行缩进）、朗读用哪种声音、能不能做母语阅读（只有中文书能）。 */
enum class BookLanguage(val wire: String, val labelZh: String) {
    English("en", "英文"),
    Chinese("zh", "中文"),
    ;

    companion object {
        fun fromWire(wire: String): BookLanguage = entries.firstOrNull { it.wire == wire } ?: English

        /** 按正文猜：汉字占字母和汉字总数的三成以上算中文书。 */
        fun detect(sample: String): BookLanguage {
            var han = 0
            var latin = 0
            sample.forEach { c ->
                when {
                    Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HAN -> han++
                    c in 'a'..'z' || c in 'A'..'Z' -> latin++
                }
            }
            val total = han + latin
            return if (total > 0 && han.toDouble() / total >= 0.3) Chinese else English
        }
    }
}

/** 书从哪来。[wire] 存库；公版书和 AO3 恢复备份时能按来源 id 重新下载，本地导入的不能。 */
enum class BookSource(val wire: String, val labelZh: String) {
    Imported("imported", "本地导入"),
    Gutenberg("gutenberg", "古腾堡计划"),
    Wikisource("wikisource", "维基文库"),
    Ao3("ao3", "AO3"),
    ;

    companion object {
        fun fromWire(wire: String): BookSource = entries.firstOrNull { it.wire == wire } ?: Imported
    }
}

/** 解析出来的一章：标题和段落。段落是纯文本，没有排版标记。 */
data class ParsedChapter(val title: String, val paragraphs: List<String>) {
    val charCount: Int get() = paragraphs.sumOf { it.length }
}

/** 解析出来的一本书，还没进库。 */
data class ParsedBook(
    val title: String,
    val author: String,
    val language: BookLanguage,
    val chapters: List<ParsedChapter>,
    /** 封面图的原始字节（EPUB 才有）；没有为 null，书架上生成纯色封面。 */
    val cover: ByteArray? = null,
    val coverExtension: String = "",
)

/**
 * 阅读位置：第几章、章内第几个字符。改字号、切换翻页方式之后，按这个回到同一句——
 * 页码会变，字符偏移不会。
 */
data class BookLocator(val chapter: Int, val offset: Int) {
    companion object {
        val Start = BookLocator(0, 0)
    }
}
