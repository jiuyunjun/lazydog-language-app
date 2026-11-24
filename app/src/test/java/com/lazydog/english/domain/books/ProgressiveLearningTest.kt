package com.lazydog.english.domain.books

import com.lazydog.english.domain.vocabulary.PartOfSpeech
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressiveLearningTest {

    private fun mapping(zh: String, lemma: String) = ZhMapping(zh, lemma, PartOfSpeech.Noun, 1, 3000, true)

    /** 段落号按每 100 字一段算，方便构造"分散在不同段落"。 */
    private fun occ(start: Int, zh: String = "火把", lemma: String = "torch", paragraph: Int = start / 100) =
        WordOccurrence(start, start + zh.length, paragraph, mapping(zh, lemma))

    private fun input(
        occurrences: List<WordOccurrence>,
        words: Map<String, ReadingWordSnapshot> = emptyMap(),
        shownBefore: Set<Pair<Int, String>> = emptySet(),
        credit: Int = ProgressiveRules.OPPORTUNITY_CHARS,
        daily: Int = 4,
        pool: Int = 15,
        density: ReplacementDensity = ReplacementDensity.Light,
        length: Int = 6000,
    ) = ProgressivePlanner.Input(
        text = "字".repeat(length),
        occurrences = occurrences.sortedBy { it.start },
        lookahead = emptyList(),
        words = words,
        shownBefore = shownBefore,
        disabledZh = emptySet(),
        excludedStarts = emptySet(),
        eligibleNew = { true },
        readingStart = 0,
        credit = credit,
        dailyRemaining = daily,
        poolRemaining = pool,
        density = density,
        now = NOW,
    )

    @Test
    fun `light density limits a 500 char window, new words, paragraphs and same sense`() {
        val gate = DensityGate(ReplacementDensity.Light)
        val a = occ(0, "火把", "torch")
        gate.add(a, isNew = true)
        assertFalse("同一个 500 字窗口里只能有 1 个新词", gate.fits(occ(300, "洞口", "cave"), isNew = true))
        assertFalse("同一段最多 1 处", gate.fits(occ(50, "洞口", "cave"), isNew = false))
        assertFalse("同义项 500 字内只换 1 处", gate.fits(occ(400), isNew = false))
        val b = occ(200, "洞口", "cave")
        assertTrue(gate.fits(b, isNew = false))
        gate.add(b, isNew = false)
        assertFalse("500 字里最多 2 处", gate.fits(occ(450, "宝剑", "sword"), isNew = false))
        assertTrue(gate.fits(occ(700, "宝剑", "sword"), isNew = false))
    }

    @Test
    fun `a new word is introduced and comes back after its first interval`() {
        // 后文在不同段落反复出现：10（引入）、110（太近，留原文）、900（2 分钟后约 800 字，复现）、2000、3500。
        val plan = ProgressivePlanner.plan(input(listOf(occ(10), occ(110), occ(900), occ(2000), occ(3500))))
        assertEquals(listOf(10, 900), plan.map { it.occurrence.start })
        assertTrue(plan[0].introduces)
        assertFalse(plan[1].introduces)
    }

    @Test
    fun `no quota or no pool room means no new words`() {
        val occurrences = listOf(occ(10), occ(900), occ(2000))
        assertTrue(ProgressivePlanner.plan(input(occurrences, daily = 0)).isEmpty())
        assertTrue(ProgressivePlanner.plan(input(occurrences, pool = 0)).isEmpty())
        assertTrue(ProgressivePlanner.plan(input(occurrences, density = ReplacementDensity.Off)).isEmpty())
    }

    @Test
    fun `opportunity waits for 1500 new chars`() {
        val occurrences = listOf(occ(10), occ(900), occ(1600), occ(2600), occ(3600), occ(4600))
        val plan = ProgressivePlanner.plan(input(occurrences, credit = 0))
        assertEquals(1600, plan.first().occurrence.start)
    }

    @Test
    fun `active words appear only when due and shown places come back`() {
        val torch = ProgressiveRules.senseId("torch", PartOfSpeech.Noun, 1)
        val due = ReadingWordSnapshot(torch, ReadingWordState.Active, step = 2, nextDueAt = NOW - 1, pausedAt = 0)
        val occurrences = listOf(occ(10), occ(2000))
        val duePlan = ProgressivePlanner.plan(input(occurrences, words = mapOf(torch to due), daily = 0))
        assertEquals(listOf(10), duePlan.map { it.occurrence.start })

        val notDue = due.copy(nextDueAt = NOW + ProgressiveRules.DAY)
        assertTrue(ProgressivePlanner.plan(input(occurrences, words = mapOf(torch to notDue), daily = 0)).isEmpty())
        // 已经展示过的位置原样放回，不因为时间没到就突然改字。
        val back = ProgressivePlanner.plan(input(occurrences, words = mapOf(torch to notDue), shownBefore = setOf(2000 to torch), daily = 0))
        assertEquals(listOf(2000), back.map { it.occurrence.start })
        // 「我认识这个意思」以后立即恢复原文。
        val known = notDue.copy(state = ReadingWordState.KnownSelf)
        assertTrue(ProgressivePlanner.plan(input(occurrences, words = mapOf(torch to known), shownBefore = setOf(2000 to torch))).isEmpty())
    }

    @Test
    fun `spread out recurrence beats a burst in one paragraph`() {
        val burst = (0 until 10).map { occ(1000 + it * 5, "洞口", "cave", paragraph = 10) } + occ(20, "洞口", "cave", paragraph = 0)
        val spread = listOf(occ(30, paragraph = 0), occ(1500), occ(2600), occ(4000))
        val plan = ProgressivePlanner.plan(input(burst + spread))
        assertEquals("torch", plan.first { it.introduces }.occurrence.mapping.lemma)
    }

    @Test
    fun `finder protects proper nouns and skips plural forms`() {
        val seg = ChineseSegmenter(
            mapOf(
                "大陆" to ChineseSegmenter.Entry(3000, "n"),
                "火把" to ChineseSegmenter.Entry(500, "n"),
                "几" to ChineseSegmenter.Entry(9000, "m"),
                "支" to ChineseSegmenter.Entry(9000, "q"),
                "他" to ChineseSegmenter.Entry(50000, "r"),
            ),
        )
        val text = "他在北大陆举起火把。北大陆很大。几支火把亮了。"
        val found = OccurrenceFinder.find(text, seg.segment(text)) { zh, _ ->
            when (zh) {
                "大陆" -> mapping("大陆", "continent")
                "火把" -> mapping("火把", "torch")
                else -> null
            }
        }
        assertEquals(listOf("火把"), found.map { it.mapping.zh })
        assertEquals(text.indexOf("火把"), found.single().start)
    }

    @Test
    fun `intervals grow then stay at a day`() {
        assertEquals(2 * 60_000L, ProgressiveRules.intervalMillis(0))
        assertEquals(60 * 60_000L, ProgressiveRules.intervalMillis(1))
        assertEquals(ProgressiveRules.DAY, ProgressiveRules.intervalMillis(2))
        assertEquals(ProgressiveRules.DAY, ProgressiveRules.intervalMillis(7))
        assertEquals(3, ProgressiveRules.countTextChars("火 把\n。", 0, 5))
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
    }
}
