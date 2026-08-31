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
        // 揭晓页要高亮它，挖空提示要挖它——对不上就整题作废，不能靠界面兜。
        assertDropped(sampleItem(keyEn = "never showed up"), "重点表达不在句子里")
    }

    @Test
    fun `there must be exactly three distractors`() {
        assertDropped(sampleItem(distractors = sampleDistractors().take(2)), "干扰项必须正好三条")
    }

    @Test
    fun `options that repeat each other are rejected`() {
        // 选项撞车的话四选一实际上变成三选一，题就白出了。
        val clashing = sampleDistractors().toMutableList()
        clashing[0] = clashing[0].copy(meaningZh = "我勉强准时赶到了会议。")
        assertDropped(sampleItem(distractors = clashing), "选项之间重复")
    }

    @Test
    fun `distractors must each name a different kind of mishearing`() {
        // 三条都归到同一类的话，"你栽在哪一类"就没有区分度了。
        val sameType = sampleDistractors().map { it.copy(mishearType = MishearType.Negation) }
        assertDropped(sampleItem(distractors = sameType), "三条干扰项的误听类型重复")
    }

    @Test
    fun `a distractor without an explanation is rejected`() {
        // 答错时要把这句话原样显示出来，空的就等于只告诉用户"你错了"。
        val silent = sampleDistractors().toMutableList()
        silent[1] = silent[1].copy(whyZh = "")
        assertDropped(sampleItem(distractors = silent), "干扰项没说清为什么会听错")
    }

    @Test
    fun `an unknown mishear type never reaches the domain`() {
        // 封闭集合之外的值在 payload 层就被丢掉，落到这里表现为干扰项数量不足。
        assertEquals(null, MishearType.fromWire("随便写的"))
        assertEquals(MishearType.SimilarAction, MishearType.fromWire("Similar-Action"))
    }

    @Test
    fun `a scene hint that gives away the meaning is rejected`() {
        assertDropped(sampleItem().copy(sceneHintZh = "意思是我勉强准时赶到了会议"), "场景提示泄露了答案")
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
