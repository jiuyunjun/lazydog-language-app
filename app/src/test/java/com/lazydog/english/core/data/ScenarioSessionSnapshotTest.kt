package com.lazydog.english.core.data

import com.lazydog.english.domain.scenario.ScenarioBrief
import com.lazydog.english.domain.scenario.ScenarioDifficulty
import com.lazydog.english.domain.scenario.ScenarioGoal
import com.lazydog.english.domain.scenario.ScenarioMessage
import com.lazydog.english.domain.scenario.ScenarioReplyOption
import com.lazydog.english.domain.scenario.ScenarioSpeaker
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class ScenarioSessionSnapshotTest {
    @Test
    fun `conversation snapshot survives json round trip`() {
        val snapshot = ScenarioSessionSnapshot(
            brief = brief(),
            stage = ScenarioStage.Conversation,
            messages = listOf(
                ScenarioMessage(0, ScenarioSpeaker.Opponent, "How can I help?"),
                ScenarioMessage(1, ScenarioSpeaker.User, "I booked a deluxe room."),
            ),
            options = brief().initialReplyOptions,
            hint = "Could you…",
            replyMode = ScenarioReplyMode.Free,
            input = "I'd like",
            achievedGoals = mapOf("g1" to 1),
        )
        val json = Json { encodeDefaults = true }

        val decoded = json.decodeFromString(
            ScenarioSessionSnapshot.serializer(),
            json.encodeToString(ScenarioSessionSnapshot.serializer(), snapshot),
        )

        assertEquals(snapshot, decoded)
    }

    private fun brief() = ScenarioBrief(
        scenarioId = "hotel-wrong-room",
        titleZh = "酒店房型错了",
        situationZh = "要求升级或退差价。",
        opponentName = "Daniel",
        opponentRoleZh = "前台经理",
        opponentPersonalityZh = "礼貌但不松口。",
        goals = (1..4).map { ScenarioGoal("g$it", "目标 $it") },
        difficulty = ScenarioDifficulty(2, 2, 2, true, false),
        openingLineEn = "How can I help?",
        openingSubtextZh = "",
        initialReplyOptions = (1..4).map { ScenarioReplyOption("Reply $it", "回复 $it") },
    )
}
