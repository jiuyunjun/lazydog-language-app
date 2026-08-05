package com.lazydog.english.core.ai

import com.lazydog.english.domain.generation.GenerationResult
import com.lazydog.english.domain.scenario.ScenarioBrief
import com.lazydog.english.domain.scenario.ScenarioDifficulty
import com.lazydog.english.domain.scenario.ScenarioGenerationRequest
import com.lazydog.english.domain.scenario.ScenarioGoal
import com.lazydog.english.domain.scenario.ScenarioMessage
import com.lazydog.english.domain.scenario.ScenarioReplyOption
import com.lazydog.english.domain.scenario.ScenarioSource
import com.lazydog.english.domain.scenario.ScenarioSpeaker
import com.lazydog.english.domain.scenario.ScenarioTurnRequest
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ScenarioGeneratorContractTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `valid scenario brief parses and keeps four goals`() = runBlocking {
        server.enqueue(chatResponse(validBriefJson))

        val result = generator().generateScenario(generationRequest()) as GenerationResult.Success

        assertEquals("hotel-wrong-room", result.data.scenarioId)
        assertEquals(4, result.data.goals.size)
        assertEquals(4, result.data.initialReplyOptions.size)
    }

    @Test
    fun `recent scenario id is rejected even when model repeats it`() = runBlocking {
        server.enqueue(chatResponse(validBriefJson))

        val result = generator().generateScenario(
            generationRequest().copy(excludedScenarioIds = setOf("hotel-wrong-room")),
        )

        assertTrue(result is GenerationResult.Failure)
    }

    @Test
    fun `judge rejects goal id outside scenario checklist`() = runBlocking {
        server.enqueue(
            chatResponse("""{"achievedGoalIds":["not-a-goal"],"communicationFailure":null}"""),
        )

        val result = generator().judgeScenarioTurn(turnRequest())

        assertTrue(result is GenerationResult.Failure)
    }

    private fun generator() = OpenAiContentGenerator(
        config = { AiConfig(server.url("/v1").toString(), "test-key", "gpt-test") },
        retryDelayMs = 1,
    )

    private fun generationRequest() = ScenarioGenerationRequest(
        source = ScenarioSource.Recommended,
        seedZh = "酒店房型错了",
        learnerLevel = "B1",
        learningGoal = "旅行沟通",
        topics = listOf("旅行"),
        difficulty = ScenarioDifficulty(2, 2, 2, true, false),
        excludedScenarioIds = emptySet(),
    )

    private fun turnRequest() = ScenarioTurnRequest(
        brief = ScenarioBrief(
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
        ),
        transcript = listOf(ScenarioMessage(0, ScenarioSpeaker.Opponent, "How can I help?")),
        userReplyEn = "I booked a deluxe room.",
    )

    private fun chatResponse(content: String): MockResponse {
        val escaped = content.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        return MockResponse().setBody(
            """{"model":"gpt-test","choices":[{"message":{"role":"assistant","content":"$escaped"}}]}""",
        )
    }

    private val validBriefJson =
        """{"schemaVersion":1,"scenarioId":"hotel-wrong-room","titleZh":"酒店房型错了",""" +
            """"situationZh":"要求升级或退差价。","opponentName":"Daniel","opponentRoleZh":"前台经理",""" +
            """"opponentPersonalityZh":"礼貌但不松口。","goals":[""" +
            (1..4).joinToString(",") { """{"id":"g$it","textZh":"目标 $it"}""" } +
            """],"openingLineEn":"How can I help?","openingSubtextZh":"他在等你说明情况。",""" +
            """"initialReplyOptions":[""" +
            (1..4).joinToString(",") { """{"en":"Reply $it","zh":"回复 $it"}""" } + "]}"
}
