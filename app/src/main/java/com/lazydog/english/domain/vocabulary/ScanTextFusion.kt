package com.lazydog.english.domain.vocabulary

import kotlin.math.max
import kotlin.math.min

/**
 * 两串 OCR 词元的对齐：同一句话在两帧（或一帧和一张照片）里被认得略有出入时，找出谁对应谁。
 *
 * 词元级的编辑距离，替换代价按两个词的字面相似度给：wor1d 对 world 只算 0.2，
 * 比"删一个再插一个"便宜得多，所以认错一个字母的词仍然对得上原位。
 */
internal object TokenAlign {
    /** 比对用的形式：小写、去掉两边标点。"World," 和 "world" 是同一个词。 */
    fun key(token: String): String =
        token.replace('’', '\'').lowercase().trim { !it.isLetterOrDigit() }

    fun charSimilarity(a: String, b: String): Float {
        if (a == b) return 1f
        val longest = max(a.length, b.length)
        if (longest == 0) return 1f
        return 1f - levenshtein(a, b).toFloat() / longest
    }

    /** [aToB]：a 的第 i 个词对上 b 的第几个，-1 表示 b 里没有它。 */
    class Result(val cost: Float, val aToB: IntArray)

    fun align(a: List<String>, b: List<String>): Result {
        val ka = a.map(::key)
        val kb = b.map(::key)
        val sub = Array(a.size) { i -> FloatArray(b.size) { j -> 1f - charSimilarity(ka[i], kb[j]) } }
        val d = Array(a.size + 1) { FloatArray(b.size + 1) }
        for (i in 1..a.size) d[i][0] = i.toFloat()
        for (j in 1..b.size) d[0][j] = j.toFloat()
        for (i in 1..a.size) {
            for (j in 1..b.size) {
                d[i][j] = min(d[i - 1][j - 1] + sub[i - 1][j - 1], min(d[i - 1][j], d[i][j - 1]) + 1f)
            }
        }
        val aToB = IntArray(a.size) { -1 }
        var i = a.size
        var j = b.size
        while (i > 0 && j > 0) {
            when (d[i][j]) {
                d[i - 1][j - 1] + sub[i - 1][j - 1] -> {
                    aToB[i - 1] = j - 1
                    i--
                    j--
                }
                d[i - 1][j] + 1f -> i--
                else -> j--
            }
        }
        return Result(d[a.size][b.size], aToB)
    }

    /** 0..1，1 是一模一样。 */
    fun similarity(a: List<String>, b: List<String>): Float {
        val longest = max(a.size, b.size)
        if (longest == 0) return 1f
        return 1f - align(a, b).cost / longest
    }

    fun tokens(sentence: String): List<String> = sentence.split(' ').filter { it.isNotEmpty() }

    private fun levenshtein(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        var row = IntArray(b.length + 1)
        for (i in 1..a.length) {
            row[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                row[j] = min(min(row[j - 1], previous[j]) + 1, previous[j - 1] + cost)
            }
            val swap = previous
            previous = row
            row = swap
        }
        return previous[b.length]
    }
}

/**
 * 多帧投票拼句子。
 *
 * 实时识别每帧独立认字，长句里总有一两个词这帧认对、下帧认错。只取最新一帧，句子就一直在变；
 * 要求"连续两帧完全一致"才换，句子越长越难满足，结果是卡在旧的错误上。
 *
 * 这里把同一句话最近 [window] 帧的结果存下来，每个词的位置单独投票：
 * 1. 先选出和其余各帧总差异最小的一帧当底稿（中位帧），别的帧都按词对齐到它上面；
 * 2. 每个位置上，至少有两帧认成同一个样子才有资格当选，越新的帧票越重（每老一帧乘 [decay]）——
 *    偶发的一帧认错选不上，真换了内容两三帧就跟过去；
 * 3. 没有任何写法够两票时，用底稿的。
 *
 * 和上一帧比差得太多（相似度低于 [sameSentence]），说明准星挪到了另一句，清空重来。
 */
class SentenceFusion(
    private val window: Int = 8,
    private val decay: Float = 0.8f,
    private val sameSentence: Float = 0.5f,
) {
    data class Fused(
        val sentence: String,
        /** 准星那个位置上投票胜出的英文词；那个位置被投掉了就是 null。 */
        val word: String?,
        /** [word] 在 [sentence] 按空格切开后的下标，-1 表示没有。 */
        val wordIndex: Int,
    )

    private class Frame(val tokens: List<String>, val wordIndex: Int)

    private val frames = ArrayDeque<Frame>()

    fun add(sentence: String, wordIndex: Int): Fused {
        val tokens = TokenAlign.tokens(sentence)
        val last = frames.lastOrNull()
        if (last != null && TokenAlign.similarity(last.tokens, tokens) < sameSentence) frames.clear()
        frames.addLast(Frame(tokens, wordIndex))
        while (frames.size > window) frames.removeFirst()
        return fuse()
    }

    fun reset() = frames.clear()

    private fun fuse(): Fused {
        val latest = frames.last()
        if (frames.size == 1) return fused(latest.tokens, latest.tokens.indices.toList(), latest.wordIndex)

        // 中位帧：到其余各帧的对齐代价之和最小；一样小时取更早的，已经显示着的不轻易换。
        val alignments = HashMap<Pair<Int, Int>, TokenAlign.Result>()
        fun alignment(a: Int, b: Int) = alignments.getOrPut(a to b) { TokenAlign.align(frames[a].tokens, frames[b].tokens) }
        val reference = frames.indices.minBy { a -> frames.indices.sumOf { b -> if (a == b) 0.0 else alignment(a, b).cost.toDouble() } }
        val base = frames[reference].tokens

        // 每个位置上：写法 → (加权票数, 支持的帧数)。空串代表"这帧这里没有词"。
        val ballots = List(base.size) { HashMap<String, Pair<Float, Int>>() }
        frames.forEachIndexed { index, frame ->
            val weight = pow(decay, frames.lastIndex - index)
            val map = if (index == reference) null else alignment(reference, index).aToB
            for (slot in base.indices) {
                val reading = if (map == null) base[slot] else map[slot].let { if (it < 0) "" else frame.tokens[it] }
                val (votes, count) = ballots[slot][reading] ?: (0f to 0)
                ballots[slot][reading] = votes + weight to count + 1
            }
        }
        val winners = base.indices.map { slot ->
            ballots[slot].filterValues { it.second >= 2 }.maxByOrNull { it.value.first }?.key ?: base[slot]
        }

        // 最新一帧的准星词对到底稿的哪个位置。
        val latestSlot = if (reference == frames.lastIndex) {
            latest.wordIndex
        } else {
            alignment(reference, frames.lastIndex).aToB.indexOfFirst { it == latest.wordIndex && it >= 0 }
        }
        val kept = winners.indices.filter { winners[it].isNotEmpty() }
        return fused(winners, kept, latestSlot)
    }

    private fun fused(tokens: List<String>, kept: List<Int>, slot: Int): Fused {
        val outIndex = kept.indexOf(slot)
        return Fused(
            sentence = kept.joinToString(" ") { tokens[it] },
            word = if (outIndex >= 0) ScanAim.englishWordIn(tokens[slot]) else null,
            wordIndex = outIndex,
        )
    }

    private fun pow(base: Float, exponent: Int): Float {
        var result = 1f
        repeat(exponent) { result *= base }
        return result
    }
}

/**
 * 按加号那一刻拍的高清照片，用来纠正实时帧认错的字。
 *
 * 实时识别为了流畅只看 720p 的预览帧，书页上的小字每个字母才几个像素；拍照是全分辨率，
 * 同一行字清楚得多（谷歌翻译"扫描"模式也是先定格高清图再认字）。
 * 照片和预览的取景、比例不完全一样，这里不换算坐标，直接按**文字**在照片里找回同一句话：
 * 和实时那句最像的一句，够像（[minSimilarity]）就用照片里的写法，不够像就当没拍到、沿用实时结果。
 */
object ScanRefine {
    fun refine(live: ScanTarget, still: List<ScannedBlock>, minSimilarity: Float = 0.5f): ScanTarget? {
        val liveTokens = TokenAlign.tokens(live.sentence)
        if (liveTokens.isEmpty()) return null
        val (sentence, similarity) = ScanAim.sentencesIn(still)
            .map { it to TokenAlign.similarity(liveTokens, TokenAlign.tokens(it)) }
            .maxByOrNull { it.second } ?: return null
        if (similarity < minSimilarity) return null

        val stillTokens = TokenAlign.tokens(sentence)
        val mapped = live.wordIndex.takeIf { it in liveTokens.indices }
            ?.let { TokenAlign.align(liveTokens, stillTokens).aToB[it] }
            ?.takeIf { it >= 0 }
        // 照片里那个位置的词和实时认的差太多，多半是对齐错了位，词还是用实时的。
        val word = mapped?.let { ScanAim.englishWordIn(stillTokens[it]) }
            ?.takeIf { TokenAlign.charSimilarity(TokenAlign.key(it), TokenAlign.key(live.word)) >= 0.5f }
        return live.copy(
            word = word ?: live.word,
            sentence = sentence,
            wordIndex = if (word != null) mapped!! else -1,
        )
    }
}
