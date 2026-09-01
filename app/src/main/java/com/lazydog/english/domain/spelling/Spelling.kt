package com.lazydog.english.domain.spelling

import com.lazydog.english.core.model.ReviewGrade
import java.time.Instant
import java.time.ZoneId
import kotlin.math.max

enum class SpellingStage(val labelZh: String) {
    Seen("接触"),
    Recognition("识别"),
    PartialRecall("局部回忆"),
    ChunkRecall("分块回忆"),
    GuidedRecall("提示拼写"),
    FreeRecall("完整拼写"),
    Retained("长期保持"),
}

enum class SpellingQuestionType {
    Recognition,
    PartialCompletion,
    ChunkRecall,
    GuidedRecall,
    FreeRecall,
    DelayedFreeRecall,
}

/** 设计稿「错误分类与薄弱画像」列的八类。顺序与画像页展示顺序一致。 */
enum class SpellingErrorType(val labelZh: String) {
    Omission("漏字"),
    Insertion("多字"),
    Substitution("替换"),
    Transposition("顺序颠倒"),
    Doubling("双写错误"),
    VowelOrder("元音顺序"),
    Phonetic("拼音式拼写"),
    Morphology("词形变化"),
}

data class WeakSegment(
    val segment: String,
    val start: Int,
    val endExclusive: Int,
    val errorCount: Int,
)

data class SpellingProgress(
    val stage: SpellingStage = SpellingStage.Seen,
    val recognitionScore: Double = 0.0,
    val partialRecallScore: Double = 0.0,
    val chunkRecallScore: Double = 0.0,
    val phonemeGraphemeScore: Double = 0.0,
    val freeRecallScore: Double = 0.0,
    val retentionScore: Double = 0.0,
    val successStreak: Int = 0,
    val failureStreak: Int = 0,
    val stageSuccessCount: Int = 0,
    val freeRecallSuccessCount: Int = 0,
    val successfulRecallDates: Set<String> = emptySet(),
    val longestSuccessfulIntervalDays: Int = 0,
    val currentIntervalDays: Int = 1,
    val weakSegments: List<WeakSegment> = emptyList(),
    val lastAttemptAt: Instant? = null,
)

data class SpellingEvaluation(
    val correct: Boolean,
    val normalizedAnswer: String,
    val masteryCredit: Double,
    val errorTypes: Set<SpellingErrorType>,
    val weakSegment: WeakSegment?,
    val nextProgress: SpellingProgress,
    val reviewGrade: ReviewGrade,
    /**
     * 这次错更像手滑而不是没记住：编辑距离 1 且答得极快。
     * 设计稿「阶段升降级规则」最后一行要求这种情况不降级——
     * 打错一个键和想不起来这个词，不是同一件事。
     */
    val likelyTypo: Boolean = false,
)

object SpellingEngine {
    fun questionType(progress: SpellingProgress): SpellingQuestionType = when (progress.stage) {
        SpellingStage.Seen, SpellingStage.Recognition -> SpellingQuestionType.Recognition
        SpellingStage.PartialRecall -> SpellingQuestionType.PartialCompletion
        SpellingStage.ChunkRecall -> SpellingQuestionType.ChunkRecall
        SpellingStage.GuidedRecall -> SpellingQuestionType.GuidedRecall
        SpellingStage.FreeRecall -> SpellingQuestionType.FreeRecall
        SpellingStage.Retained -> SpellingQuestionType.DelayedFreeRecall
    }

    fun masteryCredit(correct: Boolean, hintLevel: Int): Double {
        if (!correct) return 0.0
        return when (hintLevel.coerceIn(0, 5)) {
            0 -> 1.0
            1 -> 0.8
            2 -> 0.6
            3 -> 0.4
            4 -> 0.2
            else -> 0.0
        }
    }

    fun evaluate(
        progress: SpellingProgress,
        expected: String,
        answer: String,
        questionType: SpellingQuestionType,
        hintLevel: Int,
        attemptedAt: Instant,
        /** 本题作答耗时；只用来把「手滑」和「没记住」分开，0 表示没测。 */
        responseTimeMillis: Long = 0,
        /** 题面是靠声音给的（放音 / 音标），命中音形对应这一维。 */
        audioPrompted: Boolean = false,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): SpellingEvaluation {
        val expectedNormalized = normalize(expected)
        val answerNormalized = normalize(answer)
        val correct = expectedNormalized == answerNormalized
        val credit = masteryCredit(correct, hintLevel)
        val intervalDays = progress.lastAttemptAt?.let {
            max(0L, java.time.Duration.between(it, attemptedAt).toDays()).toInt()
        } ?: 0
        val errors = if (correct) emptySet() else classifyErrors(expectedNormalized, answerNormalized)
        val weak = if (correct) null else findWeakSegment(expectedNormalized, answerNormalized)
        val updatedWeak = if (weak == null) progress.weakSegments else mergeWeakSegment(progress.weakSegments, weak)
        val likelyTypo = !correct && isLikelyTypo(expectedNormalized, answerNormalized, responseTimeMillis)
        val nextSuccessStreak = if (correct) progress.successStreak + 1 else 0
        // 手滑不算一次「忘了」：连续错误计数是降级的依据，打错一个键不该往里记。
        val nextFailureStreak = when {
            correct -> 0
            likelyTypo -> progress.failureStreak
            else -> progress.failureStreak + 1
        }
        val nextStageSuccess = if (correct) progress.stageSuccessCount + 1 else 0
        val isFree = questionType == SpellingQuestionType.FreeRecall ||
            questionType == SpellingQuestionType.DelayedFreeRecall
        val successfulDates = if (correct && isFree) {
            progress.successfulRecallDates + attemptedAt.atZone(zoneId).toLocalDate().toString()
        } else {
            progress.successfulRecallDates
        }
        val freeSuccesses = progress.freeRecallSuccessCount + if (correct && isFree) 1 else 0
        val longestInterval = if (correct && isFree) {
            max(progress.longestSuccessfulIntervalDays, intervalDays)
        } else {
            progress.longestSuccessfulIntervalDays
        }

        val provisional = progress.copy(
            recognitionScore = updateScore(progress.recognitionScore, questionType == SpellingQuestionType.Recognition, credit),
            partialRecallScore = updateScore(progress.partialRecallScore, questionType == SpellingQuestionType.PartialCompletion, credit),
            chunkRecallScore = updateScore(progress.chunkRecallScore, questionType == SpellingQuestionType.ChunkRecall, credit),
            freeRecallScore = updateScore(
                progress.freeRecallScore,
                questionType == SpellingQuestionType.GuidedRecall || isFree,
                credit,
            ),
            phonemeGraphemeScore = updateScore(progress.phonemeGraphemeScore, audioPrompted, credit),
            retentionScore = updateScore(progress.retentionScore, questionType == SpellingQuestionType.DelayedFreeRecall, credit),
            successStreak = nextSuccessStreak,
            failureStreak = nextFailureStreak,
            stageSuccessCount = nextStageSuccess,
            freeRecallSuccessCount = freeSuccesses,
            successfulRecallDates = successfulDates,
            longestSuccessfulIntervalDays = longestInterval,
            currentIntervalDays = nextInterval(progress.currentIntervalDays, correct, credit),
            weakSegments = updatedWeak,
            lastAttemptAt = attemptedAt,
        )
        val nextStage = deriveNextStage(provisional, correct, hintLevel, likelyTypo)
        val next = provisional.copy(
            stage = nextStage,
            stageSuccessCount = if (nextStage != progress.stage) 0 else provisional.stageSuccessCount,
            failureStreak = if (nextStage != progress.stage) 0 else provisional.failureStreak,
        )
        return SpellingEvaluation(
            correct = correct,
            normalizedAnswer = answerNormalized,
            masteryCredit = credit,
            errorTypes = errors,
            weakSegment = weak,
            nextProgress = next,
            reviewGrade = reviewGrade(questionType, correct, credit, intervalDays),
            likelyTypo = likelyTypo,
        )
    }

    fun recognitionOptions(word: String): List<String> {
        val clean = normalize(word)
        if (clean.length < 3) return listOf(clean)
        val middle = clean.length / 2
        val candidates = linkedSetOf(clean)
        candidates += clean.removeRange(middle, middle + 1)
        if (middle > 0) {
            val chars = clean.toCharArray()
            val swapAt = if (chars[middle] == chars[middle - 1] && middle + 1 < chars.size) middle else middle - 1
            if (swapAt >= 0 && swapAt + 1 < chars.size) {
                val temp = chars[swapAt]
                chars[swapAt] = chars[swapAt + 1]
                chars[swapAt + 1] = temp
                candidates += chars.concatToString()
            }
        }
        val replacement = when (clean[middle]) {
            'a' -> 'e'
            'e' -> 'a'
            'i' -> 'e'
            'o' -> 'a'
            'u' -> 'o'
            else -> if (clean[middle] == 'n') 'm' else 'n'
        }
        candidates += clean.replaceRange(middle, middle + 1, replacement.toString())
        var insertAt = middle
        while (candidates.size < 4 && insertAt < clean.length) {
            candidates += clean.substring(0, insertAt) + clean[insertAt] + clean.substring(insertAt)
            insertAt++
        }
        return candidates.take(4).sortedBy { stableOptionOrder(it, clean) }
    }

    fun maskedWord(word: String, weakSegments: List<WeakSegment>, chunk: Boolean): String {
        val clean = normalize(word)
        if (clean.isEmpty()) return ""
        val preferred = weakSegments.maxByOrNull { it.errorCount }?.takeIf { it.start in clean.indices }
        val start: Int
        val end: Int
        if (preferred != null) {
            start = preferred.start.coerceIn(0, clean.lastIndex)
            end = preferred.endExclusive.coerceIn(start + 1, clean.length)
        } else {
            val width = if (chunk) (clean.length / 3).coerceAtLeast(2) else (clean.length / 4).coerceAtLeast(1)
            start = ((clean.length - width) / 2).coerceAtLeast(0)
            end = (start + width).coerceAtMost(clean.length)
        }
        val blank = if (chunk) "_".repeat(end - start) else clean.substring(start, end).map { '_' }.joinToString("")
        return clean.replaceRange(start, end, blank)
    }

    /**
     * 局部补全和词块回忆只让用户打缺的那一段，判定前要拼回完整单词。
     * 直接拿片段和整词比对永远都是错的。
     */
    fun fillMasked(word: String, typed: String, weakSegments: List<WeakSegment>, chunk: Boolean): String {
        val masked = maskedWord(word, weakSegments, chunk)
        val start = masked.indexOf('_')
        if (start < 0) return normalize(typed)
        val end = masked.lastIndexOf('_') + 1
        return normalize(masked.take(start) + typed.trim() + masked.drop(end))
    }

    fun hintText(expected: String, answer: String, level: Int, weakSegments: List<WeakSegment>): String {
        val clean = normalize(expected)
        val submitted = normalize(answer)
        if (submitted.isBlank()) {
            return when (level.coerceIn(0, 5)) {
                0 -> "先试着写一次；需要时再要提示。"
                1 -> "首字母是 ${clean.take(1)}，一共 ${clean.length} 个字母。"
                2 -> "可以分成 ${chunkWord(clean).size} 个词块。"
                3 -> "开头是 ${clean.take(2)}，结尾是 ${clean.takeLast(2)}。"
                4 -> "分块看：${chunkWord(clean).joinToString(" + ")}"
                else -> "答案：$clean"
            }
        }
        return when (level.coerceIn(0, 5)) {
            0 -> "拼写不正确，再试一次。"
            1 -> {
                val delta = clean.length - submitted.length
                when {
                    delta > 0 -> "少了 $delta 个字母。"
                    delta < 0 -> "多了 ${-delta} 个字母。"
                    else -> "字母数量对了，但有位置不对。"
                }
            }
            2 -> {
                val weak = findWeakSegment(clean, submitted)
                if (weak == null) "有一小段顺序不对。" else "错误大约在第 ${weak.start + 1}～${weak.endExclusive} 个字母附近。"
            }
            3 -> {
                val weak = findWeakSegment(clean, submitted) ?: weakSegments.maxByOrNull { it.errorCount }
                if (weak == null) "开头是 ${clean.take(2)}，结尾是 ${clean.takeLast(2)}。" else "这一段是：…${weak.segment}…"
            }
            4 -> "分块看：${chunkWord(clean).joinToString(" + ")}"
            else -> "答案：$clean"
        }
    }

    /**
     * 拆成前缀 / 词干 / 后缀三段，拆不出来才退回等长切分。
     * 设计稿 62 屏画的是 en + viron + ment：中间那块才是要练的，
     * 所以前后缀都剥掉之后剩下的词干必须自成一块，不能被并进旁边。
     */
    fun chunkWord(word: String): List<String> {
        val clean = normalize(word)
        if (clean.length <= 5) return listOf(clean)
        val suffix = COMMON_SUFFIXES.firstOrNull { clean.length - it.length >= 3 && clean.endsWith(it) }
        val withoutSuffix = if (suffix == null) clean else clean.dropLast(suffix.length)
        val prefix = COMMON_PREFIXES.firstOrNull { withoutSuffix.length - it.length >= 3 && withoutSuffix.startsWith(it) }
        val stem = if (prefix == null) withoutSuffix else withoutSuffix.drop(prefix.length)
        val parts = listOfNotNull(prefix, stem.ifBlank { null }, suffix)
        if (parts.size >= 2) return parts
        val size = (clean.length / 3.0).toInt().coerceAtLeast(2)
        return clean.chunked(size)
    }

    private val COMMON_SUFFIXES = listOf(
        "tion", "sion", "ment", "ness", "able", "ible", "ance", "ence", "ful", "ing", "ed", "ly",
    )

    private val COMMON_PREFIXES = listOf(
        "inter", "trans", "under", "over", "dis", "mis", "pre", "pro", "sub", "com", "con", "ex",
        "en", "in", "im", "re", "un", "de",
    )

    fun classifyErrors(expected: String, answer: String): Set<SpellingErrorType> {
        val e = normalize(expected)
        val a = normalize(answer)
        if (e == a) return emptySet()
        val result = linkedSetOf<SpellingErrorType>()
        if (isAdjacentTransposition(e, a)) {
            result += SpellingErrorType.Transposition
            val mismatch = e.indices.filter { e[it] != a[it] }
            if (mismatch.all { e[it] in VOWELS && a[it] in VOWELS }) result += SpellingErrorType.VowelOrder
        } else when {
            e.length > a.length -> result += SpellingErrorType.Omission
            e.length < a.length -> result += SpellingErrorType.Insertion
            else -> result += SpellingErrorType.Substitution
        }
        if (hasDoublingError(e, a)) result += SpellingErrorType.Doubling
        if (isPhoneticSpelling(e, a)) result += SpellingErrorType.Phonetic
        if (hasMorphologyError(e, a)) result += SpellingErrorType.Morphology
        return result
    }

    fun findWeakSegment(expected: String, answer: String): WeakSegment? {
        val e = normalize(expected)
        val a = normalize(answer)
        if (e.isEmpty() || e == a) return null
        var prefix = 0
        while (prefix < minOf(e.length, a.length) && e[prefix] == a[prefix]) prefix++
        var suffix = 0
        while (
            suffix < minOf(e.length - prefix, a.length - prefix) &&
            e[e.lastIndex - suffix] == a[a.lastIndex - suffix]
        ) suffix++
        if (e.length != a.length) {
            // 单字增删会让后续字符全部错位；围绕真正的增删点保留一个可训练词块，
            // 而不是把整段错位都标成薄弱区域。
            val start = (prefix - 4).coerceAtLeast(0)
            val end = (prefix + 1).coerceIn(start + 1, e.length)
            return WeakSegment(e.substring(start, end), start, end, 1)
        }
        val start = (prefix - 1).coerceAtLeast(0)
        val end = (e.length - suffix + 1).coerceIn(start + 1, e.length)
        return WeakSegment(e.substring(start, end), start, end, 1)
    }

    private fun deriveNextStage(
        progress: SpellingProgress,
        correct: Boolean,
        hintLevel: Int,
        likelyTypo: Boolean,
    ): SpellingStage {
        // 一次手滑不动阶段：设计稿明确「单次手滑（编辑距离 1 且用时极短）不降级」。
        if (!correct && likelyTypo) return progress.stage
        if (!correct) return when {
            progress.stage == SpellingStage.Retained -> SpellingStage.FreeRecall
            progress.stage == SpellingStage.FreeRecall && progress.failureStreak >= 2 -> SpellingStage.GuidedRecall
            progress.stage == SpellingStage.GuidedRecall && progress.failureStreak >= 2 -> SpellingStage.PartialRecall
            else -> progress.stage
        }
        return when (progress.stage) {
            SpellingStage.Seen -> SpellingStage.Recognition
            SpellingStage.Recognition -> if (progress.stageSuccessCount >= 2) SpellingStage.PartialRecall else progress.stage
            SpellingStage.PartialRecall -> if (progress.stageSuccessCount >= 2) SpellingStage.ChunkRecall else progress.stage
            SpellingStage.ChunkRecall -> if (progress.stageSuccessCount >= 2) SpellingStage.GuidedRecall else progress.stage
            SpellingStage.GuidedRecall -> if (progress.stageSuccessCount >= 2 && hintLevel <= 1) SpellingStage.FreeRecall else progress.stage
            SpellingStage.FreeRecall -> if (
                progress.freeRecallSuccessCount >= 3 &&
                progress.successfulRecallDates.size >= 2 &&
                progress.longestSuccessfulIntervalDays >= 7
            ) SpellingStage.Retained else progress.stage
            SpellingStage.Retained -> progress.stage
        }
    }

    private fun updateScore(current: Double, applies: Boolean, credit: Double): Double =
        if (!applies) current else if (current == 0.0) credit else (current * 0.7 + credit * 0.3).coerceIn(0.0, 1.0)

    private fun nextInterval(current: Int, correct: Boolean, credit: Double): Int = when {
        !correct -> max(1, current / 3)
        credit >= 0.8 -> (current * 2).coerceAtMost(60)
        credit >= 0.4 -> (current + 1).coerceAtMost(60)
        else -> 1
    }

    private fun reviewGrade(
        type: SpellingQuestionType,
        correct: Boolean,
        credit: Double,
        intervalDays: Int,
    ): ReviewGrade = when {
        !correct -> ReviewGrade.Forgot
        credit < 0.8 -> ReviewGrade.Hard
        type == SpellingQuestionType.Recognition || type == SpellingQuestionType.PartialCompletion -> ReviewGrade.Hard
        (type == SpellingQuestionType.FreeRecall || type == SpellingQuestionType.DelayedFreeRecall) && intervalDays >= 7 -> ReviewGrade.Easy
        else -> ReviewGrade.Good
    }

    private fun normalize(value: String): String = value.trim().lowercase()

    private fun isAdjacentTransposition(expected: String, answer: String): Boolean {
        if (expected.length != answer.length) return false
        val mismatch = expected.indices.filter { expected[it] != answer[it] }
        return mismatch.size == 2 && mismatch[1] == mismatch[0] + 1 &&
            expected[mismatch[0]] == answer[mismatch[1]] && expected[mismatch[1]] == answer[mismatch[0]]
    }

    private fun hasDoublingError(expected: String, answer: String): Boolean {
        fun doubles(value: String): Set<String> = value.zipWithNext()
            .filter { it.first == it.second }
            .map { "${it.first}${it.second}" }
            .toSet()
        return doubles(expected) != doubles(answer)
    }

    /**
     * 「按读音猜出来的拼法」：definitely → definately、separate → seperate。
     * 特征是长度不变、错的位置读音相同——弱读元音互换，或同音的辅音写法互换。
     * 这类错不是没记住，是记的是声音，所以单独归一类，画像页据此安排音形对应训练。
     */
    private fun isPhoneticSpelling(expected: String, answer: String): Boolean {
        if (expected.length != answer.length) return false
        val mismatch = expected.indices.filter { expected[it] != answer[it] }
        if (mismatch.isEmpty() || isAdjacentTransposition(expected, answer)) return false
        return mismatch.all { index ->
            val e = expected[index]
            val a = answer[index]
            (e in VOWELS && a in VOWELS) || setOf(e, a) in HOMOPHONE_CONSONANTS
        }
    }

    private fun hasMorphologyError(expected: String, answer: String): Boolean {
        val suffixes = listOf("s", "es", "ed", "ing", "ly", "ness", "ment", "tion")
        return suffixes.any { suffix ->
            (expected.endsWith(suffix) || answer.endsWith(suffix)) &&
                expected.take(max(1, expected.length - suffix.length - 1)) ==
                answer.take(max(1, answer.length - suffix.length - 1))
        }
    }

    private fun mergeWeakSegment(existing: List<WeakSegment>, new: WeakSegment): List<WeakSegment> {
        val match = existing.indexOfFirst { it.start == new.start && it.endExclusive == new.endExclusive }
        if (match < 0) return (existing + new).sortedByDescending { it.errorCount }.take(5)
        return existing.mapIndexed { index, segment ->
            if (index == match) segment.copy(errorCount = segment.errorCount + 1) else segment
        }.sortedByDescending { it.errorCount }.take(5)
    }

    private fun stableOptionOrder(option: String, expected: String): Int = (option + expected).fold(17) { acc, char -> acc * 31 + char.code }

    /**
     * 编辑距离 1 且答得极快 = 手滑。两个条件缺一不可：
     * 只看距离会把"就差一个字母想不起来"也放过，只看用时会把闭眼乱按也放过。
     */
    private fun isLikelyTypo(expected: String, answer: String, responseTimeMillis: Long): Boolean {
        if (responseTimeMillis !in 1..TYPO_MAX_MILLIS) return false
        if (answer.isBlank()) return false
        return editDistanceAtMostOne(expected, answer)
    }

    /** 只判断"是不是恰好差一步"，不需要完整的编辑距离矩阵。 */
    private fun editDistanceAtMostOne(expected: String, answer: String): Boolean {
        if (expected == answer) return false
        if (kotlin.math.abs(expected.length - answer.length) > 1) return false
        val longer = if (expected.length >= answer.length) expected else answer
        val shorter = if (expected.length >= answer.length) answer else expected
        var i = 0
        var j = 0
        var used = false
        while (i < longer.length && j < shorter.length) {
            if (longer[i] == shorter[j]) {
                i++
                j++
                continue
            }
            if (used) return false
            used = true
            i++
            if (longer.length == shorter.length) j++
        }
        return true
    }

    private val VOWELS = setOf('a', 'e', 'i', 'o', 'u')

    /** 写法不同但读音相近的辅音对，用于识别「按读音拼」。 */
    private val HOMOPHONE_CONSONANTS = setOf(
        setOf('c', 's'),
        setOf('c', 'k'),
        setOf('s', 'z'),
        setOf('g', 'j'),
        setOf('f', 'v'),
    )

    /** 手滑的用时上限。设计稿把门槛列为待定，先按它自己给的候选值取 2 秒。 */
    const val TYPO_MAX_MILLIS: Long = 2_000

    /** 提示梯度的最高一级：到这儿答案就直接摆出来了，本次得分为 0。 */
    const val MAX_HINT_LEVEL = 5
}
