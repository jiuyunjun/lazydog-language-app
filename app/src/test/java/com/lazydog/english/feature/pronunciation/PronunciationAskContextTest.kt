package com.lazydog.english.feature.pronunciation

import com.lazydog.english.domain.pronunciation.*
import org.junit.Assert.*
import org.junit.Test

class PronunciationAskContextTest {
    private val right = PerceptionOption("ship", "ʃɪp", true)
    private val wrong = PerceptionOption("sheep", "ʃiːp", false)
    private val question = PerceptionQuestion(
        targetId = "contrast", contrastId = "short-long", exercise = PerceptionExercise.MinimalPairTwo,
        promptWord = "ship", promptIpa = "ʃɪp", options = listOf(right, wrong),
        difficulty = PerceptionDifficulty.FixedPair, variantId = "ship-sheep",
        leftPhonemeId = "ih", rightPhonemeId = "iy",
    )

    @Test fun `no answer and no loaded question never expose the target word`() {
        assertNull(perceptionAskContext(question, null))
        assertNull(perceptionAskContext(null, wrong))
    }

    @Test fun `after answering explanation has the actual choice and expected answer`() {
        val details = perceptionAskContext(question, wrong)!!.details.associate { it.label to it.value }
        assertEquals("sheep", details["我的选择"])
        assertEquals("ship", details["正确答案"])
    }
}
