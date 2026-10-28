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
data class ScannedWord(val text: String, val box: TextBox)

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
) {
    /**
     * 交给查词当语境的那句话。
     *
     * 对准的是标题、招牌、包装上的一两个词时，"句子"就是这个词自己，拿它当语境
     * 等于没给，存进生词本还会把例句写成光秃秃一个词。这种时候给空，让查词自己配例句。
     */
    val contextSentence: String
        get() = sentence.takeIf { s ->
            ENGLISH_WORD.findAll(s).count() >= MIN_CONTEXT_WORDS
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
        for (block in blocks) {
            val text = BlockText.of(block)
            for (token in text.tokens) {
                val word = token.word ?: continue
                val raw = token.boxes.minOf { it.distanceTo(x, y) }
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
        )
    }

    /** 画面里所有的句子，切法和 [aim] 一样（段内跨行拼、不跨段）。 */
    fun sentencesIn(blocks: List<ScannedBlock>): List<String> = blocks.flatMap { block ->
        val joined = BlockText.of(block).joined
        buildList {
            var at = 0
            while (at < joined.length) {
                val range = sentenceRange(joined, at)
                joined.substring(range.first, range.last + 1).trim().takeIf { it.isNotEmpty() }?.let(::add)
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
    internal fun englishWordIn(raw: String): String? =
        ENGLISH_WORD.find(raw.replace('’', '\''))?.value

    /** 一个词元在拼好的整段文字里占 [start, end)。 */
    private data class Token(val raw: String, val boxes: List<TextBox>, val start: Int, val end: Int) {
        val word: String? = englishWordIn(raw)
    }

    private class BlockText(val joined: String, val tokens: List<Token>) {
        companion object {
            fun of(block: ScannedBlock): BlockText {
                // 先把行尾 "beauti-" 和下一行的 "ful" 接成一个词：纸面上断行是排版的事，
                // 准星对着哪一半，要的都是 beautiful。
                val merged = mutableListOf<Pair<String, List<TextBox>>>()
                block.lines.forEachIndexed { lineIndex, line ->
                    line.words.forEachIndexed { wordIndex, word ->
                        val previous = merged.lastOrNull()
                        val continuesBrokenWord = wordIndex == 0 && lineIndex > 0 && previous != null &&
                            block.lines[lineIndex - 1].words.isNotEmpty() &&
                            previous.first.endsWith("-") && previous.first.length > 1 &&
                            word.text.firstOrNull()?.isLowerCase() == true
                        if (continuesBrokenWord) {
                            merged[merged.lastIndex] =
                                previous!!.first.dropLast(1) + word.text to previous.second + word.box
                        } else {
                            merged += word.text to listOf(word.box)
                        }
                    }
                }
                val builder = StringBuilder()
                val tokens = merged.map { (raw, boxes) ->
                    if (builder.isNotEmpty()) builder.append(' ')
                    val start = builder.length
                    builder.append(raw)
                    Token(raw, boxes, start, builder.length)
                }
                return BlockText(builder.toString(), tokens)
            }
        }
    }
}

/**
 * [index] 所在那句话在 [text] 里的范围（含句末标点，可能带首尾空白）。
 *
 * 只认 `. ! ?` 做句界，和双击查词、三击讲句用的是同一套口径。
 */
fun sentenceRange(text: String, index: Int): IntRange {
    if (text.isEmpty()) return IntRange.EMPTY
    val safeIndex = index.coerceIn(0, text.lastIndex)
    val ends = charArrayOf('.', '!', '?')
    var start = 0
    for (i in (safeIndex - 1) downTo 0) {
        if (text[i] in ends) {
            start = i + 1
            break
        }
    }
    var end = text.length
    for (i in safeIndex until text.length) {
        if (text[i] in ends) {
            end = i + 1
            break
        }
    }
    return start until end
}
