package com.lazydog.english.feature.books

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextDecoration
import com.lazydog.english.domain.books.ReaderSettings
import com.lazydog.english.domain.books.RenderedReplacement
import com.lazydog.english.domain.books.ScaffoldStage
import com.lazydog.english.domain.books.ReaderTheme

/** 一页：章节显示文本里的 [start]..[end)。 */
internal data class ReaderPage(val start: Int, val end: Int)

/** 阅读器四种主题的颜色。只在阅读器里用，不跟随 App 的深浅色。 */
internal data class ReaderColors(val background: Color, val text: Color, val secondary: Color)

internal fun ReaderTheme.colors(): ReaderColors = when (this) {
    ReaderTheme.Light -> ReaderColors(Color(0xFFFFFFFF), Color(0xFF1F1F1F), Color(0xFF8A8A8A))
    ReaderTheme.Sepia -> ReaderColors(Color(0xFFF4EBD8), Color(0xFF4A3B2A), Color(0xFF9A8670))
    ReaderTheme.Dark -> ReaderColors(Color(0xFF1E1F22), Color(0xFFD5D5D5), Color(0xFF8C8C8C))
    ReaderTheme.Black -> ReaderColors(Color(0xFF000000), Color(0xFFB4B4B4), Color(0xFF707070))
}

/**
 * 正文样式。分页和显示必须用同一份：
 * - 断行用 [LineBreak.Simple]（逐行贪心）。默认的"整段平衡"断行会根据整段内容调整每一行，
 *   同一段从页中间开始重新排时就会换行不同，页底那一行可能被挤出去；贪心断行不会。
 * - 不自动断字，理由同上。
 */
internal fun readerStyle(settings: ReaderSettings, color: Color): TextStyle = TextStyle(
    color = color,
    fontSize = settings.fontSizeSp.sp,
    lineHeight = (settings.fontSizeSp * settings.lineHeight).sp,
    fontFamily = if (settings.serif) FontFamily.Serif else FontFamily.SansSerif,
    lineBreak = LineBreak.Simple,
    hyphens = Hyphens.None,
)

/** 章节显示文本 → 带样式的版本：开头的标题加粗放大。 */
internal fun chapterAnnotated(text: String, titleLength: Int): AnnotatedString =
    AnnotatedString(
        text = text,
        spanStyles = if (titleLength > 0) {
            listOf(AnnotatedString.Range(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 1.25.em), 0, titleLength))
        } else {
            emptyList()
        },
    )

/**
 * 按视口切页：整章排一次，按行累计高度，装不下的行进下一页。一页至少一行，
 * 免得超大字号下死循环。
 */
internal fun paginate(
    measurer: TextMeasurer,
    text: AnnotatedString,
    style: TextStyle,
    widthPx: Int,
    heightPx: Int,
): List<ReaderPage> {
    if (text.isEmpty() || widthPx <= 0 || heightPx <= 0) return listOf(ReaderPage(0, text.length))
    val layout: TextLayoutResult = measurer.measure(text, style, constraints = Constraints(maxWidth = widthPx))
    val pages = mutableListOf<ReaderPage>()
    var line = 0
    while (line < layout.lineCount) {
        val top = layout.getLineTop(line)
        var end = line
        while (end < layout.lineCount && layout.getLineBottom(end) - top <= heightPx) end++
        if (end == line) end = line + 1
        pages += ReaderPage(layout.getLineStart(line), layout.getLineEnd(end - 1))
        line = end
    }
    return pages
}

/** 替换上去的英文在这四种主题下的颜色：要和正文分得开，又不刺眼。 */
internal fun ReaderTheme.accent(): Color = when (this) {
    ReaderTheme.Light -> Color(0xFF1F5FAD)
    ReaderTheme.Sepia -> Color(0xFF2F5E8C)
    ReaderTheme.Dark -> Color(0xFF8AB4F8)
    ReaderTheme.Black -> Color(0xFF7FA8E0)
}

/**
 * 母语阅读的章节样式（设计文档 §5.7）：引入阶段强调色加下划线（新词，点一下看中文），
 * 提示阶段只用强调色，独立阶段和正文一样。
 */
internal fun nativeAnnotated(
    text: String,
    titleLength: Int,
    replacements: List<RenderedReplacement>,
    theme: ReaderTheme,
): AnnotatedString {
    if (replacements.isEmpty()) return chapterAnnotated(text, titleLength)
    val accent = theme.accent()
    val spans = ArrayList<AnnotatedString.Range<SpanStyle>>()
    if (titleLength > 0) spans += AnnotatedString.Range(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 1.25.em), 0, titleLength)
    replacements.forEach { r ->
        val style = when (r.stage) {
            ScaffoldStage.Introduced -> SpanStyle(color = accent, fontWeight = FontWeight.Medium, textDecoration = TextDecoration.Underline)
            ScaffoldStage.Hinted -> SpanStyle(color = accent, fontWeight = FontWeight.Medium)
            ScaffoldStage.Independent -> null
        }
        if (style != null) spans += AnnotatedString.Range(style, r.englishStart, r.englishEnd)
    }
    return AnnotatedString(text, spanStyles = spans)
}

/** 偏移落在哪一页。超出末尾的（"从上一章末尾进来"）落到最后一页。 */
internal fun pageIndexFor(pages: List<ReaderPage>, offset: Int): Int {
    if (pages.isEmpty()) return 0
    val index = pages.indexOfLast { it.start <= offset }
    return index.coerceIn(0, pages.lastIndex)
}
