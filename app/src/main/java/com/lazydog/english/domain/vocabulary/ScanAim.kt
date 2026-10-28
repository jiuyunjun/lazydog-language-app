package com.lazydog.english.domain.vocabulary

import kotlin.math.hypot
import kotlin.math.max

/** 一个英文词：字母开头，中间可以夹撇号或连字符（don't、well-known）。 */
private val ENGLISH_WORD = Regex("[A-Za-z]+(?:['\\-][A-Za-z]+)*")

/**
 * 扫一扫取词：取景画面里认出来的字，和"准星对着的是哪个词、哪句话"的判定。
 *
 * 坐标系由调用方决定，这里只要求文字框和准星在同一个坐标系里。
 * 扫一扫用的是预览视图的像素坐标，准星就是视图正中心。
 */
data class TextBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    val centerX get() = (left + right) / 2f
    val centerY get() = (top + bottom) / 2f

    fun offset(dx: Float, dy: Float) = TextBox(left + dx, top + dy, right + dx, bottom + dy)

    /** 点到框的距离；点在框里是 0。 */
    fun distanceTo(x: Float, y: Float): Float {
        val dx = max(max(left - x, 0f), x - right)
        val dy = max(max(top - y, 0f), y - bottom)
        return hypot(dx, dy)
    }
}

/** 识别器给出的一个词（空格隔开的一段，可能带着标点："world,"）。 */
data class ScannedWord(val text: String, val box: TextBox, val confidence: Float? = null)

data class ScannedLine(val words: List<ScannedWord>)

/** 一段：识别器认为连在一起的几行。句子只在段内拼，不跨段——两段之间多半是两栏或标题。 */
data class ScannedBlock(val lines: List<ScannedLine>)

enum class ScanMode { Word, Sentence }

data class ScanTarget(
    /** 准星下的英文词，去掉了两边的标点，大小写照原样（交给查词时再还原词条）。 */
    val word: String,
    /** 这个词所在的那句话，跨行时已经拼好、行尾断词连字符已经接回去。 */
    val sentence: String,
    /** 这个词的框；行尾断开的词有两个。 */
    val wordBoxes: List<TextBox>,
    /** 这句话里每个词的框，取句模式整句高亮用。 */
    val sentenceBoxes: List<TextBox>,
    /** [word] 是 [sentence] 按空格切开后的第几个词元，-1 表示不知道。 */
    val wordIndex: Int = -1,
    val wordConfidence: Float? = null,
    val sentenceConfidence: Float? = null,
    val confirmedFrames: Int = 1,
) {
    /**
     * 交给查词当语境的那句话。
     *
     * 对准的是标题、招牌、包装上的一两个词时，"句子"就是这个词自己，拿它当语境
     * 等于没给，存进生词本还会把例句写成光秃秃一个词。这种时候给空，让查词自己配例句。
     */
    val contextSentence: String
        get() = sentence.takeIf { s ->
            ENGLISH_WORD.findAll(s).count() >= MIN_CONTEXT_WORDS && ScanQuality.textIssue(s, ScanMode.Sentence) == null &&
                (sentenceConfidence == null || sentenceConfidence >= ScanQuality.MIN_CONFIDENCE)
        }.orEmpty()

    companion object {
        const val MIN_CONTEXT_WORDS = 3
    }
}

object ScanAim {
    /**
     * 准星 ([x], [y]) 对着的那个英文词。
     *
     * 准星落在词框里最好；落在两个词之间的空隙、行间距里也算最近那个词，
     * 但离得比 [tolerance] 还远就当没对准——手一晃准星扫过空白处，
     * 不该还把半屏外的某个词报出来。
     *
     * [prefer] 是上一帧锁定的词：这一帧里同一个词的距离先减掉 [stickiness] 再比。
     * 准星压在两个词交界处时，手一抖离谁更近每帧都在变；不给现任一点粘性，
     * 目标就在两个词之间来回跳。
     */
    fun aim(
        blocks: List<ScannedBlock>,
        x: Float,
        y: Float,
        tolerance: Float,
        prefer: ScanTarget? = null,
        stickiness: Float = 0f,
    ): ScanTarget? {
        var best: Pair<BlockText, Token>? = null
        var bestDistance = Float.MAX_VALUE
        for (block in ScanQuality.readingBlocks(blocks)) {
            val text = BlockText.of(block)
            for (token in text.tokens) {
                val word = token.word ?: continue
                val raw = token.boxes.minOf { it.distanceTo(x, y) }
                if (raw > tolerance) continue
                val distance = if (prefer != null && sameWord(word, token.boxes.first(), prefer)) raw - stickiness else raw
                if (distance < bestDistance) {
                    bestDistance = distance
                    best = text to token
                }
            }
        }
        val (text, token) = best ?: return null
        if (bestDistance > tolerance) return null
        val range = sentenceRange(text.joined, token.start)
        val sentence = text.joined.substring(range.first, range.last + 1).trim()
        val inSentence = text.tokens.filter { it.start <= range.last && it.end > range.first }
        return ScanTarget(
            word = token.word!!,
            sentence = sentence,
            wordBoxes = token.boxes,
            sentenceBoxes = inSentence.flatMap { it.boxes },
            wordIndex = inSentence.indexOf(token),
            wordConfidence = token.confidence,
            sentenceConfidence = inSentence.mapNotNull { it.confidence }.minOrNull(),
        )
    }

    /** 画面里所有的句子，切法和 [aim] 一样（段内跨行拼、不跨段）。 */
    fun sentencesIn(blocks: List<ScannedBlock>): List<String> = sentenceReadings(blocks).map { it.first }

    internal fun sentenceReadings(blocks: List<ScannedBlock>): List<Pair<String, Float?>> =
        ScanQuality.readingBlocks(blocks).flatMap { block ->
            val text = BlockText.of(block)
            val joined = text.joined
            buildList {
                var at = 0
                while (at < joined.length) {
                    val range = sentenceRange(joined, at)
                    val sentence = joined.substring(range.first, range.last + 1).trim()
                    val confidence = text.tokens.filter { it.start <= range.last && it.end > range.first }
                        .mapNotNull { it.confidence }.minOrNull()
                    if (sentence.isNotEmpty()) add(sentence to confidence)
                    at = range.last + 1
                }
            }
        }

    /**
     * 两帧里认到的是不是同一个词：字面相同（不分大小写），而且位置没跳出一个半行高。
     * 只比字面不行——一页书上 the 有几十个，准星从这个 the 挪到下一行那个 the 是换了目标。
     */
    fun sameWord(a: ScanTarget, b: ScanTarget): Boolean = sameWord(a.word, a.wordBoxes.first(), b)

    private fun sameWord(word: String, box: TextBox, other: ScanTarget): Boolean {
        if (!word.equals(other.word, ignoreCase = true)) return false
        val otherBox = other.wordBoxes.first()
        val reach = max(box.height, otherBox.height) * 1.5f
        return hypot(box.centerX - otherBox.centerX, box.centerY - otherBox.centerY) <= reach
    }

    /** 词元里真正的英文词："(world," → world，"don't" 保留撇号，纯数字和中文给 null。 */
    internal fun englishWordIn(raw: String): String? {
        val word = raw.replace('’', '\'').trim { !it.isLetterOrDigit() }
        // 不把 wor1d / abc123 截成 wor / abc；错误片段会被当成真实词条保存。
        return word.takeIf { ENGLISH_WORD.matches(it) }
    }

    /** 一个词元在拼好的整段文字里占 [start, end)。 */
    private data class Token(val raw: String, val boxes: List<TextBox>, val start: Int, val end: Int, val confidence: Float?) {
        val word: String? = englishWordIn(raw)
    }

    private class BlockText(val joined: String, val tokens: List<Token>) {
        companion object {
            fun of(block: ScannedBlock): BlockText {
                // 先把行尾 "beauti-" 和下一行的 "ful" 接成一个词：纸面上断行是排版的事，
                // 准星对着哪一半，要的都是 beautiful。
                val merged = mutableListOf<Triple<String, List<TextBox>, Float?>>()
                block.lines.forEachIndexed { lineIndex, line ->
                    line.words.forEachIndexed { wordIndex, word ->
                        val previous = merged.lastOrNull()
                        val continuesBrokenWord = wordIndex == 0 && lineIndex > 0 && previous != null &&
                            block.lines[lineIndex - 1].words.isNotEmpty() &&
                            previous.first.endsWith("-") && previous.first.length > 1 &&
                            word.text.firstOrNull()?.isLowerCase() == true
                        if (continuesBrokenWord) {
                            merged[merged.lastIndex] = Triple(
                                previous!!.first.dropLast(1) + word.text, previous.second + word.box,
                                listOfNotNull(previous.third, word.confidence).minOrNull(),
                            )
                        } else {
                            merged += Triple(word.text, listOf(word.box), word.confidence)
                        }
                    }
                }
                val builder = StringBuilder()
                val tokens = merged.map { (raw, boxes, confidence) ->
                    if (builder.isNotEmpty()) builder.append(' ')
                    val start = builder.length
                    builder.append(raw)
                    Token(raw, boxes, start, builder.length, confidence)
                }
                return BlockText(builder.toString(), tokens)
            }
        }
    }
}

/**
 * [index] 所在那句话在 [text] 里的范围（含句末标点，可能带首尾空白）。
 *
 * `. ! ?` 做句界；跳过小数、常见称谓和姓名首字母，句末连续标点与闭引号归前句。
 */
fun sentenceRange(text: String, index: Int): IntRange {
    if (text.isEmpty()) return IntRange.EMPTY
    val safeIndex = index.coerceIn(0, text.lastIndex)
    var start = 0
    var at = 0
    while (at < text.length) {
        if (text[at] !in ".!?" || (text[at] == '.' && isInternalPeriod(text, at))) {
            at++
            continue
        }
        var end = at + 1
        while (end < text.length && text[end] in ".!?\"'”’)]}") end++
        if (safeIndex < end) return start until end
        start = end
        at = end
    }
    return start until text.length
}

private val TITLE_ABBREVIATIONS = setOf("mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st")

private fun isInternalPeriod(text: String, at: Int): Boolean {
    val before = text.getOrNull(at - 1)
    val after = text.getOrNull(at + 1)
    if (before?.isDigit() == true && after?.isDigit() == true) return true
    if (before?.isLetter() == true && after?.isLetter() == true) return true
    val prefix = text.substring(0, at).takeLastWhile { it.isLetter() || it == '.' }
    val next = text.drop(at + 1).firstOrNull { !it.isWhitespace() } ?: return false
    if (prefix.lowercase() in TITLE_ABBREVIATIONS && next.isLetter()) return true
    if (prefix.length == 1 && prefix[0].isUpperCase() && next.isUpperCase()) return true
    // e.g. / i.e. 总是引出后文；U.S. 等在小写续文前不截断，在句末仍可结束。
    return prefix.lowercase() in setOf("e.g", "i.e") ||
        ('.' in prefix && next.isLowerCase())
}
