package com.lazydog.english.domain.pronunciation

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 出题的三条规矩（`发音与音标学习DESIGN.md` §10.3、§10.5、§11.6）。
 *
 * 这三条在页面里都只是几行看起来无害的 `if`，但它们各自对应一种「用户看着在进步，
 * 其实在学别的东西」的失败：答位置、答某一道题、背符号。
 */
class PerceptionQuestionFactoryTest {

    private val leftPhoneme = Phoneme(
        id = "i-short",
        ipa = "ɪ",
        category = PhonemeCategory.Vowel,
        groupZh = "短元音",
        shortDescriptionZh = "短、松的前元音",
        articulation = ArticulationGuide(keyActionZh = "放松、一带而过"),
    )
    private val rightPhoneme = leftPhoneme.copy(id = "i-long", ipa = "iː", shortDescriptionZh = "长、紧的前元音")
    private val spare = listOf("e", "æ", "ʌ").mapIndexed { i, ipa ->
        leftPhoneme.copy(id = "spare-$i", ipa = ipa)
    }

    private val contrast = PhonemeContrast(
        id = "i-short__i-long",
        leftPhonemeId = "i-short",
        rightPhonemeId = "i-long",
        minimalPairs = listOf(
            MinimalPair("ship", "sheep", "/ʃɪp/", "/ʃiːp/"),
            MinimalPair("sit", "seat", "/sɪt/", "/siːt/"),
            MinimalPair("live", "leave", "/lɪv/", "/liːv/"),
            MinimalPair("fill", "feel", "/fɪl/", "/fiːl/"),
        ),
    )

    private val catalog = object : PhonemeCatalog {
        override val accent = AccentProfile.GeneralAmerican
        override val phonemes = listOf(leftPhoneme, rightPhoneme) + spare
        override val contrasts = listOf(contrast)
    }

    private fun factory(seed: Int = 7) = PerceptionQuestionFactory(catalog, Random(seed))

    @Test
    fun `正确答案不总在同一边`() {
        val f = factory()
        val positions = (1..40).mapNotNull { i ->
            f.next(contrast, PerceptionDifficulty.RotatingPair)
                ?.let { q -> q.options.indexOfFirst { it.correct } }
        }

        assertEquals(40, positions.size)
        assertTrue(
            "正确答案一直在同一个位置，用户几轮之后就在答位置而不是答声音",
            positions.toSet().size > 1,
        )
    }

    @Test
    fun `播出去的词一定是正确选项`() {
        val f = factory()
        repeat(20) {
            val q = f.next(contrast, PerceptionDifficulty.RotatingPair)!!
            assertEquals(q.promptWord, q.correctOption.label)
            assertEquals(1, q.options.count { it.correct })
        }
    }

    @Test
    fun `轮换档会换词对`() {
        val f = factory()
        val variants = (1..20).mapNotNull { f.next(contrast, PerceptionDifficulty.RotatingPair)?.variantId }

        assertTrue(
            "同一组对比连着出同一对词，用户记住的是「那道题选左边」",
            variants.toSet().size > 1,
        )
    }

    @Test
    fun `连着两题不重复同一对词`() {
        val f = factory()
        var recent = listOf<String>()
        repeat(10) {
            val q = f.next(contrast, PerceptionDifficulty.RotatingPair, recent)!!
            assertFalse("刚出过的词对又出了一遍", q.variantId in recent)
            recent = (recent + q.variantId).takeLast(2)
        }
    }

    @Test
    fun `固定档永远用同一对词`() {
        val f = factory()
        val variants = (1..10).mapNotNull { f.next(contrast, PerceptionDifficulty.FixedPair)?.variantId }

        assertEquals(1, variants.toSet().size)
    }

    @Test
    fun `三选一给出三个不重样的选项`() {
        val f = factory()
        repeat(20) {
            val q = f.next(contrast, PerceptionDifficulty.ThreeWay)!!
            assertEquals(3, q.options.size)
            assertEquals(3, q.options.map { it.label }.toSet().size)
            assertEquals(PerceptionExercise.MinimalPairThree, q.exercise)
        }
    }

    @Test
    fun `符号题播的仍然是词，选项是音标`() {
        val f = factory()
        val q = f.next(contrast, PerceptionDifficulty.SymbolRecall)!!

        assertEquals(PerceptionExercise.SoundToIpa, q.exercise)
        assertTrue("孤立音位的合成不可靠，符号题也得播词", q.promptWord.isNotBlank())
        assertEquals(3, q.options.size)
        assertTrue(q.options.any { it.correct })
    }

    @Test
    fun `符号题的干扰项凑不齐就不出这道题`() {
        val thin = object : PhonemeCatalog {
            override val accent = AccentProfile.GeneralAmerican
            override val phonemes = listOf(leftPhoneme, rightPhoneme)
            override val contrasts = listOf(contrast)
        }

        assertNull(
            PerceptionQuestionFactory(thin, Random(1)).next(contrast, PerceptionDifficulty.SymbolRecall),
        )
    }

    @Test
    fun `没到能听辨就不该出符号题`() {
        val introduced = PronunciationProgress(
            targetId = "ct:x",
            perception = SkillEstimate(0.5f, 10),
            stage = PronunciationStage.Introduced,
        )
        val discriminating = introduced.copy(stage = PronunciationStage.Discriminating)

        assertFalse(
            "符号是给已经建立起来的声音贴的标签，反过来就退回成背音标表了",
            introduced.allowsSymbolRecall(),
        )
        assertTrue(discriminating.allowsSymbolRecall())
    }

    @Test
    fun `没有词对的对比组出不了题`() {
        val empty = contrast.copy(id = "empty", minimalPairs = emptyList())

        assertNull(factory().next(empty, PerceptionDifficulty.RotatingPair))
    }

    @Test
    fun `每道题都带着两个音位的 id，答错时才讲得出差在哪`() {
        val q = factory().next(contrast, PerceptionDifficulty.RotatingPair)!!

        assertNotNull(catalog.phoneme(q.leftPhonemeId))
        assertNotNull(catalog.phoneme(q.rightPhonemeId))
    }
}
