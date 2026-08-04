package com.lazydog.english.domain.planning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyPlannerTest {

    @Test
    fun `full budget yields all four steps in priority order`() {
        val plan = DailyPlanner.plan(dailyMinutes = 15, dueVocabCount = 8, dueGrammarCount = 1)
        assertEquals(
            listOf(DailyStep.Words, DailyStep.Grammar, DailyStep.Reading, DailyStep.Speaking),
            plan.map { it.step },
        )
        assertTrue(plan[0].note.contains("8"))
    }

    @Test
    fun `tight budget drops later steps but never words`() {
        val plan = DailyPlanner.plan(dailyMinutes = 6, dueVocabCount = 0, dueGrammarCount = 0)
        assertEquals(DailyStep.Words, plan.first().step)
        assertTrue(plan.none { it.step == DailyStep.Speaking })
    }

    @Test
    fun `tiny budget still includes words`() {
        val plan = DailyPlanner.plan(dailyMinutes = 1, dueVocabCount = 3, dueGrammarCount = 0)
        assertEquals(listOf(DailyStep.Words), plan.map { it.step })
    }

    @Test
    fun `notes reflect due state`() {
        val noDue = DailyPlanner.plan(12, 0, 0)
        assertTrue(noDue.first().note.contains("新词"))
    }
}
