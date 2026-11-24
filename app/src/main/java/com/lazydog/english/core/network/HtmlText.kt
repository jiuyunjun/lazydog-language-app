package com.lazydog.english.core.network

/**
 * HTML / XHTML 片段 → 纯文本段落。不引入 HTML 解析库（ARCHITECTURE §0：不加依赖），
 * 网页正文和 EPUB 章节的结构都够简单，按块级标签断行、去标签、解实体就够用。
 */
object HtmlText {

    /** 整段 HTML → 段落：块级标签处断行，其余标签去掉，空行不要。 */
    fun paragraphs(html: String): List<String> {
        val cleaned = NOISE.replace(html, " ")
        val broken = BLOCK.replace(cleaned, "\n")
        return TAG.replace(broken, "")
            .let(::decode)
            .split('\n')
            .map { it.replace(SPACES, " ").trim() }
            .filter { it.isNotEmpty() }
    }

    /** 一小段 HTML → 一行文字（标题、链接文字）。 */
    fun inline(html: String): String =
        decode(TAG.replace(BREAK.replace(html, " "), "")).replace(SPACES, " ").trim()

    fun decode(text: String): String = ENTITY.replace(text) { match ->
        val name = match.groupValues[1]
        when {
            name.startsWith("#x") || name.startsWith("#X") ->
                name.drop(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: match.value
            name.startsWith("#") -> name.drop(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: match.value
            else -> NAMED[name] ?: match.value
        }
    }

    private val NOISE = Regex(
        """<(head|script|style|noscript|svg|nav|iframe|button)\b[^>]*>.*?</\1>|<!--.*?-->""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )
    private val BLOCK = Regex(
        """</?(p|div|h[1-6]|li|ul|ol|blockquote|tr|table|section|article|pre|dd|dt|hr)\b[^>]*>|<br\s*/?>""",
        RegexOption.IGNORE_CASE,
    )
    private val BREAK = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
    private val TAG = Regex("<[^>]+>")
    private val SPACES = Regex("""[ \t 　]+""")
    private val ENTITY = Regex("""&(#?[xX]?[0-9a-zA-Z]+);""")
    private val NAMED = mapOf(
        "nbsp" to " ", "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "ldquo" to "“", "rdquo" to "”", "lsquo" to "‘", "rsquo" to "’", "mdash" to "—", "ndash" to "–",
        "hellip" to "…", "middot" to "·", "times" to "×", "copy" to "©", "eacute" to "é", "egrave" to "è",
        "aacute" to "á", "agrave" to "à", "ouml" to "ö", "uuml" to "ü", "auml" to "ä", "ccedil" to "ç",
    )
}
