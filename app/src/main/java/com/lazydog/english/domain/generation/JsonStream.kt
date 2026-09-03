package com.lazydog.english.domain.generation

/**
 * 从"还没写完"的 JSON 里取出某个字符串字段已经生成的部分。
 *
 * 存在的理由：生成期间只显示"已生成 N 字"很枯燥，但结构化输出又不能等它闭合才解析。
 * 增量文本只用于展示，最终仍以完整 JSON 解析加校验为准（AI_CONTRACTS §2）。
 */
object JsonStream {

    fun partialString(raw: String, key: String): String {
        val keyAt = raw.indexOf("\"$key\"")
        if (keyAt < 0) return ""
        // 键名之后的第一个引号就是值的开引号（中间只有冒号和空白）。
        val valueStart = raw.indexOf('"', keyAt + key.length + 2)
        if (valueStart < 0) return ""

        val out = StringBuilder()
        var i = valueStart + 1
        while (i < raw.length) {
            val c = raw[i]
            if (c == '"') return out.toString()
            if (c == '\\') {
                // 转义序列还没传完，先返回已有的部分，下一段到了再重算。
                if (i + 1 >= raw.length) return out.toString()
                when (val escaped = raw[i + 1]) {
                    'n' -> out.append('\n')
                    't' -> out.append('\t')
                    'r' -> Unit
                    'u' -> {
                        if (i + 5 >= raw.length) return out.toString()
                        raw.substring(i + 2, i + 6).toIntOrNull(16)?.let { out.append(it.toChar()) }
                        i += 4
                    }
                    else -> out.append(escaped)
                }
                i += 1
            } else {
                out.append(c)
            }
            i += 1
        }
        return out.toString()
    }

    /** 按顺序找第一个已经开始生成的字段，用于"哪段先到就先显示哪段"。 */
    fun firstNonEmpty(raw: String, vararg keys: String): String =
        keys.firstNotNullOfOrNull { key -> partialString(raw, key).takeIf { it.isNotBlank() } }.orEmpty()

    /**
     * 同一个键在数组里出现的每一个值，按到达顺序。
     *
     * 一次生成十个词、十道题时，[partialString] 只给第一条，等待期间那一行就再也不动了。
     * 逐条列出来，用户能看着单词一个个冒出来——这既是进度，也已经是他要学的内容。
     */
    fun allStrings(raw: String, key: String, limit: Int = 20): List<String> {
        val out = mutableListOf<String>()
        var from = 0
        while (out.size < limit) {
            val keyAt = raw.indexOf("\"$key\"", from)
            if (keyAt < 0) break
            val value = partialString(raw.substring(keyAt), key)
            if (value.isNotBlank()) out.add(value)
            from = keyAt + key.length + 2
        }
        return out
    }
}

/**
 * 从"还没写完"的 JSON 里，按顺序取出某个数组字段里**已经闭合**的对象。
 *
 * 存在的理由：听力一次要生成十句，等整段 JSON 收完再解析，用户要干等十句的时间。
 * 逐条取出来就能第一句到了就开练，剩下的边听边补（英语听力训练模块DESIGN.md §18）。
 *
 * 只认对象数组，且要求对象在数组里逐个闭合——这正是模型生成 JSON 的顺序。
 * 它是**增量**的：每次 [feed] 只扫新到的那一段，重复喂同一段前缀不会重复吐同一个对象。
 */
class JsonArrayScanner(private val key: String) {

    private var cursor = 0
    private var arrayFound = false
    private var finished = false

    private var depth = 0
    private var objectStart = -1
    private var inString = false
    private var escaped = false

    /** 已经吐出去过几个对象。用于最终整体解析时跳过前面已消费的部分。 */
    var emitted: Int = 0
        private set

    /**
     * 喂入到目前为止收到的**全部**原始文本（[JsonStream] 的流式回调就是这么给的），
     * 返回这一次新闭合的对象文本，顺序与数组里一致。
     */
    fun feed(raw: String): List<String> {
        if (finished) return emptyList()
        if (!arrayFound && !locateArray(raw)) return emptyList()

        val out = mutableListOf<String>()
        while (cursor < raw.length) {
            val c = raw[cursor]
            when {
                inString -> when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                c == '"' -> inString = true
                c == '{' -> {
                    if (depth == 0) objectStart = cursor
                    depth += 1
                }
                c == '}' -> {
                    depth -= 1
                    if (depth == 0 && objectStart >= 0) {
                        out.add(raw.substring(objectStart, cursor + 1))
                        emitted += 1
                        objectStart = -1
                    }
                }
                // 数组闭合就收工：后面还有别的字段，再扫下去会把它们当成条目。
                c == ']' && depth == 0 -> {
                    finished = true
                    cursor += 1
                    return out
                }
            }
            cursor += 1
        }
        return out
    }

    /** 找到 `"key"` 后面那个 `[`，把游标停在它之后。找不到就等下一段。 */
    private fun locateArray(raw: String): Boolean {
        val keyAt = raw.indexOf("\"$key\"", cursor)
        if (keyAt < 0) return false
        val bracket = raw.indexOf('[', keyAt + key.length + 2)
        if (bracket < 0) return false
        cursor = bracket + 1
        arrayFound = true
        return true
    }
}
