package com.lazydog.english.core.ai

import com.lazydog.english.domain.scenario.ScenarioBrief
import com.lazydog.english.domain.scenario.ScenarioDifficulty
import com.lazydog.english.domain.scenario.ScenarioGoal
import com.lazydog.english.domain.scenario.ScenarioMessage
import com.lazydog.english.domain.scenario.ScenarioReplyOption
import com.lazydog.english.domain.scenario.ScenarioSpeaker
import com.lazydog.english.domain.scenario.ScenarioTurnRequest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScenarioPromptTest {

    @Test
    fun `role prompt explicitly forbids corrections and scoring`() {
        val prompt = OpenAiContentGenerator.buildScenarioTurnPrompt(request())
        assertTrue(prompt.contains("不要纠错"))
        assertTrue(prompt.contains("不要纠错、夸奖、评价或解释英语"))
        assertFalse(prompt.contains("achievedGoalIds"))
    }

    @Test
    fun `judge prompt only asks for goals and communication failure`() {
        val prompt = OpenAiContentGenerator.buildScenarioJudgePrompt(request())
        assertTrue(prompt.contains("achievedGoalIds"))
        assertTrue(prompt.contains("communicationFailure"))
        assertTrue(prompt.contains("不要返回语法、用词、自然度、建议或分数"))
        assertFalse(prompt.contains("replyOptions"))
    }

    private fun request() = ScenarioTurnRequest(
        brief = ScenarioBrief(
            scenarioId = "hotel-wrong-room",
            titleZh = "酒店房型错了",
            situationZh = "要求退差价。",
            opponentName = "Daniel",
            opponentRoleZh = "经理",
            opponentPersonalityZh = "不太合作",
            goals = (1..4).map { ScenarioGoal("g$it", "目标 $it") },
            difficulty = ScenarioDifficulty(2, 2, 2, false, false),
            openingLineEn = "What is the problem?",
            openingSubtextZh = "",
            initialReplyOptions = (1..4).map { ScenarioReplyOption("Reply $it", "回复 $it") },
        ),
        transcript = listOf(ScenarioMessage(0, ScenarioSpeaker.Opponent, "What is the problem?")),
        userReplyEn = "I booked a deluxe room.",
    )
}
