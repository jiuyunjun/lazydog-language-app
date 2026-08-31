package com.lazydog.english.core.ai

import com.lazydog.english.domain.generation.GenerationResult
import com.lazydog.english.domain.generation.GrammarLessonRequest
import com.lazydog.english.domain.generation.NewWordsRequest
import com.lazydog.english.domain.listening.ListeningSetRequest
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ChatUrlTest {

    @Test
    fun `builds chat completions url tolerating trailing slash`() {
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            chatCompletionsUrl("https://api.openai.com/v1/"),
        )
    }
}

class ExtractJsonTest {

    @Test
    fun `passes plain json through`() {
        assertEquals("""{"a":1}""", extractJson("""{"a":1}"""))
    }

    @Test
    fun `strips markdown fences and surrounding prose`() {
        val content = "好的，这是结果：\n```json\n{\"a\":1}\n```\n希望有帮助"
        assertEquals("""{"a":1}""", extractJson(content))
    }
}

class GrammarPromptTest {
    @Test
    fun `grammar prompt separates pattern summary and explanation`() {
        val prompt = OpenAiContentGenerator.buildGrammarPrompt(
            GrammarLessonRequest("A2-B1", focus = "将来计划", knownGrammar = emptyList()),
        )

        assertTrue(prompt.contains("patternEn 是唯一主标题"))
        assertTrue(prompt.contains("不得含中文"))
        assertTrue(prompt.contains("summaryZh"))
        assertTrue(prompt.contains("be going to + base verb"))
    }
}

class ListeningPromptTest {
    @Test
    fun `listening prompt carries the structured conditions instead of just a level`() {
        // §18：只说"生成一个 B1 句子"出来的就是教科书英语，训练不到真实语流。
        val prompt = OpenAiContentGenerator.buildListeningPrompt(
            ListeningSetRequest(
                sceneZh = "商务职场",
                subScenesZh = listOf("会议", "汇报"),
                count = 10,
                learnerLevel = "B1",
                topics = listOf("科技"),
            ),
        )

        assertTrue(prompt.contains("商务职场"))
        assertTrue(prompt.contains("会议、汇报"))
        assertTrue(prompt.contains("intentZh"))
        assertTrue(prompt.contains("registerZh"))
        assertTrue(prompt.contains("audioFeatures"))
        // §15：授权说不清就不要照抄真实台词。
        assertTrue(prompt.contains("不要照搬电影"))
    }
}

class AiLogTest {

    @Test
    fun `server error message is pulled out of the error envelope`() {
        val body = """{"error":{"message":"Unsupported parameter: 'max_tokens' is not supported with this model.",
                       "type":"invalid_request_error","param":"max_tokens","code":null}}"""
        assertEquals(
            "Unsupported parameter: 'max_tokens' is not supported with this model.",
            extractErrorMessage(body),
        )
    }

    @Test
    fun `a body without an error envelope is returned as is`() {
        assertEquals("502 Bad Gateway", extractErrorMessage("502 Bad Gateway"))
    }

    @Test
    fun `keys echoed back by the server never reach logcat`() {
        // AI_CONTRACTS §8：日志不得出现 Authorization 和密钥。
        val logged = AiLog.body("""{"error":{"message":"bad key sk-abcd1234efgh5678 with Bearer sk-zzzz9999"}}""")
        assertFalse(logged.contains("sk-abcd1234efgh5678"))
        assertFalse(logged.contains("sk-zzzz9999"))
        assertTrue(logged.contains("sk-***"))
    }

    @Test
    fun `long error bodies are truncated`() {
        val logged = AiLog.body("x".repeat(5000))
        assertTrue(logged.length < 600)
        assertTrue(logged.endsWith("（已截断）"))
    }
}

/** AI_CONTRACTS 契约测试：合法返回、缺字段、坏 JSON、限流重试。 */
class OpenAiContentGeneratorTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun generator() = OpenAiContentGenerator(
        config = { AiConfig(server.url("/v1").toString(), "test-key", "gpt-test") },
        retryDelayMs = 1,
    )

    private fun escape(content: String): String = content
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")

    private fun chatBody(content: String): String =
        """{"model":"gpt-test","choices":[{"message":{"role":"assistant","content":"${escape(content)}"}}]}"""

    private fun sseBody(vararg parts: String): String = buildString {
        parts.forEach { part ->
            append("data: {\"model\":\"gpt-test\",\"choices\":[{\"delta\":{\"content\":\"${escape(part)}\"}}]}\n\n")
        }
        append("data: [DONE]\n\n")
    }

    private val wordsJson =
        """{"schemaVersion":1,"words":[
           {"term":"curb","ipa":"/kɜːb/","pos":"v.","meaningZh":"控制","exampleEn":"The city tried to curb traffic.","exampleZh":"市政府想控制车流。","collocations":["curb traffic"],"memoryHintZh":"curb 本义是路缘石，把车流圈在路里，引申成控制、抑制。"},
           {"term":"","ipa":"","meaningZh":"","exampleEn":"","exampleZh":""}
        ]}"""

    private val wordsRequest = NewWordsRequest(5, "A2-B1", listOf("科技"), listOf("linger"))

    @Test
    fun `valid words pass and invalid entries are dropped`() = runBlocking {
        server.enqueue(MockResponse().setBody(chatBody(wordsJson)))

        val result = generator().generateNewWords(wordsRequest)

        val success = result as GenerationResult.Success
        assertEquals(listOf("curb"), success.data.map { it.term })
        assertEquals(1, success.droppedNotes.size)
        assertEquals("gpt-test", success.model)

        val recorded = server.takeRequest()
        assertEquals("/v1/chat/completions", recorded.path)
        assertEquals("Bearer test-key", recorded.getHeader("Authorization"))
        assertTrue(recorded.body.readUtf8().contains("json_object"))
    }

    @Test
    fun `malformed content json fails without polluting anything`() = runBlocking {
        server.enqueue(MockResponse().setBody(chatBody("这不是 JSON")))

        val result = generator().generateNewWords(wordsRequest)

        assertTrue(result is GenerationResult.Failure)
    }

    @Test
    fun `wrong schema version is rejected`() = runBlocking {
        server.enqueue(MockResponse().setBody(chatBody("""{"schemaVersion":2,"words":[]}""")))

        val result = generator().generateNewWords(wordsRequest)

        val failure = result as GenerationResult.Failure
        assertTrue(failure.reason.contains("schema"))
    }

    @Test
    fun `retries once on 429 then succeeds`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(MockResponse().setBody(chatBody(wordsJson)))

        val result = generator().generateNewWords(wordsRequest)

        assertTrue(result is GenerationResult.Success)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `non retryable error fails immediately`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))

        val result = generator().generateNewWords(wordsRequest)

        val failure = result as GenerationResult.Failure
        assertTrue(failure.reason.contains("401"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `streaming accumulates deltas and reports progress`() = runBlocking {
        val half = wordsJson.length / 2
        server.enqueue(
            MockResponse().setBody(sseBody(wordsJson.substring(0, half), wordsJson.substring(half))),
        )

        val progress = mutableListOf<Int>()
        val result = generator().generateNewWords(wordsRequest) { progress.add(it) }

        val success = result as GenerationResult.Success
        assertEquals(listOf("curb"), success.data.map { it.term })
        assertEquals(2, progress.size)
        assertTrue(progress.last() > progress.first())

        val recorded = server.takeRequest()
        assertTrue(recorded.body.readUtf8().contains("\"stream\":true"))
    }

    @Test
    fun `reading with missing review word is rejected`() = runBlocking {
        val readingJson =
            """{"schemaVersion":1,"title":"T","body":"${"word ".repeat(60)}","estimatedCefr":"A2",
               "targetVocabulary":[],"targetGrammar":[],
               "comprehensionQuestions":[{"promptZh":"?","options":["A","B"],"answerIndex":0,"explanationZh":"e"}]}"""
        server.enqueue(MockResponse().setBody(chatBody(readingJson)))

        val result = generator().generateReading(
            com.lazydog.english.domain.generation.ReadingGenerationRequest(
                learnerLevel = "A2",
                topic = "科技",
                targetLength = 100,
                reviewVocabulary = listOf("curb"),
                knownVocabulary = emptyList(),
                reviewGrammar = emptyList(),
                maxNewWords = 4,
            ),
        )

        val failure = result as GenerationResult.Failure
        assertTrue(failure.reason.contains("curb"))
    }

    @Test
    fun `word explanation parses`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                chatBody("""{"term":"curb","ipa":"/kɜːb/","meaningZh":"v. 控制","usageNoteZh":"这里指控制车流。"}"""),
            ),
        )

        val result = generator().explainWord("curb", "The city tried to curb traffic.", "A2")

        val success = result as GenerationResult.Success
        assertEquals("v. 控制", success.data.meaningZh)
    }

    @Test
    fun `word explanation streams accumulated content`() = runBlocking {
        val json = """{"term":"curb","ipa":"/kɜːb/","meaningZh":"v. 控制","usageNoteZh":"这里指控制车流。"}"""
        val half = json.length / 2
        server.enqueue(MockResponse().setBody(sseBody(json.substring(0, half), json.substring(half))))

        val progress = mutableListOf<String>()
        val result = generator().explainWord("curb", "We should curb traffic.", "A2") { progress += it }

        assertTrue(result is GenerationResult.Success)
        assertEquals(2, progress.size)
        assertEquals(json, progress.last())
        assertTrue(server.takeRequest().body.readUtf8().contains("\"stream\":true"))
    }

    @Test
    fun `grammar lesson parses and validates`() = runBlocking {
        val lessonJson =
            """{"schemaVersion":1,"patternEn":"had + past participle","labelZh":"过去完成时","summaryZh":"表示过去某时之前已完成的动作",
               "explanationZh":"用于说明过去的过去。","goodExampleEn":"I had left before she arrived.",
               "goodExampleZh":"她到之前我已经走了。","badExampleEn":"I left before she had arrived.",
               "badExampleNoteZh":"先后关系反了。","tipZh":"先发生的用 had done。"}"""
        server.enqueue(MockResponse().setBody(chatBody(lessonJson)))

        val result = generator().generateGrammarLesson(
            GrammarLessonRequest("A2-B1", focus = null, knownGrammar = listOf("现在完成时")),
        )

        val success = result as GenerationResult.Success
        assertEquals("had + past participle", success.data.patternEn)
        assertEquals("表示过去某时之前已完成的动作", success.data.summaryZh)
    }

    @Test
    fun `known grammar point is rejected by validation`() = runBlocking {
        val lessonJson =
            """{"schemaVersion":1,"patternEn":"have/has + past participle","labelZh":"现在完成时","summaryZh":"表示过去动作与现在有关",
               "explanationZh":"动作发生在过去，但结果与现在有关。","goodExampleEn":"I have done it.","goodExampleZh":"我做完了。",
               "badExampleEn":"","badExampleNoteZh":"","tipZh":""}"""
        server.enqueue(MockResponse().setBody(chatBody(lessonJson)))

        val result = generator().generateGrammarLesson(
            GrammarLessonRequest("A2-B1", focus = null, knownGrammar = listOf("have/has + past participle")),
        )

        assertTrue(result is GenerationResult.Failure)
    }

    @Test
    fun `assessment reading question without passage is dropped, others pass`() = runBlocking {
        val assessmentJson =
            """{"schemaVersion":1,"questions":[
                 {"skill":"vocab","prompt":"He was ___ to help.","options":["reluctant","eager","vivid"],"answerIndex":0,"explanationZh":"e"},
                 {"skill":"reading","prompt":"Where did it happen?","options":["A","B","C"],"answerIndex":1,"explanationZh":"e","passage":"A short story about a trip."}
               ]}"""
        server.enqueue(MockResponse().setBody(chatBody(assessmentJson)))

        val result = generator().generateAssessmentQuestions("B1", 2, listOf("旅行"))

        val success = result as GenerationResult.Success
        assertEquals(2, success.data.size)
        assertTrue(success.data.any { it.skill == "reading" && it.passage != null })
    }

    @Test
    fun `deep reading with duplicate tags is rejected`() = runBlocking {
        val readingJson =
            """{"schemaVersion":1,"passage":"${"word ".repeat(250)}",
               "questions":[
                 {"tag":"main_idea","prompt":"p1","options":["a","b","c"],"answerIndex":0,"explanationZh":"e"},
                 {"tag":"main_idea","prompt":"p2","options":["a","b","c"],"answerIndex":0,"explanationZh":"e"},
                 {"tag":"inference","prompt":"p3","options":["a","b","c"],"answerIndex":0,"explanationZh":"e"},
                 {"tag":"vocab_reference","prompt":"p4","options":["a","b","c"],"answerIndex":0,"explanationZh":"e"}
               ]}"""
        server.enqueue(MockResponse().setBody(chatBody(readingJson)))

        val result = generator().generateDeepReading("B1", listOf("科技"))

        val failure = result as GenerationResult.Failure
        assertTrue(failure.reason.contains("主旨") || failure.reason.contains("main_idea"))
    }

    @Test
    fun `deep reading with four distinct tags passes`() = runBlocking {
        val readingJson =
            """{"schemaVersion":1,"passage":"${"word ".repeat(250)}",
               "questions":[
                 {"tag":"main_idea","prompt":"p1","options":["a","b","c"],"answerIndex":0,"explanationZh":"e"},
                 {"tag":"detail","prompt":"p2","options":["a","b","c"],"answerIndex":0,"explanationZh":"e"},
                 {"tag":"inference","prompt":"p3","options":["a","b","c"],"answerIndex":0,"explanationZh":"e"},
                 {"tag":"vocab_reference","prompt":"p4","options":["a","b","c"],"answerIndex":0,"explanationZh":"e"}
               ]}"""
        server.enqueue(MockResponse().setBody(chatBody(readingJson)))

        val result = generator().generateDeepReading("B1", listOf("科技"))

        val success = result as GenerationResult.Success
        assertEquals(4, success.data.questions.size)
    }

    @Test
    fun `expression rubric parses five dimensions with evidence`() = runBlocking {
        val rubricJson =
            """{"dimensions":[
                 {"dimension":"task_completion","score":3,"evidenceZh":["回答了要求的三点"]},
                 {"dimension":"organization","score":3,"evidenceZh":["顺序清楚"]},
                 {"dimension":"grammar_control","score":2,"evidenceZh":["有时态错误但不影响理解"]},
                 {"dimension":"vocabulary","score":3,"evidenceZh":["用词自然"]},
                 {"dimension":"pragmatics","score":3,"evidenceZh":["语气得体"]}
               ]}"""
        server.enqueue(MockResponse().setBody(chatBody(rubricJson)))

        val result = generator().evaluateExpressionRubric("写一写你的旅行", "I traveled to Kyoto last spring.", null)

        val success = result as GenerationResult.Success
        assertEquals(14, success.data.total)
    }

    @Test
    fun `expression rubric missing a dimension fails`() = runBlocking {
        val rubricJson =
            """{"dimensions":[
                 {"dimension":"task_completion","score":3,"evidenceZh":["e"]}
               ]}"""
        server.enqueue(MockResponse().setBody(chatBody(rubricJson)))

        val result = generator().evaluateExpressionRubric("task", "text", "B1")

        assertTrue(result is GenerationResult.Failure)
    }

    private fun sampleFeedback() = com.lazydog.english.domain.speaking.PronunciationFeedback(
        recognizedText = "The smell of coffee lingered in the kitchen.",
        accuracyScore = 78,
        fluencyScore = 82,
        completenessScore = 100,
        pronunciationScore = 80,
        words = listOf(
            com.lazydog.english.domain.speaking.WordFeedback(
                "kitchen", 45, com.lazydog.english.domain.speaking.WordErrorType.Mispronunciation,
            ),
        ),
    )

    @Test
    fun `pronunciation tips parse and drop unknown kind`() = runBlocking {
        val tipsJson =
            """{"tips":[
                 {"kind":"good","titleZh":"整句听得懂","bodyZh":"节奏也挺稳。"},
                 {"kind":"attention","titleZh":"kitchen 读音不太准","bodyZh":"重音在前面那一节。"},
                 {"kind":"excellent","titleZh":"x","bodyZh":"y"}
               ]}"""
        server.enqueue(MockResponse().setBody(chatBody(tipsJson)))

        val result = generator().explainPronunciation("The smell of coffee lingered in the kitchen.", sampleFeedback())

        val success = result as GenerationResult.Success
        assertEquals(2, success.data.size)
    }

    @Test
    fun `pronunciation tips with no valid entries fails`() = runBlocking {
        server.enqueue(MockResponse().setBody(chatBody("""{"tips":[]}""")))

        val result = generator().explainPronunciation("text", sampleFeedback())

        assertTrue(result is GenerationResult.Failure)
    }

    @Test
    fun `correction item parses when the correction actually changes the sentence`() = runBlocking {
        val itemJson =
            """{"incorrectSentence":"She go to school every day.",
               "referenceCorrection":"She goes to school every day.","explanationZh":"第三人称单数要加 s。"}"""
        server.enqueue(MockResponse().setBody(chatBody(itemJson)))

        val result = generator().generateCorrectionItem("B1", listOf("日常"))

        val success = result as GenerationResult.Success
        assertEquals("She goes to school every day.", success.data.referenceCorrection)
    }

    @Test
    fun `correction item with a no-op correction fails`() = runBlocking {
        val itemJson =
            """{"incorrectSentence":"She goes to school every day.",
               "referenceCorrection":"She goes to school every day.","explanationZh":"e"}"""
        server.enqueue(MockResponse().setBody(chatBody(itemJson)))

        val result = generator().generateCorrectionItem("B1", emptyList())

        assertTrue(result is GenerationResult.Failure)
    }

    private val listeningRequest =
        ListeningSetRequest("商务职场", listOf("会议"), count = 10, learnerLevel = "B1", topics = emptyList())

    /** 六句合法 + 一句关键表达不在句子里，凑够开局下限，坏的那句要被丢掉。 */
    private val listeningJson: String
        get() {
            val good = (1..6).joinToString(",") { i ->
                """{"textEn":"I barely made it to the $i o'clock meeting on time.",
                   "meaningZh":"我勉强准时赶到了第 $i 场会议","subSceneZh":"会议","intentZh":"解释",
                   "toneZh":"Nervous","registerZh":"口语","cefr":"B1","listeningDifficulty":3,
                   "audioFeatures":["linking","reduction"],
                   "keyExpression":{"en":"barely made it","meaningZh":"差一点没赶上"},
                   "wrongMeaningsZh":["我提前参加了第 $i 场会议","我没有参加第 $i 场会议"],
                   "sceneHintZh":"这句和迟到、赶时间有关","keywordHintZh":"注意听 barely"}"""
            }
            val bad =
                """{"textEn":"We should probably push the deadline by a couple of days.",
                   "meaningZh":"我们大概得把截止日往后推两天","subSceneZh":"排期","intentZh":"建议",
                   "toneZh":"Neutral","registerZh":"口语","cefr":"B1","listeningDifficulty":3,
                   "audioFeatures":["reduction"],
                   "keyExpression":{"en":"call it off","meaningZh":"取消"},
                   "wrongMeaningsZh":["我们应该提前交","我们应该取消这个项目"],
                   "sceneHintZh":"和时间安排有关","keywordHintZh":"注意听 push"}"""
            return """{"schemaVersion":1,"items":[$good,$bad]}"""
        }

    @Test
    fun `listening set drops the item whose key expression is missing from the sentence`() = runBlocking {
        server.enqueue(MockResponse().setBody(chatBody(listeningJson)))

        val result = generator().generateListeningSet(listeningRequest)

        val success = result as GenerationResult.Success
        assertEquals(6, success.data.size)
        assertEquals(1, success.droppedNotes.size)
        assertTrue(success.droppedNotes.single().contains("重点表达不在句子里"))
    }

    @Test
    fun `too few usable listening sentences fails instead of opening a short round`() = runBlocking {
        val onlyOne =
            """{"schemaVersion":1,"items":[
               {"textEn":"I barely made it to the meeting on time.","meaningZh":"我勉强准时赶到了会议",
                "subSceneZh":"会议","intentZh":"解释","toneZh":"Nervous","registerZh":"口语","cefr":"B1",
                "listeningDifficulty":3,"audioFeatures":["linking"],
                "keyExpression":{"en":"barely made it","meaningZh":"差一点没赶上"},
                "wrongMeaningsZh":["我提前到了","我没到"],
                "sceneHintZh":"和迟到有关","keywordHintZh":"注意听 barely"}]}"""
        server.enqueue(MockResponse().setBody(chatBody(onlyOne)))

        val result = generator().generateListeningSet(listeningRequest)

        val failure = result as GenerationResult.Failure
        assertTrue(failure.reason.contains("太少"))
    }

    @Test
    fun `listening set with a wrong schema version is rejected`() = runBlocking {
        server.enqueue(MockResponse().setBody(chatBody("""{"schemaVersion":9,"items":[]}""")))

        val result = generator().generateListeningSet(listeningRequest)

        assertTrue((result as GenerationResult.Failure).reason.contains("schema"))
    }

    @Test
    fun `every request carries an output cap`() = runBlocking {
        // 不带 max_tokens 的话服务端没有停下来的理由，模型转起圈来就一路计费。
        server.enqueue(MockResponse().setBody(chatBody(listeningJson)))

        generator().generateListeningSet(listeningRequest)

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"max_tokens\":${OpenAiContentGenerator.LISTENING_MAX_TOKENS}"))
    }

    @Test
    fun `a stream that never stops is cut off instead of billed forever`() = runBlocking {
        // readTimeout 拦不住这种：它是每次读的超时，只要一直有数据就一直被重置。
        val chunk = "x".repeat(1000)
        val parts = Array(OpenAiContentGenerator.MAX_RESPONSE_CHARS / 1000 + 5) { chunk }
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sseBody(*parts)),
        )

        var lastReported = 0
        val result = generator().generateListeningSet(listeningRequest) { lastReported = it }

        val failure = result as GenerationResult.Failure
        assertTrue(failure.reason.contains("一直没停"))
        assertTrue(
            "掐断点不该超过上限太多，实际报到 $lastReported",
            lastReported <= OpenAiContentGenerator.MAX_RESPONSE_CHARS,
        )
    }

    @Test
    fun `a 400 says what the server actually complained about`() = runBlocking {
        // 之前只报"HTTP 400"，等于什么都没说。
        server.enqueue(
            MockResponse().setResponseCode(400).setBody(
                """{"error":{"message":"Invalid value for 'temperature'.","type":"invalid_request_error"}}""",
            ),
        )

        val result = generator().generateListeningSet(listeningRequest)

        val failure = result as GenerationResult.Failure
        assertTrue(failure.reason.contains("400"))
        assertTrue(failure.reason.contains("Invalid value for 'temperature'."))
    }

    @Test
    fun `a model that rejects max_tokens is retried with max_completion_tokens`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(400).setBody(
                """{"error":{"message":"Unsupported parameter: 'max_tokens' is not supported with this model.",
                   "param":"max_tokens"}}""",
            ),
        )
        server.enqueue(MockResponse().setBody(chatBody(listeningJson)))

        val result = generator().generateListeningSet(listeningRequest)

        assertTrue(result is GenerationResult.Success)
        val first = server.takeRequest().body.readUtf8()
        val second = server.takeRequest().body.readUtf8()
        assertTrue(first.contains("\"max_tokens\""))
        assertFalse(first.contains("max_completion_tokens"))
        assertTrue(second.contains("\"max_completion_tokens\":${OpenAiContentGenerator.LISTENING_MAX_TOKENS}"))
        // 换名重发只做一次，别把一个 400 变成来回打
        assertFalse(second.contains("\"max_tokens\":"))
    }

    @Test
    fun `a 400 unrelated to the token limit is not retried`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(400)
                .setBody("""{"error":{"message":"model not found"}}"""),
        )

        val result = generator().generateListeningSet(listeningRequest)

        assertTrue(result is GenerationResult.Failure)
        assertEquals(1, server.requestCount)
    }
}
