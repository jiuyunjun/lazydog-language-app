package com.lazydog.english.domain.planning

import com.lazydog.english.domain.progress.DailyProgress
import com.lazydog.english.domain.progress.reachedDailyMinimum
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkippedStepsTest {
    private val plan = DailyPlanner.plan(12, 0, 0)

    @Test fun skippingAllDoesNotCompleteMinimum() {
        val done = emptySet<String>()
        val skipped = plan.map { it.step.id }.toSet()
        assertNull(DailyPlanner.nextStep(plan, done, skipped))
        assertFalse(reachedDailyMinimum(DailyProgress.Empty, done.size))
    }

    @Test fun restoringSkippedStepMakesItNextAgain() {
        val skipped = setOf(DailyStep.Words.id)
        assertEquals(DailyStep.Grammar, DailyPlanner.nextStep(plan, emptySet(), skipped)?.step)
        assertEquals(DailyStep.Words,
            DailyPlanner.nextStep(plan, emptySet(), skipped - DailyStep.Words.id)?.step)
    }

    @Test fun completedStepStillCountsAndIsNotRecommended() {
        val done = setOf(DailyStep.Words.id)
        assertTrue(reachedDailyMinimum(DailyProgress.Empty, done.size))
        assertEquals(DailyStep.Production,
            DailyPlanner.nextStep(plan, done, setOf(DailyStep.Grammar.id))?.step)
    }
}
