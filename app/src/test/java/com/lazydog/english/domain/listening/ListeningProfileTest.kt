package com.lazydog.english.domain.listening

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 听力画像：能力地图的第二层（`持续学习DESIGN.md` §16）。 */
class ListeningProfileTest {

    private fun record(
        correct: Boolean,
        features: List<String>,
        playCount: Int = 1,
        hint: ListeningHintLevel = ListeningHintLevel.None,
        mishear: MishearType? = null,
    ) = ListeningRecord(
        correct = correct,
        playCount = playCount,
        hintLevel = hint,
        audioFeatures = features,
        mishearType = mishear,
        score = if (correct) 100 else 0,
    )

    @Test
    fun `没数据就是空画像`() {
        assertEquals(ListeningProfile.Empty, listeningProfile(emptyList()))
    }

    @Test
    fun `样本不够不给总正确率`() {
        val profile = listeningProfile(List(5) { record(correct = true, features = listOf("linking")) })
        assertNull(profile.percent)
        assertEquals(5, profile.attempts)
    }

    @Test
    fun `每个听觉难点各算各的`() {
        val profile = listeningProfile(
            List(4) { record(correct = false, features = listOf("linking")) } +
                List(6) { record(correct = true, features = listOf("numbers")) },
        )
        val linking = profile.features.first { it.feature == "linking" }
        val numbers = profile.features.first { it.feature == "numbers" }
        assertEquals(0, linking.percent)
        assertEquals(100, numbers.percent)
        // 最弱的排最前面：这一页是用来决定下一步练什么的。
        assertEquals("linking", profile.features.first().feature)
    }

    @Test
    fun `一句命中多个难点时每个都记一次`() {
        val profile = listeningProfile(listOf(record(correct = false, features = listOf("linking", "reduction"))))
        assertEquals(2, profile.features.size)
        assertTrue(profile.features.all { it.attempts == 1 })
    }

    @Test
    fun `样本够的排在样本不够的前面`() {
        val profile = listeningProfile(
            List(5) { record(correct = true, features = listOf("linking")) } +
                listOf(record(correct = false, features = listOf("rare"))),
        )
        // "rare" 正确率 0% 更低，但只练过一次，不该顶在最前面当结论。
        assertEquals("linking", profile.features.first().feature)
    }

    @Test
    fun `用了提示答对仍然算答对，裸听一遍另算一档`() {
        val profile = listeningProfile(
            listOf(
                record(correct = true, features = listOf("linking"), hint = ListeningHintLevel.Keyword),
                record(correct = true, features = listOf("linking"), playCount = 3),
                record(correct = true, features = listOf("linking")),
            ),
        )
        // 提示的代价已经体现在分数里，这里再罚一次等于同一件事扣两遍。
        assertEquals(3, profile.correct)
        // 真实听力看这个：没提示、一遍就懂。
        assertEquals(1, profile.firstListen)
    }

    @Test
    fun `最常栽的误听类型按次数排`() {
        val profile = listeningProfile(
            listOf(
                record(false, listOf("linking"), mishear = MishearType.Negation),
                record(false, listOf("linking"), mishear = MishearType.Linking),
                record(false, listOf("linking"), mishear = MishearType.Negation),
                record(true, listOf("linking")),
            ),
        )
        assertEquals(MishearType.Negation, profile.mishears.first().type)
        assertEquals(2, profile.mishears.first().count)
        assertEquals(2, profile.mishears.size)
    }
}
