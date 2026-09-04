package com.lazydog.english.feature.listening

import com.lazydog.english.domain.listening.ListeningDistractor
import com.lazydog.english.domain.listening.ListeningItem
import com.lazydog.english.domain.listening.ListeningKeyExpression
import com.lazydog.english.domain.listening.MishearType
import org.junit.Assert.assertTrue
import org.junit.Test

class ListeningAskContextTest {

    @Test
    fun `before the reveal the context leaks neither the sentence nor the answer`() {
        val context = listeningAskContext(item, revealed = false)
        val text = (context.title + context.details.joinToString { it.label + it.value })
        assertTrue(text, !text.contains(item.textEn))
        assertTrue(text, !text.contains(item.meaningZh))
        assertTrue(text, !text.contains(item.keyExpression.en))
    }

    @Test
    fun `选项露出来之后，四条都给，但不标哪条是对的`() {
        // 用户正盯着这四条，不给的话模型只能瞎猜他在纠结什么。
        val shown = item.allOptionsZh.shuffled()
        val context = listeningAskContext(item, revealed = false, optionsZh = shown)
        val text = context.title + context.details.joinToString { it.label + it.value }

        shown.forEach { assertTrue(it, text.contains(it)) }
        // 英文原文仍然一个字都不给：这一页的规矩是英文永远最后出现。
        assertTrue(text, !text.contains(item.textEn))
        assertTrue(text, !text.contains(item.keyExpression.en))
        // 正确答案混在四条里，没有任何东西把它标出来——模型和用户一样只能从选项本身推。
        assertTrue(text, !text.contains("正确答案是"))
        assertTrue(text, text.contains("不能说出、暗示或用排除法指向哪个选项是正确答案"))
    }

    @Test
    fun `after the reveal the sentence and the key expression are on the table`() {
        val context = listeningAskContext(item, revealed = true)
        val text = context.title + context.details.joinToString { it.value }
        assertTrue(text, text.contains(item.textEn))
        assertTrue(text, text.contains(item.keyExpression.en))
    }

    private val item = ListeningItem(
        textEn = "I'd rather not talk about it right now.",
        meaningZh = "我现在不太想聊这个。",
        sceneZh = "朋友闲聊",
        subSceneZh = "回避话题",
        intentZh = "婉拒",
        toneZh = "客气但明确",
        registerZh = "口语",
        cefr = "B1",
        listeningDifficulty = 3,
        audioFeatures = listOf("reduction"),
        keyExpression = ListeningKeyExpression(en = "would rather not", meaningZh = "宁可不"),
        distractors = listOf(
            ListeningDistractor("我现在很想聊这个。", MishearType.Negation, "没听出 not"),
        ),
        sceneHintZh = "两个人在说要不要继续这个话题",
        keywordHintZh = "注意一个表示宁可的结构",
    )
}
