package com.lazydog.english.domain.books

/**
 * TXT 电子书切章（设计文档 §3.2）。
 *
 * 认这些章节标题：「第一章」「第12章」「第三回」「第五节」「卷一」「序章」「楔子」「尾声」「后记」，
 * 以及英文的「Chapter 1」「CHAPTER IV」「Chapter One」。标题行要短（40 字以内），
 * 正文里偶尔提到「第一章」的长句不算。切不出两章以上时，按约 [FALLBACK_CHARS] 字一段切，
 * 段落不切开。
 */
object TextBookSplitter {

    const val FALLBACK_CHARS = 5_000

    private const val MAX_TITLE_LENGTH = 40

    private val CHINESE_HEADING = Regex(
        """^\s*(第[0-9零一二三四五六七八九十百千两〇]+[章回节卷部集篇]|卷[0-9零一二三四五六七八九十百千]+|序章|序言|楔子|引子|尾声|后记|番外)(\s|：|:|$).*""",
    )
    private val ENGLISH_HEADING = Regex(
        """^\s*(CHAPTER|Chapter|BOOK|Book|PART|Part)\s+([0-9]+|[IVXLCDM]+|One|Two|Three|Four|Five|Six|Seven|Eight|Nine|Ten|Eleven|Twelve|[A-Z][a-z]+)\b.*""",
    )

    fun isHeading(line: String): Boolean {
        val clean = line.trim()
        if (clean.isEmpty() || clean.length > MAX_TITLE_LENGTH) return false
        return CHINESE_HEADING.matches(clean) || ENGLISH_HEADING.matches(clean)
    }

    /**
     * [text] → 章节。段落按空行或单换行切（TXT 两种都有）；去掉每段首尾空白和全角空格缩进。
     * 第一个标题之前的内容（书名、作者、简介）如果有，单独成「开篇」一章。
     */
    fun split(text: String): List<ParsedChapter> {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val chapters = mutableListOf<ParsedChapter>()
        var title = ""
        var paragraphs = mutableListOf<String>()
        fun flush() {
            if (paragraphs.isNotEmpty()) chapters += ParsedChapter(title.ifBlank { "开篇" }, paragraphs)
            paragraphs = mutableListOf()
        }
        for (raw in lines) {
            val line = raw.trim { it.isWhitespace() || it == '　' }
            if (line.isEmpty()) continue
            if (isHeading(line)) {
                flush()
                title = line
            } else {
                paragraphs += line
            }
        }
        flush()
        // 只有一章或切不出来：按字数切。
        if (chapters.size >= 2) return chapters
        val all = chapters.flatMap { it.paragraphs }
        return bySize(all)
    }

    internal fun bySize(paragraphs: List<String>): List<ParsedChapter> {
        if (paragraphs.isEmpty()) return emptyList()
        val chapters = mutableListOf<ParsedChapter>()
        var current = mutableListOf<String>()
        var size = 0
        paragraphs.forEach { p ->
            if (size > 0 && size + p.length > FALLBACK_CHARS) {
                chapters += ParsedChapter("第 ${chapters.size + 1} 部分", current)
                current = mutableListOf()
                size = 0
            }
            current += p
            size += p.length
        }
        if (current.isNotEmpty()) chapters += ParsedChapter("第 ${chapters.size + 1} 部分", current)
        return chapters
    }
}
