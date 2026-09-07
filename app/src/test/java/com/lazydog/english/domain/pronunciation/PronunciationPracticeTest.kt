package com.lazydog.english.domain.pronunciation

import java.io.File
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import kotlinx.serialization.json.Json

/**
 * 提示阶梯、动态难度、推荐排序，以及 assets 里那份音位表本身的自洽性。
 *
 * 最后一组测试直接读 `app/src/main/assets/phonemes_en_us.json`：它是手写的内容，
 * 而手写内容最容易出的错是引用了一个不存在的 id、或者对比组只配了一对词。
 * 这两种错在设备上都表现为「点进去是空的」，跑一次单测就能挡住。
 */
class PronunciationPracticeTest {

    // ---- 提示阶梯 ----

    @Test
    fun `提示逐级递增，最后一级才是答案`() {
        var hint = PronunciationHint.None
        val seen = mutableListOf(hint)
        repeat(5) {
            hint = hint.next()
            seen += hint
        }

        assertEquals(
            listOf(
                PronunciationHint.None,
                PronunciationHint.Replay,
                PronunciationHint.ContrastPlay,
                PronunciationHint.ShowIpa,
                PronunciationHint.ShowArticulation,
                PronunciationHint.RevealAnswer,
            ),
            seen,
        )
        assertTrue(hint.isFinal)
        assertEquals("到顶之后再点不该越界", hint, hint.next())
    }

    @Test
    fun `给答案那一级的编号就是不计分的那一级`() {
        assertEquals(
            "两处各写一个数字，早晚会对不上",
            PerceptionScoring.ANSWER_HINT_LEVEL,
            PronunciationHint.RevealAnswer.level,
        )
    }

    // ---- 动态难度 ----

    @Test
    fun `全对就往难了调`() {
        assertEquals(
            PerceptionDifficulty.RotatingPair,
            DifficultyPolicy.adjust(PerceptionDifficulty.FixedPair, recentAccuracy = 1f, sampleCount = 6),
        )
    }

    @Test
    fun `错得多就退回去`() {
        assertEquals(
            PerceptionDifficulty.RotatingPair,
            DifficultyPolicy.adjust(PerceptionDifficulty.ThreeWay, recentAccuracy = 0.5f, sampleCount = 6),
        )
    }

    @Test
    fun `落在目标区间里不动档`() {
        assertEquals(
            PerceptionDifficulty.RotatingPair,
            DifficultyPolicy.adjust(PerceptionDifficulty.RotatingPair, recentAccuracy = 0.8f, sampleCount = 6),
        )
    }

    @Test
    fun `样本太少不动档`() {
        assertEquals(
            PerceptionDifficulty.FixedPair,
            DifficultyPolicy.adjust(PerceptionDifficulty.FixedPair, recentAccuracy = 1f, sampleCount = 2),
        )
    }

    // ---- 推荐排序 ----

    private fun progress(
        id: String,
        perceptionScore: Float,
        samples: Int,
        lastPracticedAt: Long? = 0L,
    ) = PronunciationProgress(
        targetId = id,
        perception = SkillEstimate(perceptionScore, samples),
        stage = PronunciationStage.Discriminating,
        lastPracticedAt = lastPracticedAt,
    )

    @Test
    fun `证据不够的目标不进推荐队列`() {
        val weakButUnproven = progress("ct:a", perceptionScore = 0.40f, samples = 3)
        val solidWeak = progress("ct:b", perceptionScore = 0.61f, samples = 28)

        val queue = PracticeQueue.weakest(listOf(weakButUnproven, solidWeak), now = NOW)

        assertEquals("只练过 3 次的 40% 不该插队", listOf("ct:b"), queue.map { it.targetId })
        assertEquals(listOf("ct:a"), PracticeQueue.needsMoreEvidence(listOf(weakButUnproven, solidWeak)).map { it.targetId })
    }

    @Test
    fun `稳定的目标不排进弱项，但会到期回来抽查`() {
        val stable = PronunciationProgress(
            targetId = "ct:stable",
            perception = SkillEstimate(0.95f, 30),
            production = SkillEstimate(0.9f, 20),
            stage = PronunciationStage.Stable,
            lastPracticedAt = NOW - PracticeQueue.RETENTION_INTERVAL_MILLIS - 1,
        )

        assertTrue(PracticeQueue.weakest(listOf(stable), now = NOW).isEmpty())
        assertEquals(listOf("ct:stable"), PracticeQueue.dueForRetention(listOf(stable), NOW).map { it.targetId })
    }

    @Test
    fun `久没练的排在前面`() {
        val justPracticed = progress("ct:fresh", 0.62f, 20, lastPracticedAt = NOW)
        val longAgo = progress("ct:stale", 0.62f, 20, lastPracticedAt = NOW - 30L * 24 * 60 * 60 * 1000)

        val queue = PracticeQueue.weakest(listOf(justPracticed, longAgo), now = NOW)

        assertEquals("ct:stale", queue.first().targetId)
    }

    @Test
    fun `没有任何记录时冷启动推最难的几组`() {
        val catalog = loadCatalog()

        val cold = PracticeQueue.coldStart(catalog)

        assertEquals(3, cold.size)
        assertTrue(cold.first().difficulty >= cold.last().difficulty)
    }

    // ---- assets 里那份音位表 ----

    @Test
    fun `音位表能解析出来，而且不是空的`() {
        val catalog = loadCatalog()

        assertFalse(catalog.isEmpty)
        assertEquals("en-US", catalog.accent.locale)
        assertTrue(catalog.phonemes.size >= 15)
        assertTrue(catalog.contrasts.size >= 8)
    }

    @Test
    fun `对比组引用的音位都存在，而且不自己跟自己比`() {
        val catalog = loadCatalog()

        catalog.contrasts.forEach { contrast ->
            assertNotNull("${contrast.id} 的左音位不存在", catalog.phoneme(contrast.leftPhonemeId))
            assertNotNull("${contrast.id} 的右音位不存在", catalog.phoneme(contrast.rightPhonemeId))
            assertTrue(
                "${contrast.id} 左右是同一个音位",
                contrast.leftPhonemeId != contrast.rightPhonemeId,
            )
        }
    }

    @Test
    fun `每组对比至少三对词`() {
        val catalog = loadCatalog()

        catalog.contrasts.forEach { contrast ->
            assertTrue(
                "${contrast.id} 只有 ${contrast.minimalPairs.size} 对词——" +
                    "词太少用户记住的是「那道题选左边」，不是这两个音的差别",
                contrast.minimalPairs.size >= 3,
            )
        }
    }

    @Test
    fun `音位卡上写的对比 id 真的存在`() {
        val catalog = loadCatalog()

        catalog.phonemes.forEach { phoneme ->
            phoneme.contrastIds.forEach { id ->
                assertNotNull("${phoneme.ipa} 指向了不存在的对比 $id", catalog.contrast(id))
            }
        }
    }

    @Test
    fun `每个音位都有关键动作和例词`() {
        val catalog = loadCatalog()

        catalog.phonemes.forEach { phoneme ->
            assertTrue(
                "${phoneme.ipa} 没写关键动作——只给术语的说明对着镜子照不出来",
                phoneme.articulation.keyActionZh.isNotBlank(),
            )
            assertTrue("${phoneme.ipa} 没有例词", phoneme.exampleWords.isNotEmpty())
            assertTrue(phoneme.shortDescriptionZh.isNotBlank())
        }
    }

    @Test
    fun `常见误读四段齐全`() {
        val catalog = loadCatalog()

        catalog.phonemes.flatMap { it.commonErrors }.forEach { error ->
            assertTrue(error.substituteIpa.isNotBlank())
            assertTrue(error.exampleEn.isNotBlank())
            assertTrue(
                "${error.exampleEn} 没写会被听成什么——用户不知道后果就不会想改",
                error.soundsLikeEn.isNotBlank(),
            )
            assertTrue(
                "${error.exampleEn} 没写怎么改——用户不知道该动哪儿",
                error.fixZh.isNotBlank(),
            )
        }
    }

    @Test
    fun `目标 id 带类型前缀，音位和对比不会撞在一起`() {
        val phoneme = PronunciationTarget.ofPhoneme("th-voiceless")
        val contrast = PronunciationTarget.ofContrast("th-voiceless__s")

        assertTrue(PronunciationTarget.isPhoneme(phoneme))
        assertFalse(PronunciationTarget.isContrast(phoneme))
        assertTrue(PronunciationTarget.isContrast(contrast))
        assertEquals("th-voiceless", PronunciationTarget.rawId(phoneme))
        assertEquals("th-voiceless__s", PronunciationTarget.rawId(contrast))
    }

    private fun loadCatalog(): PhonemeCatalog {
        val payload = json.decodeFromString<PhonemeCatalogPayload>(assetFile().readText())
        return object : PhonemeCatalog {
            override val accent = payload.accent
            override val phonemes = payload.phonemes.sortedBy { it.displayOrder }
            override val contrasts = payload.contrasts
        }
    }

    /** 单测跑在 `app/` 目录下，assets 就在源码树里，不需要 Android 运行时。 */
    private fun assetFile(): File {
        val direct = File("src/main/assets/$ASSET")
        return if (direct.exists()) direct else File("app/src/main/assets/$ASSET")
    }

    private companion object {
        const val ASSET = "phonemes_en_us.json"
        const val NOW = 1_700_000_000_000L
        val json = Json { ignoreUnknownKeys = true }
    }
}
