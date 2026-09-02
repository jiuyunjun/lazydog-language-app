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
