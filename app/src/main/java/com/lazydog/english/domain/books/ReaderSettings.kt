package com.lazydog.english.domain.books

import kotlinx.serialization.Serializable

/** 阅读器的排版设置（设计文档 §4.2）。全局一份，存在 DataStore，所有书共用。 */
@Serializable
data class ReaderSettings(
    val fontSizeSp: Int = 19,
    /** 行高是字号的几倍。 */
    val lineHeight: Float = 1.6f,
    val theme: ReaderTheme = ReaderTheme.Light,
    val pageMode: PageMode = PageMode.Paged,
    /** 衬线体读长文更舒服；关掉用系统无衬线体。 */
    val serif: Boolean = true,
    /** 左右页边距，dp。 */
    val marginDp: Int = 22,
) {
    fun withFontSize(delta: Int) = copy(fontSizeSp = (fontSizeSp + delta).coerceIn(MIN_FONT, MAX_FONT))

    companion object {
        const val MIN_FONT = 13
        const val MAX_FONT = 32
        val LINE_HEIGHTS = listOf(1.35f, 1.6f, 1.9f)
        val MARGINS = listOf(14, 22, 32)
    }
}

/** 主题只存名字，颜色在界面层定。 */
@Serializable
enum class ReaderTheme(val labelZh: String) {
    Light("白"),
    Sepia("米黄"),
    Dark("深色"),
    Black("纯黑"),
}

@Serializable
enum class PageMode(val labelZh: String) {
    /** 左右翻页：点左右两侧、左右滑、音量键。 */
    Paged("左右翻页"),
    /** 上下滚动：一章连续滚，章末接下一章。 */
    Scroll("上下滚动"),
}

/**
 * 章节的显示文本（设计文档 §4.2、§4.3）。进度偏移按这份文本算，所以它的拼法必须固定：
 * 第一行是章节标题，后面每段一行；段首缩进用字符而不是段落样式——一页从段落中间开始时，
 * 段落样式会把这一页的第一行也缩进，字符不会。
 */
object ChapterText {

    /** 中文用两个全角空格；英文用两个 em 空格，宽度相近。 */
    fun indent(language: BookLanguage): String = if (language == BookLanguage.Chinese) "\u3000\u3000" else "\u2003\u2003"

    fun build(title: String, paragraphs: List<String>, language: BookLanguage): String =
        buildString {
            append(title)
            paragraphs.forEach { p ->
                append('\n')
                append(indent(language))
                append(p)
            }
        }

    /** 标题占的字符数（不含换行），界面上把这一段加粗放大。 */
    fun titleLength(title: String): Int = title.length

    /**
     * 全书进度：前面各章字数加上本章偏移，除以全书字数。显示文本比原文多了标题和缩进，
     * 差得不多，书架上的百分比用不着那么准。
     */
    fun progress(chapterCharCounts: List<Int>, locator: BookLocator): Double {
        val total = chapterCharCounts.sum().takeIf { it > 0 } ?: return 0.0
        val before = chapterCharCounts.take(locator.chapter).sum()
        val within = locator.offset.coerceAtMost(chapterCharCounts.getOrElse(locator.chapter) { 0 })
        return ((before + within).toDouble() / total).coerceIn(0.0, 1.0)
    }
}
