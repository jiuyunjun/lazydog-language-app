package com.lazydog.english.core.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class AiTaskEffortTest {

    @Test
    fun `a task with a recommended effort falls back to low`() {
        // low 几乎所有推理模型都认。只留一个兜底：候选越多，撞不上的模型要多花几个往返才安定。
        assertEquals(listOf("none", "low"), AiTask.effortCandidates(AiTask.Explain))
    }

    @Test
    fun `a task that already wants low has nothing to fall back to`() {
        assertEquals(listOf("low"), AiTask.effortCandidates(AiTask.Listening))
    }

    @Test
    fun `a task that wants the model default sends nothing`() {
        // medium 本来就是多数模型的默认值，显式再发一遍没意义，还多一个可能被拒的参数。
        assertEquals(emptyList<String>(), AiTask.effortCandidates(AiTask.Assessment))
    }

    @Test
    fun `the user's choice wins but still keeps a fallback`() {
        assertEquals(listOf("high", "low"), AiTask.effortCandidates(AiTask.Listening, chosen = "high"))
    }

    @Test
    fun `choosing the model default drops the parameter entirely`() {
        assertEquals(
            emptyList<String>(),
            AiTask.effortCandidates(AiTask.Listening, chosen = AiTask.MODEL_DEFAULT),
        )
    }

    @Test
    fun `values this model already rejected are skipped`() {
        // 撞过一次就该记住，否则每次调用都拿被拒过的取值再撞一遍。
        assertEquals(
            listOf("low"),
            AiTask.effortCandidates(AiTask.Explain, rejected = setOf("none")),
        )
    }

    @Test
    fun `a model that rejected everything ends up sending nothing`() {
        assertEquals(
            emptyList<String>(),
            AiTask.effortCandidates(AiTask.Explain, rejected = setOf("none", "low")),
        )
    }
}
