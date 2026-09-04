package com.lazydog.english.domain.progress

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 进步挑战的选词和校验（`持续学习DESIGN.md` §15）。 */
class ProofChallengeTest {

    private fun candidate(id: Long, term: String, daysAgo: Int) = ProofCandidate(id, term, daysAgo)

    @Test
    fun `只用两到四周前学的词`() {
        val picked = pickProofTerms(
            listOf(
                candidate(1, "actually", 20),
                candidate(2, "supposed", 21),
                candidate(3, "end up", 25),
                candidate(4, "probably", 15),
                // 太近和太远的都不要。
                candidate(5, "brand new", 2),
                candidate(6, "ancient", 200),
            ),
            random = Random(1),
        )
        assertEquals(PROOF_TERM_COUNT, picked.size)
        assertTrue(picked.none { it.term == "brand new" || it.term == "ancient" })
    }

    @Test
    fun `不够四个就不出这一轮`() {
        // 用刚学的词凑数，凑出来的"证明"证明不了任何事。
        val picked = pickProofTerms(
            listOf(
                candidate(1, "actually", 20),
                candidate(2, "supposed", 21),
                candidate(3, "fresh", 1),
            ),
        )
        assertTrue(picked.isEmpty())
    }

    @Test
    fun `同一个词不会被算两次`() {
        val picked = pickProofTerms(
            listOf(
                candidate(1, "actually", 20),
                candidate(2, "Actually", 21),
                candidate(3, "supposed", 22),
                candidate(4, "end up", 23),
            ),
        )
        assertTrue(picked.isEmpty())
    }

    @Test
    fun `句子必须真的用上每一个词`() {
        val terms = listOf("actually", "supposed", "end up")
        assertNull(
            validateProofSentence(
                sentenceEn = "I was actually supposed to go, but I ended up staying home.",
                sentenceZh = "我本来是要去的，结果还是待在家里了。",
                terms = terms,
            ),
        )
        // 少一个词，"你听懂了三个旧表达"这句结论就是假的。
        val missing = validateProofSentence(
            sentenceEn = "I was actually going to go, but I stayed home.",
            sentenceZh = "我本来想去，后来待在家里了。",
            terms = terms,
        )
        assertNotNull(missing)
        assertTrue(missing!!.contains("supposed"))
    }

    @Test
    fun `词形变化算用上了`() {
        assertNull(
            validateProofSentence(
                sentenceEn = "She ended up calling him back.",
                sentenceZh = "她最后还是给他回了电话。",
                terms = listOf("end"),
            ),
        )
    }

    @Test
    fun `太长、缺翻译、混中文都要挡掉`() {
        val terms = listOf("actually")
        assertNotNull(validateProofSentence("a".repeat(200) + " actually", "还行", terms))
        assertNotNull(validateProofSentence("I actually went.", "", terms))
        assertNotNull(validateProofSentence("I actually 去了.", "我去了。", terms))
    }

    @Test
    fun `选项把正解和干扰项混在一起`() {
        val challenge = ProofChallenge(
            terms = listOf("actually", "supposed", "end up", "probably"),
            sentenceEn = "…",
            sentenceZh = "…",
            oldestDaysAgo = 25,
            decoys = listOf("meanwhile", "though", "anyway", "besides"),
        )
        val options = challenge.options(seed = 7)
        assertEquals(8, options.size)
        assertTrue(options.containsAll(challenge.terms))
        // 同一个种子每次摆出来的顺序一样，重组不会让选项跳来跳去。
        assertEquals(options, challenge.options(seed = 7))
    }
}
