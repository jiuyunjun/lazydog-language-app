package com.lazydog.english.domain.pronunciation

import kotlin.random.Random

/**
 * 出听辨题（`发音与音标学习DESIGN.md` §10、§11）。
 *
 * 出题放在 domain 而不是页面里，是因为这里有三条不能随手改的规则，而它们在页面里
 * 只会变成几行看起来无害的 `if`：
 *
 * 1. **正确答案的位置必须真的随机**（§10.5）。固定在 A、或者按下标奇偶交替，
 *    用户几轮之后就在答位置而不是答声音。
 * 2. **词对要换**（§10.3）。同一组对比连着出同一对词，用户记住的是「ship 那题选左边」。
 * 3. **Sound → IPA 只在这个音已经能听辨之后出**（§11.6）。符号是给已经建立起来的声音
 *    贴的标签，反过来先背符号就退回成了背音标表。
 */

/** 一道听辨题。 */
data class PerceptionQuestion(
    val targetId: String,
    val contrastId: String,
    val exercise: PerceptionExercise,
    /** 实际播出去的那个词。Sound → IPA 也播词——孤立音位的合成不可靠。 */
    val promptWord: String,
    val promptIpa: String,
    val options: List<PerceptionOption>,
    val difficulty: PerceptionDifficulty,
    /** 这一题用的是哪一对词。记进作答，用来看「换了词还认得吗」。 */
    val variantId: String,
    /** 答错时给出的那句解释所依据的两个音位。 */
    val leftPhonemeId: String,
    val rightPhonemeId: String,
) {
    val correctOption: PerceptionOption get() = options.first { it.correct }
}

data class PerceptionOption(
    val label: String,
    val ipa: String,
    val correct: Boolean,
)

/**
 * 出题器。
 *
 * [random] 由调用方给，测试里传固定种子就能断言「答案不总在同一边」。
 */
class PerceptionQuestionFactory(
    private val catalog: PhonemeCatalog,
    private val random: Random = Random.Default,
) {

    /**
     * 出下一题。
     *
     * [recentVariants] 是最近几题用过的词对 id，用来避免连着出同一对——不是硬性去重，
     * 词对用完了还是要回头用的，只是不连着用。
     */
    fun next(
        contrast: PhonemeContrast,
        difficulty: PerceptionDifficulty,
        recentVariants: Collection<String> = emptyList(),
    ): PerceptionQuestion? {
        val pairs = contrast.minimalPairs
        if (pairs.isEmpty()) return null
        val pool = when (difficulty) {
            // 固定词对是给「刚退回来、先站稳」用的：永远是这一组里最有代表性的第一对。
            PerceptionDifficulty.FixedPair -> listOf(pairs.first())
            else -> pairs.filterNot { variantId(contrast, it) in recentVariants }.ifEmpty { pairs }
        }
        val pair = pool[random.nextInt(pool.size)]
        val playLeft = random.nextBoolean()
        val side = if (playLeft) PairSide.Left else PairSide.Right

        return when (difficulty.exercise) {
            PerceptionExercise.SoundToIpa -> soundToIpa(contrast, pair, side, difficulty)
            PerceptionExercise.MinimalPairThree -> minimalPair(contrast, pair, side, difficulty, pairs, three = true)
            PerceptionExercise.MinimalPairTwo -> minimalPair(contrast, pair, side, difficulty, pairs, three = false)
        }
    }

    private fun minimalPair(
        contrast: PhonemeContrast,
        pair: MinimalPair,
        side: PairSide,
        difficulty: PerceptionDifficulty,
        allPairs: List<MinimalPair>,
        three: Boolean,
    ): PerceptionQuestion {
        val correct = PerceptionOption(pair.wordFor(side), pair.ipaFor(side), correct = true)
        val other = side.opposite()
        val options = mutableListOf(
            correct,
            PerceptionOption(pair.wordFor(other), pair.ipaFor(other), correct = false),
        )
        if (three) {
            // 第三个干扰项从别的词对里借一个词：它和目标只差一个音以外的地方，
            // 所以它考的是「你是不是真的在听那个音」，而不是「你认不认得这个词」。
            allPairs.asSequence()
                .filter { it.leftWord != pair.leftWord }
                .map { PerceptionOption(it.wordFor(side), it.ipaFor(side), correct = false) }
                .firstOrNull { candidate -> options.none { it.label == candidate.label } }
                ?.let { options += it }
        }
        return PerceptionQuestion(
            targetId = PronunciationTarget.ofContrast(contrast.id),
            contrastId = contrast.id,
            exercise = if (options.size >= 3) {
                PerceptionExercise.MinimalPairThree
            } else {
                PerceptionExercise.MinimalPairTwo
            },
            promptWord = correct.label,
            promptIpa = correct.ipa,
            options = options.shuffled(random),
            difficulty = difficulty,
            variantId = variantId(contrast, pair),
            leftPhonemeId = contrast.leftPhonemeId,
            rightPhonemeId = contrast.rightPhonemeId,
        )
    }

    private fun soundToIpa(
        contrast: PhonemeContrast,
        pair: MinimalPair,
        side: PairSide,
        difficulty: PerceptionDifficulty,
    ): PerceptionQuestion? {
        val targetPhonemeId =
            if (side == PairSide.Left) contrast.leftPhonemeId else contrast.rightPhonemeId
        val target = catalog.phoneme(targetPhonemeId) ?: return null
        val distractors = catalog.phonemes
            .filter { it.id != target.id && it.category == target.category }
            .shuffled(random)
            .take(2)
        if (distractors.size < 2) return null
        val options = (
            listOf(PerceptionOption(target.ipa, target.ipa, correct = true)) +
                distractors.map { PerceptionOption(it.ipa, it.ipa, correct = false) }
            ).shuffled(random)
        return PerceptionQuestion(
            targetId = PronunciationTarget.ofContrast(contrast.id),
            contrastId = contrast.id,
            exercise = PerceptionExercise.SoundToIpa,
            promptWord = pair.wordFor(side),
            promptIpa = pair.ipaFor(side),
            options = options,
            difficulty = difficulty,
            variantId = variantId(contrast, pair),
            leftPhonemeId = contrast.leftPhonemeId,
            rightPhonemeId = contrast.rightPhonemeId,
        )
    }

    private fun variantId(contrast: PhonemeContrast, pair: MinimalPair): String =
        "${contrast.id}:${pair.leftWord}"
}

private fun PairSide.opposite(): PairSide =
    if (this == PairSide.Left) PairSide.Right else PairSide.Left

/**
 * Sound → IPA 能不能出（§11.6）。
 *
 * 门槛就是「能听辨」这个阶段本身：没到这一步就出符号题，等于在考一个还没建立起来的
 * 声音类别叫什么名字。
 */
fun PronunciationProgress.allowsSymbolRecall(): Boolean =
    stage == PronunciationStage.Discriminating ||
        stage == PronunciationStage.Producing ||
        stage == PronunciationStage.Stable
