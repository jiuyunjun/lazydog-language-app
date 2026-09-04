package com.lazydog.english.domain.progress

import com.lazydog.english.domain.generation.ContentValidation
import kotlin.random.Random

/**
 * 可以拿来出挑战题的一个旧知识点。
 *
 * [learnedDaysAgo] 是"学到现在多久"，它是这个功能的全部意义所在——
 * 用两周前的词组一句话让人听懂，和用今天刚学的词，完全是两回事。
 */
data class ProofCandidate(
    val itemId: Long,
    val term: String,
    val learnedDaysAgo: Int,
)

/**
 * 进步挑战（`持续学习DESIGN.md` §15）：拿两到四周前学的几个表达组成一句话，
 * 让用户**听**。听懂了就是证据——"以前听不懂，现在听懂了"这句话，
 * 由他自己的耳朵得出，比任何分数都有说服力。
 */
data class ProofChallenge(
    /** 句子里真正用到的旧词。 */
    val terms: List<String>,
    val sentenceEn: String,
    val sentenceZh: String,
    /** 这批词里最久的那个是多少天前学的，用来说"你听懂了 N 周前学的东西"。 */
    val oldestDaysAgo: Int,
    /** 关键词识别用的干扰项：句子里没有的词。 */
    val decoys: List<String>,
) {
    /** 选项按固定顺序摆开，避免每次重组都换位置（和听力页同一个理由）。 */
    fun options(seed: Int): List<String> = (terms + decoys).shuffled(Random(seed))
}

/** 挑战只用这个区间里学的词：太近了不算"以前"，太远了可能早忘干净，那是复习不是证明。 */
const val PROOF_WINDOW_MIN_DAYS = 14
const val PROOF_WINDOW_MAX_DAYS = 28

/** 一句话里放几个旧词。再多句子就开始别扭了。 */
const val PROOF_TERM_COUNT = 4

/** 关键词识别摆几个干扰项。 */
const val PROOF_DECOY_COUNT = 4

/**
 * 从候选里挑出这次要考的词。够不着 [PROOF_TERM_COUNT] 个就返回空——
 * **宁可不出这一轮，也不要用刚学的词凑数**：凑出来的"证明"证明不了任何事。
 */
fun pickProofTerms(
    candidates: List<ProofCandidate>,
    random: Random = Random.Default,
): List<ProofCandidate> {
    val eligible = candidates
        .filter { it.learnedDaysAgo in PROOF_WINDOW_MIN_DAYS..PROOF_WINDOW_MAX_DAYS }
        .filter { it.term.isNotBlank() && it.term.trim().split(WHITESPACE).size <= MAX_TERM_WORDS }
        .distinctBy { it.term.lowercase() }
    if (eligible.size < PROOF_TERM_COUNT) return emptyList()
    // 学得越久的越值得拿出来说，但也不能每次都是同样那四个，所以在较早的一批里随机取。
    val pool = eligible.sortedByDescending { it.learnedDaysAgo }.take(PROOF_TERM_COUNT * 3)
    return pool.shuffled(random).take(PROOF_TERM_COUNT)
}

/**
 * 校验模型给的句子。@return 失败原因，null 表示通过。
 *
 * 最要紧的是**每个词都真的在句子里**：少一个，这一轮的结论"你听懂了四个旧表达"就是假的。
 */
fun validateProofSentence(sentenceEn: String, sentenceZh: String, terms: List<String>): String? {
    val en = sentenceEn.trim()
    val zh = sentenceZh.trim()
    return when {
        en.isBlank() -> "句子是空的"
        en.length > MAX_SENTENCE_LENGTH -> "句子太长，听一遍记不住"
        zh.isBlank() -> "缺少中文翻译"
        zh.length > MAX_TRANSLATION_LENGTH -> "翻译太长"
        en.any { it.code in CJK_RANGE } -> "英文句子里混进了中文"
        terms.any { !sentenceContains(en, it) } ->
            "句子里没用上：" + terms.filterNot { sentenceContains(en, it) }.joinToString("、")
        else -> null
    }
}

/**
 * 这句话里有没有用上这个词。
 *
 * 多词短语要拆开逐词看：`ContentValidation.exampleContainsTerm` 是按**整词**匹配的，
 * 拿它查 "end up" 永远查不到——而 "end up"、"supposed to" 这类短语恰恰是这一关最想考的东西。
 * 拆开之后 "ended up" 能命中（end 的变形 + up），代价是不校验词序；
 * 出句提示词已经要求"必须真的用上"，这里只是挡住明显漏词的情况。
 */
private fun sentenceContains(sentence: String, term: String): Boolean =
    term.trim().split(WHITESPACE).filter { it.isNotBlank() }
        .all { ContentValidation.exampleContainsTerm(sentence, it) }

/** 词条里存着整句表达（`pos=expression`），拿一整句话去组一句话是荒唐的，所以只要词和短语。 */
private const val MAX_TERM_WORDS = 3

private val WHITESPACE = Regex("""\s+""")

private const val MAX_SENTENCE_LENGTH = 180
private const val MAX_TRANSLATION_LENGTH = 120
private val CJK_RANGE = 0x4E00..0x9FFF
