package com.lazydog.english.domain.ask

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AskStreamingTest {

    @Test
    fun `没到 answerZh 之前返回空串`() {
        assertEquals("", AskStreaming.partialAnswer(""))
        assertEquals("", AskStreaming.partialAnswer("{\"ans"))
        assertEquals("", AskStreaming.partialAnswer("{\"answerZh\""))
    }

    @Test
    fun `取出还没写完的回答正文`() {
        val raw = "{\"answerZh\":\"stay 是中性的待着"
        assertEquals("stay 是中性的待着", AskStreaming.partialAnswer(raw))
    }

    @Test
    fun `完整 JSON 里取出的正文不含后面的字段`() {
        val raw = """{"answerZh":"两者的区别在语气","addable":[{"term":"linger","meaningZh":"逗留"}]}"""
        assertEquals("两者的区别在语气", AskStreaming.partialAnswer(raw))
    }

    @Test
    fun `转义序列还原成真实字符`() {
        val raw = """{"answerZh":"第一行\n第二行，他说 \"hi\"，路径 a\\b"""
        assertEquals("第一行\n第二行，他说 \"hi\"，路径 a\\b", AskStreaming.partialAnswer(raw))
    }

    @Test
    fun `转义只传了一半时先返回已有部分`() {
        assertEquals("第一行", AskStreaming.partialAnswer("{\"answerZh\":\"第一行\\"))
        assertEquals("行", AskStreaming.partialAnswer("{\"answerZh\":\"行\\u56"))
    }

    @Test
    fun `unicode 转义能还原`() {
        assertEquals("中", AskStreaming.partialAnswer("{\"answerZh\":\"\\u4e2d"))
    }
}

class AskValidationTest {

    @Test
    fun `空回答不通过`() {
        assertEquals("回答是空的", AskValidation.validate(AskAnswer("   ")))
    }

    @Test
    fun `有正文就通过`() {
        assertNull(AskValidation.validate(AskAnswer("有内容")))
    }

    @Test
    fun `清洗去掉空条目、去重并截到上限`() {
        val cleaned = AskValidation.clean(
            AskAnswer(
                answerZh = "  答案  ",
                addable = listOf(
                    AskAddableTerm(" linger ", " 逗留 "),
                    AskAddableTerm("Linger", "重复的词形"),
                    AskAddableTerm("remain", ""),
                    AskAddableTerm("stay", "待着"),
                    AskAddableTerm("dwell", "居住"),
                    AskAddableTerm("hover", "盘旋"),
                ),
            ),
        )
        assertEquals("答案", cleaned.answerZh)
        assertEquals(listOf("linger", "stay", "dwell"), cleaned.addable.map { it.term })
        assertEquals("逗留", cleaned.addable.first().meaningZh)
    }
}
