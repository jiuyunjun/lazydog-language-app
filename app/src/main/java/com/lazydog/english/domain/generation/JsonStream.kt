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
}
