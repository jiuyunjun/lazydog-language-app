package com.lazydog.english.domain.vocabulary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 假索引：第 n 个词排名就是 n，够测所有挑词逻辑，不用碰 assets。 */
private class FakeIndex(private val words: List<String>) : WordFrequencyIndex {
    constructor(count: Int) : this((1..count).map { "w$it" })

    override val size: Int get() = words.size
    override fun rankOf(word: String): Int? =
        words.indexOf(word.trim().lowercase()).takeIf { it >= 0 }?.plus(1)

    override fun wordsInRange(fromRank: Int, toRank: Int): List<String> {
        val from = (fromRank - 1).coerceAtLeast(0)
        val to = toRank.coerceAtMost(words.size)
        return if (from >= to) emptyList() else words.subList(from, to)
    }
}

class FrequencyBandTest {

    @Test
    fun `档位按排名切分，边界归上一档`() {
        assertEquals(FrequencyBand.Top1000, FrequencyBand.forRank(1))
        assertEquals(FrequencyBand.Top1000, FrequencyBand.forRank(1_000))
        assertEquals(FrequencyBand.Top2000, FrequencyBand.forRank(1_001))
        assertEquals(FrequencyBand.Top12000, FrequencyBand.forRank(12_000))
        assertEquals(FrequencyBand.Rare, FrequencyBand.forRank(12_001))
    }

    @Test
    fun `没收录的词算生僻，而不是当成排名很大的词`() {
        val index = FakeIndex(listOf("apple"))
        assertNull(index.rankOf("photosynthesis"))
        assertEquals(FrequencyBand.Rare, index.bandOf("photosynthesis"))
        assertEquals(FrequencyBand.Top1000, index.bandOf("APPLE"))
    }
}

class VocabularyCandidatesTest {

    @Test
    fun `等级越高，取词窗口越靠后`() {
        val a1 = VocabularyCandidates.rankWindow(1.0)
        val b1 = VocabularyCandidates.rankWindow(3.0)
        val c1 = VocabularyCandidates.rankWindow(5.0)

        assertTrue(a1.first < b1.first)
        assertTrue(b1.first < c1.first)
        assertTrue(a1.last < b1.last)
        assertTrue(b1.last < c1.last)
    }

    @Test
    fun `半档不跳变——B1 和 B1+ 的窗口应该是连续的`() {
        val b1 = VocabularyCandidates.rankWindow(3.0)
        val b1plus = VocabularyCandidates.rankWindow(3.5)
        val b2 = VocabularyCandidates.rankWindow(4.0)

        assertTrue(b1plus.first in b1.first..b2.first)
        assertTrue(b1plus.last in b1.last..b2.last)
    }

    @Test
    fun `能力值超出 0 到 5 也不会炸`() {
        assertEquals(VocabularyCandidates.rankWindow(0.0), VocabularyCandidates.rankWindow(-3.0))
        assertEquals(VocabularyCandidates.rankWindow(5.0), VocabularyCandidates.rankWindow(9.0))
    }

    @Test
    fun `候选词按词频升序，且跳过已学的`() {
        val index = FakeIndex(20_000)
        val window = VocabularyCandidates.rankWindow(3.0)
        val firstThree = index.wordsInRange(window.first, window.first + 2)

        val picked = VocabularyCandidates.select(
            index = index,
            cefrScore = 3.0,
            knownTerms = listOf(firstThree[0], firstThree[1].uppercase()),
            count = 3,
        )

        // 前两个被认掉（大小写不敏感），所以队头应该是第三个。
        assertEquals(firstThree[2], picked.first())
        assertEquals(3, picked.size)
        assertTrue(picked.none { it == firstThree[0] || it == firstThree[1] })
        // 严格按排名升序，不打乱。
        assertEquals(picked.sortedBy { index.rankOf(it) }, picked)
    }

    @Test
    fun `候选词全部落在该等级的窗口内`() {
        val index = FakeIndex(20_000)
        val window = VocabularyCandidates.rankWindow(2.0)

        val picked = VocabularyCandidates.select(index, 2.0, emptyList(), count = 50)

        assertEquals(50, picked.size)
        assertTrue(picked.all { index.rankOf(it)!! in window })
    }

    @Test
    fun `索引不可用时返回空，调用方退回不按词频挑词`() {
        assertTrue(VocabularyCandidates.select(EmptyWordFrequencyIndex, 3.0, emptyList(), 20).isEmpty())
    }

    @Test
    fun `要 0 个或负数个都返回空，不抛异常`() {
        val index = FakeIndex(5_000)
        assertTrue(VocabularyCandidates.select(index, 3.0, emptyList(), 0).isEmpty())
        assertTrue(VocabularyCandidates.select(index, 3.0, emptyList(), -1).isEmpty())
    }

    @Test
    fun `词表比窗口短时只返回有的那些，不越界`() {
        // 窗口是 2000..6000，但表里只有 2500 个词，所以能给的只有 2000..2500 这 501 个。
        // 要得比这还多，也不能越界或抛异常。
        val index = FakeIndex(2_500)
        val picked = VocabularyCandidates.select(index, 3.0, emptyList(), count = 1_000)

        assertEquals(501, picked.size)
        assertTrue(picked.all { index.rankOf(it)!! in 2_000..2_500 })
    }
}
