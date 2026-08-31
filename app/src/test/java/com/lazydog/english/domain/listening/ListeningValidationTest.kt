package com.lazydog.english.domain.listening

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ListeningValidationTest {

    @Test
    fun `a well formed item passes`() {
        val result = ListeningValidation.validate(listOf(sampleItem()), maxCount = 10)
        assertEquals(1, result.valid.size)
        assertTrue(result.droppedNotes.isEmpty())
    }

    @Test
    fun `the key expression must actually be in the sentence`() {
        // 揭晓页要高亮它，Hint 3 要挖空它——对不上就整题作废，不能靠界面兜。
        val bad = sampleItem(keyEn = "never showed up")
        assertDropped(bad, "重点表达不在句子里")
    }

    @Test
    fun `there must be exactly two distractors`() {
        assertDropped(sampleItem(wrong = listOf("我提前参加了会议")), "干扰项必须正好两条")
    }

    @Test
    fun `options that repeat each other are rejected`() {
        // 选项撞车的话，三选一实际上变成二选一，题就白出了。
        assertDropped(
            sampleItem(wrong = listOf("我勉强准时赶到了会议。", "我几乎没有参加会议")),
            "选项之间重复",
        )
    }

    @Test
    fun `a scene hint that gives away the meaning is rejected`() {
        val leaking = sampleItem().copy(sceneHintZh = "意思是我勉强准时赶到了会议")
        assertDropped(leaking, "场景提示泄露了答案")
    }

    @Test
    fun `chinese inside the english sentence is rejected`() {
        assertDropped(sampleItem(textEn = "I barely 赶到 the meeting on time."), "英文句子里混了中文")
    }

    @Test
    fun `sentences that are too short to be listening practice are rejected`() {
        assertDropped(sampleItem(textEn = "I made it.", keyEn = "made it"), "句子长度应该在 4~30 词")
    }

    @Test
    fun `duplicates within one batch are dropped but the first one survives`() {
        val result = ListeningValidation.validate(listOf(sampleItem(), sampleItem()), maxCount = 10)
        assertEquals(1, result.valid.size)
        assertTrue(result.droppedNotes.single().contains("本批重复"))
    }

    @Test
    fun `validation stops once maxCount good items are collected`() {
        val items = listOf(
            sampleItem(textEn = "I barely made it to the meeting on time."),
            sampleItem(textEn = "We barely made it before the doors closed."),
            sampleItem(textEn = "They barely made it through the first round."),
        )
        assertEquals(2, ListeningValidation.validate(items, maxCount = 2).valid.size)
    }

    private fun assertDropped(item: ListeningItem, reason: String) {
        val result = ListeningValidation.validate(listOf(item), maxCount = 10)
        assertTrue("应该被丢掉，结果留下了", result.valid.isEmpty())
        assertTrue("丢弃原因不对：${result.droppedNotes}", result.droppedNotes.single().endsWith(reason))
    }
}
