package com.lazydog.english.core.speech

import com.lazydog.english.domain.speaking.WordErrorType
import com.lazydog.english.domain.speaking.overallComment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PronunciationJsonTest {

    private val sample = """
        {
          "RecognitionStatus": "Success",
          "DisplayText": "The city tried to curb traffic downtown.",
          "NBest": [
            {
              "Display": "The city tried to curb traffic downtown.",
              "PronunciationAssessment": {
                "AccuracyScore": 88.4,
                "FluencyScore": 92.1,
                "CompletenessScore": 100.0,
                "PronScore": 90.2
              },
              "Words": [
                {
                  "Word": "the",
                  "PronunciationAssessment": { "AccuracyScore": 95.0, "ErrorType": "None" }
                },
                {
                  "Word": "curb",
                  "PronunciationAssessment": { "AccuracyScore": 42.0, "ErrorType": "Mispronunciation" }
                },
                {
                  "Word": "downtown",
                  "PronunciationAssessment": { "AccuracyScore": 0.0, "ErrorType": "Omission" }
                }
              ]
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `parses scores and words`() {
        val feedback = PronunciationJson.parse(sample)!!

        assertEquals("The city tried to curb traffic downtown.", feedback.recognizedText)
        assertEquals(88, feedback.accuracyScore)
        assertEquals(92, feedback.fluencyScore)
        assertEquals(100, feedback.completenessScore)
        assertEquals(90, feedback.pronunciationScore)
        assertEquals(3, feedback.words.size)
    }

    @Test
    fun `problem words include mispronunciation and omission but not good words`() {
        val problems = PronunciationJson.parse(sample)!!.problemWords

        assertEquals(listOf("curb", "downtown"), problems.map { it.word })
        assertEquals(WordErrorType.Mispronunciation, problems[0].errorType)
        assertEquals(WordErrorType.Omission, problems[1].errorType)
    }

    @Test
    fun `missing assessment yields null`() {
        assertNull(PronunciationJson.parse("""{"DisplayText":"hi","NBest":[{"Display":"hi"}]}"""))
        assertNull(PronunciationJson.parse("""{"DisplayText":"hi"}"""))
        assertNull(PronunciationJson.parse("not json"))
    }

    @Test
    fun `unknown error type maps to unknown`() {
        val json = sample.replace("\"Omission\"", "\"SomethingNew\"")
        val feedback = PronunciationJson.parse(json)!!
        assertEquals(WordErrorType.Unknown, feedback.words[2].errorType)
    }

    @Test
    fun `overall comment covers all score bands`() {
        assertTrue(overallComment(90).isNotBlank())
        assertTrue(overallComment(75).isNotBlank())
        assertTrue(overallComment(55).isNotBlank())
        assertTrue(overallComment(30).isNotBlank())
        assertEquals(4, setOf(overallComment(90), overallComment(75), overallComment(55), overallComment(30)).size)
    }

    @Test
    fun `phoneme level evidence parses when the service sends it`() {
        val json = """
            {"DisplayText":"think","NBest":[{"Display":"think",
            "PronunciationAssessment":{"AccuracyScore":78.0,"FluencyScore":80.0,
            "CompletenessScore":100.0,"PronScore":78.0},
            "Words":[{"Word":"think","PronunciationAssessment":{"AccuracyScore":78.0,"ErrorType":"None"},
            "Phonemes":[{"Phoneme":"θ","PronunciationAssessment":{"AccuracyScore":58.0}},
            {"Phoneme":"ɪ","PronunciationAssessment":{"AccuracyScore":92.0}}]}]}]}
        """.trimIndent()

        val word = PronunciationJson.parse(json)!!.words.single()

        assertEquals(listOf("θ", "ɪ"), word.phonemes.map { it.symbol })
        assertEquals(listOf(58, 92), word.phonemes.map { it.accuracyScore })
    }

    @Test
    fun `word level responses simply have no phonemes`() {
        // 没请求音素粒度时 Azure 不返回 Phonemes。那是空列表，不是错误——
        // 既有的朗读页仍然走逐词粒度，解析不能因此变严格。
        assertTrue(PronunciationJson.parse(sample)!!.words.all { it.phonemes.isEmpty() })
    }
}
