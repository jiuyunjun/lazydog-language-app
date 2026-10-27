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
     */
    fun aim(blocks: List<ScannedBlock>, x: Float, y: Float, tolerance: Float): ScanTarget? {
        var best: Pair<BlockText, Token>? = null
        var bestDistance = Float.MAX_VALUE
        for (block in blocks) {
            val text = BlockText.of(block)
            for (token in text.tokens) {
                if (token.word == null) continue
                val distance = token.boxes.minOf { it.distanceTo(x, y) }
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
        return ScanTarget(
            word = token.word!!,
            sentence = sentence,
            wordBoxes = token.boxes,
            sentenceBoxes = text.tokens
                .filter { it.start <= range.last && it.end > range.first }
                .flatMap { it.boxes },
        )
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

/**
 * 准星下的目标在帧与帧之间短暂丢失时先别撤。
 *
 * 实时识别每一帧都重新认一遍，手稍微一抖就有一两帧认不出字。
 * 不留这一下，底下那张结果卡和高亮框会一闪一闪，加号也跟着时亮时灭。
 */
class ScanAimHold(private val holdMillis: Long = 600) {
    private var last: ScanTarget? = null
    private var lastAt = 0L

    fun update(target: ScanTarget?, nowMillis: Long): ScanTarget? {
        if (target != null) {
            last = target
            lastAt = nowMillis
            return target
        }
        if (last != null && nowMillis - lastAt > holdMillis) last = null
        return last
    }

    fun clear() {
        last = null
    }
}
