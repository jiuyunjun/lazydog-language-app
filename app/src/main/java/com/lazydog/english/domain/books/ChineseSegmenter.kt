package com.lazydog.english.domain.books

import kotlin.math.ln

/** 分词结果里的一个词：在原文里的起止、词本身、词典词性（jieba 的标签）。单字未登录词的词性为空。 */
data class ZhToken(val start: Int, val end: Int, val word: String, val pos: String)

/**
 * 中文分词（`读书与网络阅读DESIGN.md` §5.2）。照 jieba 的做法：每个位置看词典里能接哪些词，
 * 动态规划从后往前找「词频乘积最大」的切法。词典外的字按单字切，词频记 1。
 *
 * 只切汉字连成的那一段；标点、英文、数字原样跳过——它们不参与替换。
 * 不做 jieba 的隐马尔可夫未登录词识别：未登录词切成单字，单字本来就不替换。
 */
class ChineseSegmenter(private val dictionary: Map<String, Entry>) {

    data class Entry(val freq: Int, val pos: String)

    private val logTotal = ln(dictionary.values.sumOf { it.freq.toLong() }.coerceAtLeast(1).toDouble())
    private val maxLength = (dictionary.keys.maxOfOrNull { it.length } ?: 1).coerceAtMost(MAX_WORD_LENGTH)

    fun segment(text: String): List<ZhToken> {
        val tokens = ArrayList<ZhToken>()
        var i = 0
        while (i < text.length) {
            if (!isHan(text[i])) {
                i++
                continue
            }
            var end = i
            while (end < text.length && isHan(text[end])) end++
            segmentRun(text, i, end, tokens)
            i = end
        }
        return tokens
    }

    private fun segmentRun(text: String, from: Int, to: Int, out: MutableList<ZhToken>) {
        val n = to - from
        val best = DoubleArray(n + 1)
        val next = IntArray(n + 1)
        for (i in n - 1 downTo 0) {
            var score = Double.NEGATIVE_INFINITY
            var choice = i + 1
            for (length in 1..minOf(maxLength, n - i)) {
                val word = text.substring(from + i, from + i + length)
                val freq = dictionary[word]?.freq ?: if (length == 1) 1 else continue
                val candidate = ln(freq.toDouble()) - logTotal + best[i + length]
                if (candidate > score) {
                    score = candidate
                    choice = i + length
                }
            }
            best[i] = score
            next[i] = choice
        }
        var i = 0
        while (i < n) {
            val j = next[i]
            val word = text.substring(from + i, from + j)
            out += ZhToken(from + i, from + j, word, dictionary[word]?.pos.orEmpty())
            i = j
        }
    }

    companion object {
        /** 词典里最长的词有十几个字（成语、专名），切词时最多看这么长，够用也快。 */
        const val MAX_WORD_LENGTH = 8

        fun isHan(c: Char): Boolean = c in '一'..'鿿' || c == '〇'

        /** 资源文件「词 词频 词性」，`#` 开头是注释。 */
        fun parse(lines: Sequence<String>): ChineseSegmenter = ChineseSegmenter(
            lines.filter { it.isNotBlank() && !it.startsWith("#") }
                .mapNotNull { line ->
                    val parts = line.trim().split(' ')
                    val freq = parts.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
                    parts[0] to Entry(freq, parts.getOrElse(2) { "" })
                }
                .toMap(HashMap()),
        )
    }
}
