package com.lazydog.english.core.ai

import com.lazydog.english.domain.generation.NativeCanonicalRequest
import com.lazydog.english.domain.generation.NewWordsRequest
import com.lazydog.english.domain.generation.ReadingAngle
import com.lazydog.english.domain.generation.ReadingArchetype
import com.lazydog.english.domain.generation.ReadingGenerationRequest
import com.lazydog.english.domain.generation.ReadingVoice
import com.lazydog.english.domain.production.TranslationRequest
import com.lazydog.english.domain.production.TranslationSituation
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 写法、角度、语气和场合确实进了提示词，而且各进各的语言。 */
class ReadingVarietyPromptTest {

    private val reading = ReadingGenerationRequest(
        learnerLevel = "B1", topic = "咖啡", targetLength = 300,
        reviewVocabulary = emptyList(), knownVocabulary = emptyList(),
        reviewGrammar = emptyList(), maxNewWords = 4,
        archetype = ReadingArchetype.TwoVoices,
    )

    @Test
    fun `英文阅读带上英文的角度和语气`() {
        val prompt = OpenAiContentGenerator.buildReadingPrompt(
            reading.copy(
                angles = listOf(ReadingAngle.Money, ReadingAngle.Mistake),
                voice = ReadingVoice.DryWit,
            ),
        )
        assertTrue(prompt.contains(ReadingArchetype.TwoVoices.briefEn))
        assertTrue(prompt.contains("- ${ReadingAngle.Money.briefEn}"))
        assertTrue(prompt.contains("- ${ReadingAngle.Mistake.briefEn}"))
        assertTrue(prompt.contains(ReadingVoice.DryWit.briefEn))
        // 英文写作指令里不该混进给母语阅读用的中文说明。
        assertFalse(prompt.contains(ReadingAngle.Money.briefZh))
        assertFalse(prompt.contains(ReadingVoice.DryWit.briefZh))
    }

    @Test
    fun `不给角度和语气时不留空段落`() {
        val prompt = OpenAiContentGenerator.buildReadingPrompt(reading)
        assertFalse(prompt.contains("切入角度"))
        assertFalse(prompt.contains("语气："))
    }

    @Test
    fun `评审知道这篇的写法，不把对话体当成写坏的说明文`() {
        val prompt = OpenAiContentGenerator.buildReadingCriticPrompt(
            reading,
            com.lazydog.english.domain.generation.GeneratedReading(
                title = "t", body = "b", readerPayoff = "p", estimatedCefr = "B1",
                targetVocabulary = emptyList(), targetGrammar = emptyList(),
                comprehensionQuestions = emptyList(),
            ),
        )
        assertTrue(prompt.contains(ReadingArchetype.TwoVoices.labelZh))
        assertTrue(prompt.contains("不得要求把它改回说明文"))
    }

    @Test
    fun `母语阅读带上中文的写法、角度和语气`() {
        val prompt = OpenAiContentGenerator.buildNativeCanonicalPrompt(
            NativeCanonicalRequest(
                topic = "便利店", learnerLevel = "B1",
                archetype = ReadingArchetype.DayOnTheJob,
                angles = listOf(ReadingAngle.OddHour),
                voice = ReadingVoice.Storyteller,
            ),
        )
        assertTrue(prompt.contains(ReadingArchetype.DayOnTheJob.briefZh))
        assertTrue(prompt.contains("- ${ReadingAngle.OddHour.briefZh}"))
        assertTrue(prompt.contains(ReadingVoice.Storyteller.briefZh))
        assertFalse(prompt.contains(ReadingArchetype.DayOnTheJob.briefEn))
        // 没带检索资料：虚构人物要让人看得出是小品。
        assertTrue(prompt.contains("人物一律是虚构的"))
    }

    @Test
    fun `母语阅读带了检索资料时，写法不能成为虚构的理由`() {
        val prompt = OpenAiContentGenerator.buildNativeCanonicalPrompt(
            NativeCanonicalRequest(
                topic = "便利店", learnerLevel = "B1",
                factPack = listOf("某条检索摘录"),
                archetype = ReadingArchetype.FirstPerson,
            ),
        )
        assertTrue(prompt.contains("不得为了凑写法虚构人物经历"))
        assertFalse(prompt.contains("人物一律是虚构的"))
    }

    @Test
    fun `母语阅读不传写法时保持原样可用`() {
        val prompt = OpenAiContentGenerator.buildNativeCanonicalPrompt(
            NativeCanonicalRequest(topic = "便利店", learnerLevel = "B1"),
        )
        assertFalse(prompt.contains("这一篇的写法是"))
        assertTrue(prompt.contains("输出 JSON schema"))
    }

    @Test
    fun `中译英按顺序给每句一个场合`() {
        val situations = TranslationSituation.pick(2, Random(7))
        assertEquals(2, situations.toSet().size)
        val prompt = OpenAiContentGenerator.buildTranslationTasksPrompt(
            TranslationRequest(learnerLevel = "B1", situations = situations, topics = listOf("篮球")),
        )
        assertTrue(prompt.contains("1. ${situations[0]}"))
        assertTrue(prompt.contains("2. ${situations[1]}"))
        assertTrue(prompt.contains("篮球"))
        val plain = OpenAiContentGenerator.buildTranslationTasksPrompt(TranslationRequest(learnerLevel = "B1"))
        assertFalse(plain.contains("各落在一个场合里"))
    }

    @Test
    fun `一批新词要求例句错开口吻，单个词不提这条`() {
        val batch = NewWordsRequest(count = 5, learnerLevel = "B1", topics = emptyList(), knownTerms = emptyList())
        assertTrue(OpenAiContentGenerator.buildNewWordsPrompt(batch).contains("也要错开"))
        assertFalse(OpenAiContentGenerator.buildNewWordsPrompt(batch.copy(count = 1)).contains("也要错开"))
    }
}
