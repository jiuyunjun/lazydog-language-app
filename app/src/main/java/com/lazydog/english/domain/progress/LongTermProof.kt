package com.lazydog.english.domain.progress

import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 一次拼写作答，只留下"证明进步"需要的那几个字段。
 *
 * 关键是 [answer]：当时**真的写错成什么样**。这条信息决定了长期证明能不能落到实处——
 * "你以前会拼错" 和 "你以前把 receive 写成 recieve" 是两句话。
 */
data class SpellingMoment(
    val itemId: Long,
    val expected: String,
    val answer: String,
    val correct: Boolean,
    val hintLevel: Int,
    val at: Instant,
)

/**
 * 长期证明（`持续学习DESIGN.md` §14.3）：**你以前不会，现在会了**。
 *
 * 这是整份文档里最难伪造、也最有说服力的一条反馈：它指着一个具体的日子、
 * 一个具体的错误，而不是一句"你进步了"。
 */
data class LongTermProof(
    val term: String,
    /** 当时写成了什么。 */
    val pastAnswer: String,
    /** 那次错误是多少天前。 */
    val daysAgo: Int,
)

/** 旧错误至少要有这么久，才称得上"以前"。太近了拿出来说，用户自己都还记得。 */
const val PROOF_MIN_GAP_DAYS = 21L

/**
 * 从"最近无提示写对的"和"更早写错的"里配出一条长期证明。
 *
 * [recentSuccesses] 是最近几天里**没用提示**写对的作答，[olderMistakes] 是更早写错的作答。
 * 无提示是硬要求：提示答对了不足以证明会了，那样的证明经不起用户自己回想。
 *
 * 挑间隔最大的那一条：跨度越长越有说服力，"三个月前你还写错"比"三周前"更值得说。
 */
fun longTermProof(
    recentSuccesses: List<SpellingMoment>,
    olderMistakes: List<SpellingMoment>,
    now: Instant,
): LongTermProof? {
    val succeeded = recentSuccesses
        .filter { it.correct && it.hintLevel == 0 }
        .map { it.itemId }
        .toSet()
    if (succeeded.isEmpty()) return null

    return olderMistakes
        .asSequence()
        .filter { it.itemId in succeeded && !it.correct }
        // 写错才算数：空着不写、或者写的就是正确答案（判定口径不同导致的）都不能当证据。
        .filter { it.answer.isNotBlank() && !it.answer.equals(it.expected, ignoreCase = true) }
        .map { it to ChronoUnit.DAYS.between(it.at, now) }
        .filter { (_, days) -> days >= PROOF_MIN_GAP_DAYS }
        .maxByOrNull { (_, days) -> days }
        ?.let { (moment, days) ->
            LongTermProof(
                term = moment.expected,
                pastAnswer = moment.answer,
                daysAgo = days.toInt(),
            )
        }
}
