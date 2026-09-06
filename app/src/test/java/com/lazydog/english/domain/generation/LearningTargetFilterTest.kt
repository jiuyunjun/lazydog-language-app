package com.lazydog.english.domain.generation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LearningTargetFilterTest {

    private fun target(term: String, label: String = "形容词 深思熟虑的", note: String = "") =
        LearningTargetSuggestion(term, label, note)

    @Test fun englishTargetsSurvive() {
        val kept = filterLearningTargets(
            listOf(target("deliberate"), target("well-thought-out"), target("on purpose")),
            isVocab = true,
        )
        assertEquals(listOf("deliberate", "well-thought-out", "on purpose"), kept.map { it.target })
    }

    /** 模型偶尔把用户那句中文原样退回来。挑中它只会拿中文去生成英文词卡，错得很安静。 */
    @Test fun chineseTargetIsDroppedForVocabulary() {
        val kept = filterLearningTargets(
            listOf(target("深思熟虑"), target("deliberate")),
            isVocab = true,
        )
        assertEquals(listOf("deliberate"), kept.map { it.target })
    }

    /** 语法不做这个限制：「现在完成时」本来就是合法的语法目标。 */
    @Test fun chineseTargetIsKeptForGrammar() {
        val kept = filterLearningTargets(
            listOf(target("现在完成时", label = "现在完成时")),
            isVocab = false,
        )
        assertEquals(listOf("现在完成时"), kept.map { it.target })
    }

    @Test fun blankAndDuplicateAndLabellessTargetsAreDropped() {
        val kept = filterLearningTargets(
            listOf(
                target("  "),
                target("deliberate"),
                target("Deliberate"),
                target("regret", label = "  "),
            ),
            isVocab = true,
        )
        assertEquals(listOf("deliberate"), kept.map { it.target })
    }

    @Test fun tooManyTargetsAreCappedSoTheUserPicksInsteadOfReads() {
        val many = listOf(
            "alpha", "bravo", "charlie", "delta", "echo", "foxtrot", "golf", "hotel",
            "india", "juliet", "kilo", "lima",
        ).map { target(it) }
        assertEquals(MAX_LEARNING_TARGETS, filterLearningTargets(many, isVocab = true).size)
    }

    /** 数字不是词形。「plan B」这种要么模型写成 plan b，要么它本来就不该当学习目标。 */
    @Test fun digitsAreNotAWordForm() {
        assertTrue(filterLearningTargets(listOf(target("word1")), isVocab = true).isEmpty())
    }

    @Test fun surroundingWhitespaceIsTrimmedBeforeItReachesGeneration() {
        val kept = filterLearningTargets(listOf(target("  deliberate  ", note = " 强调想过 ")), isVocab = true)
        assertEquals("deliberate", kept.single().target)
        assertEquals("强调想过", kept.single().noteZh)
    }

    @Test fun everythingRejectedLeavesNothingToPick() {
        assertTrue(filterLearningTargets(listOf(target("深思熟虑"), target("后悔")), isVocab = true).isEmpty())
    }
}
