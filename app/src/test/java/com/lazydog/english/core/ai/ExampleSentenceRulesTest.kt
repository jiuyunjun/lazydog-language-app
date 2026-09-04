package com.lazydog.english.core.ai

import com.lazydog.english.domain.generation.NewWordsRequest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 例句提示词里的兴趣个性化（`持续学习DESIGN.md` §19.2）。
 *
 * 提示词没法单测"生成的句子好不好"，但可以钉住"该说的话说了、不该丢的约束没丢"。
 */
class ExampleSentenceRulesTest {

    @Test
    fun `勾了兴趣就把领域给出去，并且说明这是偏好不是硬要求`() {
        val rules = OpenAiContentGenerator.exampleSentenceRules("B1", listOf("编程", "摩托车"))
        assertTrue(rules.contains("编程"))
        assertTrue(rules.contains("摩托车"))
        // 缺了这句，模型会把词硬塞进兴趣场景，写出比无关句子更糟的别扭句。
        assertTrue(rules.contains("偏好不是硬要求"))
    }

    @Test
    fun `没勾兴趣时不多说一句`() {
        val rules = OpenAiContentGenerator.exampleSentenceRules("B1")
        assertFalse(rules.contains("这位学习者关心的领域"))
        assertFalse(rules.contains("偏好不是硬要求"))
    }

    @Test
    fun `原有的等级和自然度约束不能因为加了兴趣就丢掉`() {
        val rules = OpenAiContentGenerator.exampleSentenceRules("A2", listOf("游戏"))
        assertTrue(rules.contains("A2"))
        assertTrue(rules.contains("绝不能编造出处"))
        assertTrue(rules.contains("exampleEn 里不要出现中文"))
    }

    @Test
    fun `新词和查词讲解都带上兴趣`() {
        val topics = listOf("咖啡")
        val newWords = OpenAiContentGenerator.buildNewWordsPrompt(
            NewWordsRequest(count = 3, learnerLevel = "B1", topics = topics, knownTerms = emptyList()),
        )
        assertTrue(newWords.contains("这位学习者关心的领域"))

        val explain = OpenAiContentGenerator.buildExplainWordPrompt(
            term = "brew",
            sentence = "Let it brew for four minutes.",
            level = "B1",
            topics = topics,
        )
        assertTrue(explain.contains("这位学习者关心的领域"))
    }
}
