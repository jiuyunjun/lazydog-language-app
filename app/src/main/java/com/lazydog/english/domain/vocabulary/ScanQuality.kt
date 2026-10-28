package com.lazydog.english.domain.vocabulary

import kotlin.math.max
import kotlin.math.min

enum class ScanIssue { Unsteady, LowConfidence, NotEnglish, NoisyText, TooLong }

/** 扫描专用的保守质量门槛，不用词典猜原文，也不改变其他学习页面的输入。 */
object ScanQuality {
    const val MIN_CONFIDENCE = 0.65f
    const val MAX_SENTENCE_LENGTH = 600

    fun issue(target: ScanTarget, mode: ScanMode): ScanIssue? {
        val text = if (mode == ScanMode.Word) target.word else target.sentence
        textIssue(text, mode)?.let { return it }
        val confidence = if (mode == ScanMode.Word) target.wordConfidence else target.sentenceConfidence
        if (confidence != null && (!confidence.isFinite() || confidence < MIN_CONFIDENCE)) return ScanIssue.LowConfidence
        if (target.confirmedFrames < 2) return ScanIssue.Unsteady
        return null
    }

    fun textIssue(text: String, mode: ScanMode): ScanIssue? {
        if (text.isBlank() || text.any(::nonLatinLetter)) return ScanIssue.NotEnglish
        if (text.length > MAX_SENTENCE_LENGTH) return ScanIssue.TooLong
        if (mode == ScanMode.Word) {
            return if (ScanAim.englishWordIn(text) == text.trim()) null else ScanIssue.NoisyText
        }
        val tokens = text.trim().split(Regex("\\s+"))
        val words = tokens.count { ScanAim.englishWordIn(it) != null }
        if (words < 2) return ScanIssue.NotEnglish
        val mixed = tokens.count { t -> t.any { it in 'A'..'Z' || it in 'a'..'z' } && t.any { it.isDigit() } }
        val code = text.contains(Regex("[{}_=<>`]|://|\\\\|[A-Za-z]/[A-Za-z]"))
        if (code || mixed >= 2 || mixed.toFloat() / tokens.size >= 0.15f || words.toFloat() / tokens.size < 0.65f) {
            return ScanIssue.NoisyText
        }
        return null
    }

    /** 人工校对只解除置信度/帧数限制，文字本身仍须通过校验。 */
    fun corrected(target: ScanTarget, mode: ScanMode, input: String): ScanTarget? {
        val text = input.trim().replace(Regex("\\s+"), " ").replace('’', '\'')
        if (textIssue(text, mode) != null) return null
        return if (mode == ScanMode.Word) {
            target.copy(word = text, sentence = "", wordIndex = -1, wordConfidence = null, confirmedFrames = 2)
        } else {
            val tokens = TokenAlign.tokens(text)
            val index = tokens.indexOfFirst { ScanAim.englishWordIn(it) != null }
            target.copy(
                word = ScanAim.englishWordIn(tokens[index])!!, sentence = text, wordIndex = index,
                wordConfidence = null, sentenceConfidence = null, confirmedFrames = 2,
            )
        }
    }

    private fun nonLatinLetter(c: Char) = c.isLetter() && c !in 'A'..'Z' && c !in 'a'..'z'

    /** 先按版面和文字区域分段，再交给既有断词/句界。不能删除中文后把两侧英文接成一句。 */
    internal fun readingBlocks(blocks: List<ScannedBlock>): List<ScannedBlock> = buildList {
        for (block in blocks) {
            val lines = mutableListOf<ScannedLine>()
            fun flush() {
                if (lines.isNotEmpty()) add(ScannedBlock(lines.toList()))
                lines.clear()
            }
            var previous: TextBox? = null
            for (line in block.lines) {
                if (line.words.isEmpty()) { flush(); previous = null; continue }
                if (lines.size >= 6) flush()
                val bounds = bounds(line.words)
                previous?.let { old ->
                    val height = max(old.height, bounds.height).coerceAtLeast(1f)
                    val overlap = min(old.right, bounds.right) - max(old.left, bounds.left)
                    if (bounds.top - old.bottom > height * 1.5f || bounds.centerY <= old.centerY ||
                        overlap <= 0f || min(old.height, bounds.height) < height * 0.55f
                    ) flush()
                }
                // 混排的一行只保留连续英文片段，既不向上一行也不向下一行借上下文。
                val mixed = line.words.any { it.text.any(::nonLatinLetter) }
                var splitLine = mixed
                if (mixed) flush()
                val run = mutableListOf<ScannedWord>()
                fun flushRun() {
                    if (run.isNotEmpty()) lines.add(ScannedLine(run.toList()))
                    run.clear()
                }
                var last: TextBox? = null
                for (word in line.words) {
                    if (word.text.any(::nonLatinLetter)) {
                        flushRun(); flush(); last = null
                    } else {
                        last?.let { old ->
                            if (word.box.left - old.right > max(old.height, word.box.height) * 2.5f) {
                                flushRun(); flush()
                                splitLine = true
                            }
                        }
                        run.add(word)
                        last = word.box
                    }
                }
                flushRun()
                if (splitLine) flush()
                previous = if (splitLine) null else bounds
            }
            flush()
        }
    }

    private fun bounds(words: List<ScannedWord>) = TextBox(
        words.minOf { it.box.left }, words.minOf { it.box.top },
        words.maxOf { it.box.right }, words.maxOf { it.box.bottom },
    )
}
