package com.lazydog.english.core.ai

import com.lazydog.english.domain.generation.GenerationResult
import com.lazydog.english.domain.generation.GenerationStage
import com.lazydog.english.domain.generation.GrammarLessonRequest
import com.lazydog.english.domain.generation.MemoryAssistanceRequest
import com.lazydog.english.domain.generation.MemoryType
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
                excludedSentences = listOf("We've already covered that in the meeting."),
            ),
        )

        assertTrue(prompt.contains("商务职场"))
        assertTrue(prompt.contains("会议、汇报"))
        assertTrue(prompt.contains("intentZh"))
        assertTrue(prompt.contains("registerZh"))
        assertTrue(prompt.contains("audioFeatures"))
        // 干扰项类型必须是封闭集合，否则"你栽在哪一类"没法聚合。
        assertTrue(prompt.contains("mishearType"))
        assertTrue(prompt.contains("similar_scene"))
        // §15：授权说不清就不要照抄真实台词。
        assertTrue(prompt.contains("不要照搬电影"))
        assertTrue(prompt.contains("<heard_sentences>"))
        assertTrue(prompt.contains("We've already covered that in the meeting."))
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
        // 真机上候选由偏好和 AiTask 算好后传进来（见 LazyDogApplication），这里照搬那套算法。
        config = { task -> AiConfig(server.url("/v1").toString(), "test-key", "gpt-test", effortCandidates = AiTask.effortCandidates(task)) },
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
           {"term":"curb","ipa":"/kɜːb/","pos":"v.","meaningZh":"控制","exampleEn":"The city tried to curb traffic.","exampleZh":"市政府想控制车流。","collocations":[{"en":"curb traffic","zh":"控制车流"}],"memoryHintZh":"curb 本义是路缘石，把车流圈在路里，引申成控制、抑制。","chunks":["cu","rb"],"trickyPart":"ur","misspellings":["curbe","kurb","curp"]},
           {"term":"","ipa":"","meaningZh":"","exampleEn":"","exampleZh":""}
        ]}"""

    private val wordsRequest = NewWordsRequest(5, "A2-B1", listOf("科技"), listOf("linger"))

    @Test
    fun `valid words pass and invalid entries are dropped`() = runBlocking {
        server.enqueue(MockResponse().setBody(chatBody(wordsJson)))

        val result = generator().generateNewWords(wordsRequest)

        val success = result as GenerationResult.Success
        assertEquals(listOf("curb"), success.data.map { it.term })
        assertEquals(listOf("curb traffic"), success.data.first().collocations.map { it.en })
        assertEquals(listOf("控制车流"), success.data.first().collocations.map { it.zh })
        assertEquals(1, success.droppedNotes.size)
        assertEquals("gpt-test", success.model)

        val recorded = server.takeRequest()
        assertEquals("/v1/chat/completions", recorded.path)
        assertEquals("Bearer test-key", recorded.getHeader("Authorization"))
        assertTrue(recorded.body.readUtf8().contains("json_object"))
    }

    @Test
    fun `collocations given as plain strings still parse`() = runBlocking {
        // 模型偶尔还是照老样子给一串裸字符串。那是“只有英文、还没翻译”，
        // 不该让十几个词连同拼写事实一起白生成。
        server.enqueue(MockResponse().setBody(chatBody(wordsJson.replace(
            """[{"en":"curb traffic","zh":"控制车流"}]""",
            """["curb traffic"]""",
        ))))

        val success = generator().generateNewWords(wordsRequest) as GenerationResult.Success

        assertEquals(listOf("curb traffic"), success.data.first().collocations.map { it.en })
        assertEquals(listOf(""), success.data.first().collocations.map { it.zh })
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

        val stages = mutableListOf<GenerationStage>()
        val previews = mutableListOf<String>()
        val result = generator().generateNewWords(
            wordsRequest,
            onStage = { stages.add(it) },
            onPartialText = { previews.add(it) },
        )

        val success = result as GenerationResult.Success
        assertEquals(listOf("curb"), success.data.map { it.term })
        // 等待期间铺出去的是已经写好的词本身，不是一个字符数。
        assertTrue(previews.last().contains("curb"))
        val written = stages.filterIsInstance<GenerationStage.Writing>()
        assertEquals(2, written.size)
        assertTrue(written.last().chars > written.first().chars)
        // 响应头一到就先报一次"在等模型开口"，界面据此离开"接通中"。
        assertTrue(stages.first() is GenerationStage.Thinking)

        val recorded = server.takeRequest()
        assertTrue(recorded.body.readUtf8().contains("\"stream\":true"))
    }

    @Test
    fun `reading with missing review word is rejected`() = runBlocking {
        val readingJson =
            """{"schemaVersion":1,"title":"T","body":"${"word ".repeat(60)}",
               "readerPayoff":"Small changes to waiting can matter more than speed.","estimatedCefr":"A2",
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
            """{"schemaVersion":1,"patternEn":"had + past participle","category":"PAST","labelZh":"过去完成时","summaryZh":"表示过去某时之前已完成的动作",
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
            """{"schemaVersion":1,"patternEn":"have/has + past participle","category":"PRESENT","labelZh":"现在完成时","summaryZh":"表示过去动作与现在有关",
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
                   "distractors":[
                     {"meaningZh":"我提前参加了第 $i 场会议","mishearType":"keyword",
                      "whyZh":"barely 被听成了 early。"},
                     {"meaningZh":"我没能参加第 $i 场会议","mishearType":"negation",
                      "whyZh":"漏掉 made it 会以为事情没做成。"},
                     {"meaningZh":"第 $i 场会议准时结束了","mishearType":"similar_scene",
                      "whyZh":"只抓到 the meeting on time。"}],
                   "sceneHintZh":"这句和迟到、赶时间有关","keywordHintZh":"注意听 barely"}"""
            }
            val bad =
                """{"textEn":"We should probably push the deadline by a couple of days.",
                   "meaningZh":"我们大概得把截止日往后推两天","subSceneZh":"排期","intentZh":"建议",
                   "toneZh":"Neutral","registerZh":"口语","cefr":"B1","listeningDifficulty":3,
                   "audioFeatures":["reduction"],
                   "keyExpression":{"en":"call it off","meaningZh":"取消"},
                   "distractors":[
                     {"meaningZh":"我们应该提前交","mishearType":"tense","whyZh":"时态听反了。"},
                     {"meaningZh":"我们应该取消这个项目","mishearType":"keyword","whyZh":"push 听成了别的词。"},
                     {"meaningZh":"截止日已经过了","mishearType":"similar_scene","whyZh":"只抓到 deadline。"}],
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
    fun `listening sentences are handed over one at a time while the stream is still running`() = runBlocking {
        // 界面靠这个提前开练：等整批闭合是几十秒，第一句闭合时就该能听了。
        val chunks = listeningJson.chunked(40).toTypedArray()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sseBody(*chunks)),
        )

        val delivered = mutableListOf<String>()
        val result = generator().generateListeningSet(
            request = listeningRequest,
            onItem = { delivered.add(it.textEn) },
        )

        val success = result as GenerationResult.Success
        // 流式发出去的和最后返回的是同一批，顺序也一致——不能出现"听过的句子又来一遍"。
        assertEquals(success.data.map { it.textEn }, delivered)
        assertEquals(6, delivered.size)
        assertTrue(success.droppedNotes.single().contains("重点表达不在句子里"))
    }

    @Test
    fun `reasoning deltas are reported as thinking, not as an empty connection`() = runBlocking {
        // 推理模型开口前会先想一阵。这段以前全被当成"还没接通"，界面一动不动。
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    """data: {"model":"gpt-test","choices":[{"delta":{"content":null,""" +
                        """"reasoning_content":"先想想会议场景"}}]}

data: {"model":"gpt-test","choices":[{"delta":{"reasoning_content":"再挑难听的连读"}}]}

""" + sseBody(listeningJson),
                ),
        )

        val stages = mutableListOf<GenerationStage>()
        val result = generator().generateListeningSet(listeningRequest, onStage = { stages.add(it) })

        assertTrue(result is GenerationResult.Success)
        val thinking = stages.filterIsInstance<GenerationStage.Thinking>()
        // 响应头一到就先报一次"在等模型开口"，之后每段思考再报一次。
        assertTrue("$stages", thinking.size >= 3)
        assertTrue(thinking.last().excerpt.contains("再挑难听的连读"))
        // 正文一开始写就换成 Writing，不能一直停在思考态。
        assertTrue(stages.last() is GenerationStage.Writing)
    }

    @Test
    fun `a chunk whose content is null does not break the stream`() = runBlocking {
        // 不少服务商的第一块是 {"role":"assistant","content":null}，声明成非空会让整块解析失败。
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    """data: {"model":"gpt-test","choices":[{"delta":{"role":"assistant","content":null}}]}

""" + sseBody(listeningJson),
                ),
        )

        val result = generator().generateListeningSet(listeningRequest, onItem = {})

        assertEquals(6, (result as GenerationResult.Success).data.size)
    }

    @Test
    fun `too few usable listening sentences fails instead of opening a short round`() = runBlocking {
        val onlyOne =
            """{"schemaVersion":1,"items":[
               {"textEn":"I barely made it to the meeting on time.","meaningZh":"我勉强准时赶到了会议",
                "subSceneZh":"会议","intentZh":"解释","toneZh":"Nervous","registerZh":"口语","cefr":"B1",
                "listeningDifficulty":3,"audioFeatures":["linking"],
                "keyExpression":{"en":"barely made it","meaningZh":"差一点没赶上"},
                "distractors":[
                  {"meaningZh":"我提前到了","mishearType":"keyword","whyZh":"barely 听成了 early。"},
                  {"meaningZh":"我没到","mishearType":"negation","whyZh":"漏掉 made it。"},
                  {"meaningZh":"会议准时结束","mishearType":"similar_scene","whyZh":"只抓到 on time。"}],
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
        val result = generator().generateListeningSet(
            request = listeningRequest,
            onStage = { if (it is GenerationStage.Writing) lastReported = it.chars },
        )

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
        // 两次是因为带着 reasoning_effort 挨 400 时会先去掉它重试一次，见上面那条用例。
        repeat(2) {
            server.enqueue(
                MockResponse().setResponseCode(400).setBody(
                    """{"error":{"message":"Invalid value for 'temperature'.","type":"invalid_request_error"}}""",
                ),
            )
        }

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
    fun `each call asks for the model configured for that feature`() = runBlocking {
        // 听力值得用最强的模型，点词讲解要的是马上出字——一个全局模型没法同时满足两头。
        val asked = mutableListOf<AiTask>()
        val generator = OpenAiContentGenerator(
            config = { task ->
                asked += task
                AiConfig(server.url("/v1").toString(), "test-key", "model-for-${task.key}")
            },
            retryDelayMs = 1,
        )
        server.enqueue(MockResponse().setBody(chatBody(listeningJson)))
        server.enqueue(
            MockResponse().setBody(
                chatBody("""{"term":"curb","ipa":"/kɜːb/","meaningZh":"v. 控制","usageNoteZh":"控制车流。"}"""),
            ),
        )

        generator.generateListeningSet(listeningRequest)
        generator.explainWord("curb", "We should curb traffic.", "A2")

        assertEquals(listOf(AiTask.Listening, AiTask.Explain), asked)
        assertTrue(server.takeRequest().body.readUtf8().contains("\"model-for-listening\""))
        assertTrue(server.takeRequest().body.readUtf8().contains("\"model-for-explain\""))
    }

    @Test
    fun `a model already known to need max_completion_tokens skips the wasted round trip`() = runBlocking {
        // 每次调用都先用 max_tokens 撞一个 400，等于每次都白搭一整个往返，
        // 而这一下全落在用户盯着"接通中"的那段时间里。
        val generator = OpenAiContentGenerator(
            config = { AiConfig(server.url("/v1").toString(), "test-key", "gpt-test", useCompletionTokens = true) },
            retryDelayMs = 1,
        )
        server.enqueue(MockResponse().setBody(chatBody(listeningJson)))

        val result = generator.generateListeningSet(listeningRequest)

        assertTrue(result is GenerationResult.Success)
        assertEquals(1, server.requestCount)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("max_completion_tokens"))
        assertFalse(body.contains("\"max_tokens\""))
    }

    @Test
    fun `the token field swap is reported so it can be remembered`() = runBlocking {
        val remembered = mutableListOf<String>()
        val generator = OpenAiContentGenerator(
            config = { AiConfig(server.url("/v1").toString(), "test-key", "gpt-test") },
            retryDelayMs = 1,
            onNeedsCompletionTokens = { remembered.add(it) },
        )
        server.enqueue(
            MockResponse().setResponseCode(400).setBody(
                """{"error":{"message":"Unsupported parameter: 'max_tokens'","param":"max_tokens"}}""",
            ),
        )
        server.enqueue(MockResponse().setBody(chatBody(listeningJson)))

        generator.generateListeningSet(listeningRequest)

        assertEquals(listOf("gpt-test"), remembered)
    }

    @Test
    fun `each task asks for its own reasoning effort`() = runBlocking {
        // 推理模型开口前的思考实测占了整次调用六成，而这些任务多数只是按模板填内容。
        server.enqueue(MockResponse().setBody(chatBody(listeningJson)))
        server.enqueue(
            MockResponse().setBody(
                chatBody("""{"term":"curb","ipa":"/kɜːb/","meaningZh":"v. 控制","usageNoteZh":"控制车流。"}"""),
            ),
        )

        generator().generateListeningSet(listeningRequest)
        generator().explainWord("curb", "We should curb traffic.", "A2")

        assertTrue(server.takeRequest().body.readUtf8().contains(""""reasoning_effort":"low""""))
        // 点词要马上出字，思考对它几乎没有增益；none 是文档里给延迟敏感任务准备的取值。
        assertTrue(server.takeRequest().body.readUtf8().contains(""""reasoning_effort":"none""""))
    }

    @Test
    fun `a rejected effort value falls back to the next candidate, not to the default`() = runBlocking {
        // 服务端只点名了取值、没点名参数（真实的 400 常是这样），一样要能记住。
        // gpt-5.6-terra 认 none 却不认 minimal，取值是模型相关的。这里的关键是**别退回默认值**：
        // 不带这个参数就是模型默认（多数是 medium），比我们想要的还慢——修一下反而更慢。
        val rejectedValues = mutableListOf<Pair<String, String>>()
        val generator = OpenAiContentGenerator(
            config = { task ->
                AiConfig(
                    server.url("/v1").toString(),
                    "test-key",
                    "gpt-test",
                    effortCandidates = AiTask.effortCandidates(task),
                )
            },
            retryDelayMs = 1,
            onRejectsEffortValue = { model, effort -> rejectedValues.add(model to effort) },
        )
        server.enqueue(
            MockResponse().setResponseCode(400).setBody(
                """{"error":{"message":"Unsupported value: 'none' is not supported with this model. Supported values are 'low', 'medium'."}}""",
            ),
        )
        server.enqueue(
            MockResponse().setBody(
                chatBody("""{"term":"curb","ipa":"/kɜːb/","meaningZh":"v. 控制","usageNoteZh":"控制车流。"}"""),
            ),
        )

        val result = generator.explainWord("curb", "We should curb traffic.", "A2")

        assertTrue(result is GenerationResult.Success)
        assertEquals(listOf("gpt-test" to "none"), rejectedValues)
        assertTrue(server.takeRequest().body.readUtf8().contains(""""reasoning_effort":"none""""))
        // 退到下一个候选，而不是把参数丢掉。low 几乎所有推理模型都认。
        assertTrue(server.takeRequest().body.readUtf8().contains(""""reasoning_effort":"low""""))
    }

    @Test
    fun `a model that rejects every candidate ends up sending none of them, and is remembered`() = runBlocking {
        val rejectedParam = mutableListOf<String>()
        val generator = OpenAiContentGenerator(
            config = { task ->
                AiConfig(
                    server.url("/v1").toString(),
                    "test-key",
                    "gpt-test",
                    effortCandidates = AiTask.effortCandidates(task),
                )
            },
            retryDelayMs = 1,
            onRejectsReasoningEffort = { rejectedParam.add(it) },
        )
        // 听力只有一个候选 low，撞掉就没了。
        server.enqueue(
            MockResponse().setResponseCode(400).setBody(
                """{"error":{"message":"Unrecognized request argument: reasoning_effort"}}""",
            ),
        )
        server.enqueue(MockResponse().setBody(chatBody(listeningJson)))

        val result = generator.generateListeningSet(listeningRequest)

        assertTrue(result is GenerationResult.Success)
        assertEquals(listOf("gpt-test"), rejectedParam)
        server.takeRequest()
        assertFalse(server.takeRequest().body.readUtf8().contains("reasoning_effort"))
    }

    @Test
    fun `candidates already known to be rejected are skipped`() = runBlocking {
        val generator = OpenAiContentGenerator(
            config = { task ->
                AiConfig(
                    server.url("/v1").toString(),
                    "test-key",
                    "gpt-test",
                    effortCandidates = AiTask.effortCandidates(task, rejected = setOf("none")),
                )
            },
            retryDelayMs = 1,
        )
        server.enqueue(
            MockResponse().setBody(
                chatBody("""{"term":"curb","ipa":"/kɜːb/","meaningZh":"v. 控制","usageNoteZh":"控制车流。"}"""),
            ),
        )

        generator.explainWord("curb", "We should curb traffic.", "A2")

        assertEquals(1, server.requestCount)
        assertTrue(server.takeRequest().body.readUtf8().contains(""""reasoning_effort":"low""""))
    }

    @Test
    fun `a task that wants the model default sends no effort at all`() = runBlocking {
        // medium 本来就是多数模型的默认值，显式再发一遍没意义，还多一个可能被拒的参数。
        server.enqueue(MockResponse().setBody(chatBody("""{"dimensions":[]}""")))

        generator().evaluateExpressionRubric("task", "text", null)

        assertFalse(server.takeRequest().body.readUtf8().contains("reasoning_effort"))
    }

    @Test
    fun `a model already known to reject reasoning effort never sends it`() = runBlocking {
        val generator = OpenAiContentGenerator(
            config = { AiConfig(server.url("/v1").toString(), "test-key", "gpt-test") },
            retryDelayMs = 1,
        )
        server.enqueue(MockResponse().setBody(chatBody(listeningJson)))

        generator.generateListeningSet(listeningRequest)

        assertEquals(1, server.requestCount)
        assertFalse(server.takeRequest().body.readUtf8().contains("reasoning_effort"))
    }

    @Test
    fun `waiting for the model starts when the request is sent, not when headers arrive`() = runBlocking {
        // 实测 gpt-5 系想完之前连响应头都不发（一次压住 49 秒）。等响应头才算"在等模型"的话，
        // 整个思考期界面上都会写着"接通中"——而真正的接通只有几十毫秒。
        server.enqueue(
            MockResponse()
                .setHeadersDelay(400, java.util.concurrent.TimeUnit.MILLISECONDS)
                .setBody(chatBody(listeningJson)),
        )

        val startedAt = System.currentTimeMillis()
        var firstThinkingAt = 0L
        generator().generateListeningSet(
            request = listeningRequest,
            onStage = { if (it is GenerationStage.Thinking && firstThinkingAt == 0L) firstThinkingAt = System.currentTimeMillis() },
        )

        assertTrue("一次 Thinking 都没报", firstThinkingAt > 0)
        val waited = firstThinkingAt - startedAt
        assertTrue("应该在响应头之前就报出来，实际等了 $waited ms", waited < 300)
    }

    @Test
    fun `an unrelated 400 gives up after exhausting the optional parameter`() = runBlocking {
        // 有的网关只回一句含糊的"参数不对"，认不出是哪个，所以先去掉 reasoning_effort 再试一次。
        // 但也就这一次——一个 400 不该变成来回打。
        server.enqueue(
            MockResponse().setResponseCode(400)
                .setBody("""{"error":{"message":"model not found"}}"""),
        )
        server.enqueue(
            MockResponse().setResponseCode(400)
                .setBody("""{"error":{"message":"model not found"}}"""),
        )

        val result = generator().generateListeningSet(listeningRequest)

        assertTrue(result is GenerationResult.Failure)
        assertEquals(2, server.requestCount)
        server.takeRequest()
        assertFalse(server.takeRequest().body.readUtf8().contains("reasoning_effort"))
    }

    @Test
    fun `an unrelated 400 with no optional parameter in play is not retried at all`() = runBlocking {
        val generator = OpenAiContentGenerator(
            config = { AiConfig(server.url("/v1").toString(), "test-key", "gpt-test") },
            retryDelayMs = 1,
        )
        server.enqueue(
            MockResponse().setResponseCode(400)
                .setBody("""{"error":{"message":"model not found"}}"""),
        )

        val result = generator.generateListeningSet(listeningRequest)

        assertTrue(result is GenerationResult.Failure)
        assertEquals(1, server.requestCount)
    }
}

/**
 * 记忆提示的契约测试（词汇记忆提示DESIGN.md §6）。
 * 重点在两件事：文档示例里那些 null 要能解析，以及"再来一条"确实带上了要避开的东西。
 */
class MemoryAssistanceGeneratorTest {

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

    private fun chatBody(content: String): String {
        val escaped = content.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\n")
        return """{"model":"gpt-test","choices":[{"message":{"role":"assistant","content":"$escaped"}}]}"""
    }

    private val request = MemoryAssistanceRequest(
        term = "purchase",
        meaningZh = "购买",
        pos = "v.",
        learnerLevel = "B1",
    )

    /** 文档 §6 的示例原样：morphology 和 note 是 null，confusions 只有一条。 */
    private val designExample =
        """{"schemaVersion":1,"word":"purchase","core_meaning":"购买",
           "primary_memory_type":"CONTEXT","secondary_memory_type":"CONTRAST",
           "memory_hook":"正式场合里的 buy","morphology":null,
           "spelling":{"weak_segment":"pur","common_errors":[]},
           "pronunciation":{"syllables":["pur","chase"],"stress":1,"note":null},
           "visual_association":"在店里柜台付款，把商品正式买下来。",
           "confusions":[{"word":"buy","difference":"buy 更日常，purchase 更正式"}],
           "collocations":["purchase equipment","purchase a ticket"],
           "example":"We need to purchase new equipment.",
           "recall_question":"正式表达「购买设备」时可以用哪个词？"}"""

    @Test
    fun `the design document's own example parses`() = runBlocking {
        server.enqueue(MockResponse().setBody(chatBody(designExample)))

        val success = generator().generateMemoryAssistance(request) as GenerationResult.Success

        assertEquals("purchase", success.data.term)
        assertEquals("正式场合里的 buy", success.data.memoryHookZh)
        assertEquals(MemoryType.Context, success.data.primaryType)
        assertEquals(MemoryType.Contrast, success.data.secondaryType)
        // morphology 是 null 不是缺字段：这个词本来就没有可靠构词可拆。
        assertEquals("", success.data.morphologyZh)
        assertEquals(listOf("pur", "chase"), success.data.pronunciation.syllables)
        assertEquals(1, success.data.confusions.size)
        assertTrue(success.data.hasDetails)
    }

    @Test
    fun `a hook that blows past the length limit fails instead of being shown`() = runBlocking {
        val tooLong = designExample.replace(
            "\"memory_hook\":\"正式场合里的 buy\"",
            "\"memory_hook\":\"这个词的意思是购买而且比一般的买要正式得多常见于合同商务和书面场合\"",
        )
        server.enqueue(MockResponse().setBody(chatBody(tooLong)))

        val result = generator().generateMemoryAssistance(request)

        assertTrue(result is GenerationResult.Failure)
    }

    @Test
    fun `unparseable answers fail rather than saving an empty hint`() = runBlocking {
        server.enqueue(MockResponse().setBody(chatBody("抱歉，我没法回答")))

        assertTrue(generator().generateMemoryAssistance(request) is GenerationResult.Failure)
    }

    @Test
    fun `regenerating tells the model what to avoid`() = runBlocking {
        server.enqueue(MockResponse().setBody(chatBody(designExample)))

        generator().generateMemoryAssistance(
            request.copy(
                avoidHookZh = "正式场合里的 buy",
                avoidTypes = listOf(MemoryType.Context),
                weakSegments = listOf("chase"),
                observedErrors = listOf("purchace"),
            ),
        )

        val body = server.takeRequest().body.readUtf8()
        // 换一条的关键就在这几句上：不说清楚避开什么，模型多半只是换几个字重说同一件事。
        assertTrue(body.contains("已经看过这条记忆钩子"))
        assertTrue(body.contains("CONTEXT"))
        assertTrue(body.contains("chase"))
        assertTrue(body.contains("purchace"))
    }
}

class MemoryAssistancePromptTest {

    @Test
    fun `prompt keeps the seven strategies and the no-invention rules`() {
        val prompt = OpenAiContentGenerator.buildMemoryAssistancePrompt(
            MemoryAssistanceRequest(term = "purchase", meaningZh = "购买", pos = "v.", learnerLevel = "B1"),
        )

        MemoryType.entries.forEach { assertTrue(prompt.contains(it.name)) }
        assertTrue(prompt.contains("只选最有效的 1~2 种"))
        assertTrue(prompt.contains("不超过 20 个汉字"))
        assertTrue(prompt.contains("宁缺毋滥"))
        assertTrue(prompt.contains("禁止编造词源"))
        // 输出结构要和文档 §6 一致，字段名对不上解析就全落空。
        assertTrue(prompt.contains("primary_memory_type"))
        assertTrue(prompt.contains("weak_segment"))
        assertTrue(prompt.contains("recall_question"))
    }

    @Test
    fun `without practice history the prompt stays clean`() {
        val prompt = OpenAiContentGenerator.buildMemoryAssistancePrompt(
            MemoryAssistanceRequest(term = "purchase", learnerLevel = "B1"),
        )

        // 没练过的词不该凭空多出一句"他反复错在"——那是编造出来的上下文。
        assertFalse(prompt.contains("反复错在"))
        assertFalse(prompt.contains("已经看过这条记忆钩子"))
    }
}
