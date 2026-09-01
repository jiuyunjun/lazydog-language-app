package com.lazydog.english.domain.generation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryAssistanceValidationTest {

    private fun hint(
        term: String = "purchase",
        coreMeaning: String = "购买",
        hook: String = "正式场合里的 buy",
        primary: MemoryType = MemoryType.Context,
        secondary: MemoryType? = MemoryType.Contrast,
        morphology: String = "",
        weakSegment: String = "chase",
        commonErrors: List<String> = emptyList(),
        pronunciation: MemoryPronunciation = MemoryPronunciation(listOf("pur", "chase"), 1, ""),
        visual: String = "在收银台把商品正式买下来。",
        confusions: List<MemoryConfusion> = listOf(MemoryConfusion("buy", "buy 更日常")),
        collocations: List<String> = listOf("purchase a ticket"),
        example: String = "We need to purchase new equipment.",
        recall: String = "正式表达「购买设备」用哪个词？",
    ) = MemoryAssistance(
        term = term,
        coreMeaningZh = coreMeaning,
        primaryType = primary,
        secondaryType = secondary,
        memoryHookZh = hook,
        morphologyZh = morphology,
        weakSegment = weakSegment,
        commonErrors = commonErrors,
        pronunciation = pronunciation,
        visualAssociationZh = visual,
        confusions = confusions,
        collocations = collocations,
        exampleEn = example,
        recallQuestionZh = recall,
    )

    @Test
    fun `well formed hint survives cleaning and validation`() {
        val cleaned = MemoryAssistanceValidation.clean(hint())
        assertTrue(cleaned.droppedNotes.isEmpty())
        assertNull(MemoryAssistanceValidation.validate(cleaned.value, "purchase"))
        assertEquals("chase", cleaned.value.weakSegment)
        assertEquals(1, cleaned.value.pronunciation.stress)
    }

    @Test
    fun `hook longer than the limit fails the whole hint`() {
        // §9/§10：钩子长到几秒内读不完就失去意义，这一条重新生成，不是删掉一个字段了事。
        val long = hint(hook = "这个词表示买东西而且比一般的买更正式常见于合同和商务场合".repeat(2))
        val cleaned = MemoryAssistanceValidation.clean(long)
        assertNotNull(MemoryAssistanceValidation.validate(cleaned.value, "purchase"))
    }

    @Test
    fun `missing hook fails even when everything else is there`() {
        val cleaned = MemoryAssistanceValidation.clean(hint(hook = "  "))
        assertNotNull(MemoryAssistanceValidation.validate(cleaned.value, "purchase"))
    }

    @Test
    fun `weak segment that is not part of the word is dropped, not fatal`() {
        // 指不到位置的"易错段"比没有更糟，但它不该连累那条真正有用的钩子。
        val cleaned = MemoryAssistanceValidation.clean(hint(weakSegment = "xyz"))
        assertEquals("", cleaned.value.weakSegment)
        assertNull(MemoryAssistanceValidation.validate(cleaned.value, "purchase"))
        assertTrue(cleaned.droppedNotes.any { it.contains("易错段") })
    }

    @Test
    fun `syllables that do not spell the word are dropped along with the stress`() {
        val cleaned = MemoryAssistanceValidation.clean(
            hint(pronunciation = MemoryPronunciation(listOf("pur", "chess"), 2, "重音在前")),
        )
        assertTrue(cleaned.value.pronunciation.syllables.isEmpty())
        assertEquals(0, cleaned.value.pronunciation.stress)
        // 音节没了，但那句发音说明还留着——它不依赖音节切分。
        assertEquals("重音在前", cleaned.value.pronunciation.noteZh)
    }

    @Test
    fun `stress outside the syllable range becomes unset`() {
        val cleaned = MemoryAssistanceValidation.clean(
            hint(pronunciation = MemoryPronunciation(listOf("pur", "chase"), 5, "")),
        )
        assertEquals(2, cleaned.value.pronunciation.syllables.size)
        assertEquals(0, cleaned.value.pronunciation.stress)
    }

    @Test
    fun `confusions are capped at three and must state a difference`() {
        val cleaned = MemoryAssistanceValidation.clean(
            hint(
                confusions = listOf(
                    MemoryConfusion("buy", "buy 更日常"),
                    MemoryConfusion("acquire", "acquire 更书面"),
                    MemoryConfusion("obtain", "obtain 强调取得"),
                    MemoryConfusion("get", "get 最随意"),
                    // 只给词不给区别，属于凑数，删掉。
                    MemoryConfusion("procure", ""),
                    // 把目标词自己列成易混词没有意义。
                    MemoryConfusion("purchase", "一样"),
                ),
            ),
        )
        assertEquals(MemoryAssistanceValidation.MAX_CONFUSIONS, cleaned.value.confusions.size)
        assertTrue(cleaned.value.confusions.none { it.word == "purchase" })
    }

    @Test
    fun `example without the target word is dropped`() {
        val cleaned = MemoryAssistanceValidation.clean(hint(example = "We need new equipment."))
        assertEquals("", cleaned.value.exampleEn)
        assertNull(MemoryAssistanceValidation.validate(cleaned.value, "purchase"))
    }

    @Test
    fun `common errors never include the correct spelling`() {
        val cleaned = MemoryAssistanceValidation.clean(
            hint(term = "receive", weakSegment = "cei", commonErrors = listOf("recieve", "Receive", "")),
        )
        assertEquals(listOf("recieve"), cleaned.value.commonErrors)
    }

    @Test
    fun `morphology without any english letters is dropped`() {
        // "这个词由两部分组成" 这种只写中文说明的不算拆解，对不上任何字母。
        val cleaned = MemoryAssistanceValidation.clean(hint(morphology = "由两个部分组成"))
        assertEquals("", cleaned.value.morphologyZh)
    }

    @Test
    fun `a hint for another word is rejected`() {
        val cleaned = MemoryAssistanceValidation.clean(hint(term = "buy", weakSegment = "uy"))
        assertNotNull(MemoryAssistanceValidation.validate(cleaned.value, "purchase"))
    }

    @Test
    fun `unknown memory type falls back to context instead of failing`() {
        assertEquals(MemoryType.Context, MemoryType.normalize("SOMETHING_NEW"))
        assertEquals(MemoryType.VisualAssociation, MemoryType.normalize("visual_association"))
        assertNull(MemoryType.normalizeOrNull(""))
        assertNull(MemoryType.normalizeOrNull("SOMETHING_NEW"))
    }
}
