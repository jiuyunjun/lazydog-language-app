package com.lazydog.english.domain.scenario

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ScenarioValidationTest {

    @Test
    fun `valid brief has four to six goals and four reply options`() {
        assertNull(ScenarioValidation.brief(validBrief()))
    }

    @Test
    fun `brief rejects goal count outside product rule`() {
        val invalid = validBrief().copy(goals = validBrief().goals.take(3))
        assertEquals("完成清单必须有 4～6 条", ScenarioValidation.brief(invalid))
    }

    @Test
    fun `judgement rejects goal ids that do not belong to brief`() {
        val result = ScenarioValidation.judgement(
            ScenarioJudgement(setOf("invented"), null),
            validBrief().goals.map { it.id }.toSet(),
        )
        assertEquals("判定返回了未知目标", result)
    }

    @Test
    fun `communication failure requires repair details`() {
        val result = ScenarioValidation.judgement(
            ScenarioJudgement(
                emptySet(),
                CommunicationFailure(heardAsZh = "", explanationZh = "走反了", suggestedRewriteEn = "Try again"),
            ),
            validBrief().goals.map { it.id }.toSet(),
        )
        assertNotNull(result)
    }

    @Test
    fun `summary requires exactly three improvements`() {
        val improvement = ScenarioImprovement(
            turn = 1,
            titleZh = "把要求说具体",
            originalEn = "Can you do something?",
            improvedEn = "Could you refund the price difference?",
            reasonZh = "具体要求更容易得到回应",
            replayContextZh = "对方刚拒绝了你",
            opponentLineEn = "There is nothing I can do.",
            promptZh = "给出具体方案",
            phraseHints = listOf("Could you…"),
        )
        val summary = ScenarioSummary(
            outcomeTitleZh = "谈完了",
            overviewZh = "你把主要意思说明白了。",
            improvements = listOf(improvement, improvement),
            keepPhrases = listOf(ScenarioKeepPhrase("Could you help me?", "你能帮我吗？")),
        )
        assertEquals("总结必须固定给三条改进", ScenarioValidation.summary(summary, userTurnCount = 2))
    }

    private fun validBrief() = ScenarioBrief(
        scenarioId = "hotel-wrong-room",
        titleZh = "酒店房型错了",
        situationZh = "要求升级或退差价。",
        opponentName = "Daniel",
        opponentRoleZh = "前台经理",
        opponentPersonalityZh = "礼貌但不松口。",
        goals = (1..5).map { ScenarioGoal("goal-$it", "目标 $it") },
        difficulty = ScenarioDifficulty(2, 2, 2, true, false),
        openingLineEn = "How can I help?",
        openingSubtextZh = "他在等你说明情况。",
        initialReplyOptions = (1..4).map { ScenarioReplyOption("Reply $it", "回复 $it") },
    )
}
