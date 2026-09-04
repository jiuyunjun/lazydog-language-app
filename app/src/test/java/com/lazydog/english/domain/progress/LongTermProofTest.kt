package com.lazydog.english.domain.progress

import java.time.Instant
import java.time.temporal.ChronoUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** "你以前不会，现在会了"（`持续学习DESIGN.md` §14.3）。 */
class LongTermProofTest {

    private val now: Instant = Instant.parse("2026-09-04T10:00:00Z")

    private fun moment(
        itemId: Long,
        expected: String,
        answer: String,
        correct: Boolean,
        daysAgo: Long,
        hintLevel: Int = 0,
    ) = SpellingMoment(
        itemId = itemId,
        expected = expected,
        answer = answer,
        correct = correct,
        hintLevel = hintLevel,
        at = now.minus(daysAgo, ChronoUnit.DAYS),
    )

    @Test
    fun `配出当时真的写错的那个拼法`() {
        val proof = longTermProof(
            recentSuccesses = listOf(moment(1, "receive", "receive", correct = true, daysAgo = 0)),
            olderMistakes = listOf(moment(1, "receive", "recieve", correct = false, daysAgo = 40)),
            now = now,
        )
        assertEquals(LongTermProof(term = "receive", pastAnswer = "recieve", daysAgo = 40), proof)
    }

    @Test
    fun `挑跨度最大的那一条`() {
        // 三个月前的错误比三周前更值得说。
        val proof = longTermProof(
            recentSuccesses = listOf(
                moment(1, "receive", "receive", correct = true, daysAgo = 1),
                moment(2, "separate", "separate", correct = true, daysAgo = 0),
            ),
            olderMistakes = listOf(
                moment(1, "receive", "recieve", correct = false, daysAgo = 30),
                moment(2, "separate", "seperate", correct = false, daysAgo = 90),
            ),
            now = now,
        )
        assertEquals("separate", proof?.term)
        assertEquals(90, proof?.daysAgo)
    }

    @Test
    fun `用了提示写对的不算会了`() {
        val proof = longTermProof(
            recentSuccesses = listOf(moment(1, "receive", "receive", correct = true, daysAgo = 0, hintLevel = 2)),
            olderMistakes = listOf(moment(1, "receive", "recieve", correct = false, daysAgo = 40)),
            now = now,
        )
        assertNull(proof)
    }

    @Test
    fun `错得太近就不拿出来说`() {
        // 用户自己都还记得前几天写错过，这算不上"以前"。
        val proof = longTermProof(
            recentSuccesses = listOf(moment(1, "receive", "receive", correct = true, daysAgo = 0)),
            olderMistakes = listOf(moment(1, "receive", "recieve", correct = false, daysAgo = 5)),
            now = now,
        )
        assertNull(proof)
    }

    @Test
    fun `空答案不能当证据`() {
        val proof = longTermProof(
            recentSuccesses = listOf(moment(1, "receive", "receive", correct = true, daysAgo = 0)),
            olderMistakes = listOf(
                moment(1, "receive", "  ", correct = false, daysAgo = 60),
                moment(1, "receive", "recieve", correct = false, daysAgo = 40),
            ),
            now = now,
        )
        // 60 天前那次是空着交的，跨度虽大也不能用；退回 40 天前真写错的那次。
        assertEquals("recieve", proof?.pastAnswer)
        assertEquals(40, proof?.daysAgo)
    }

    @Test
    fun `没有配得上的就什么都不说`() {
        assertNull(longTermProof(emptyList(), emptyList(), now))
        assertNull(
            longTermProof(
                recentSuccesses = listOf(moment(1, "receive", "receive", correct = true, daysAgo = 0)),
                olderMistakes = listOf(moment(9, "separate", "seperate", correct = false, daysAgo = 60)),
                now = now,
            ),
        )
    }
}
