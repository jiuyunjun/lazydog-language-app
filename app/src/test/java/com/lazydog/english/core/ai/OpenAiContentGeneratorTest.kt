package com.lazydog.english.core.ai

import com.lazydog.english.domain.generation.GenerationResult
import com.lazydog.english.domain.generation.GrammarLessonRequest
import com.lazydog.english.domain.generation.NewWordsRequest
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
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
}
