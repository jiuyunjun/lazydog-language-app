package com.lazydog.english.domain.spelling

import com.lazydog.english.domain.progress.DifficultyBias
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 难度偏置落到拼写题上（`持续学习DESIGN.md` §11）。
 *
 * 最要紧的一条是"只准变难不准变简单"：阶段升级看的是这一档答对了几次，
 * 用更浅的题喂进去等于让掌握度建立在更弱的证据上。
 */
class SpellingDifficultyTest {

    private fun progress(stage: SpellingStage) = SpellingProgress(stage = stage)

    @Test
    fun `不给偏置时和原来一模一样`() {
        SpellingStage.entries.forEach { stage ->
            assertEquals(
                SpellingEngine.questionType(progress(stage)),
                SpellingEngine.questionType(progress(stage), DifficultyBias.Steady),
            )
        }
    }

    @Test
    fun `太顺时从认得出挪向写得出`() {
        assertEquals(
            SpellingQuestionType.PartialCompletion,
            SpellingEngine.questionType(progress(SpellingStage.Recognition), DifficultyBias.Harder),
        )
        assertEquals(
            SpellingQuestionType.FreeRecall,
            SpellingEngine.questionType(progress(SpellingStage.GuidedRecall), DifficultyBias.Harder),
        )
    }

    @Test
    fun `第一次见到的词不加难`() {
        // S0 是接触不是考试，加难只会变成让人猜。
        assertEquals(
            SpellingQuestionType.Exposure,
            SpellingEngine.questionType(progress(SpellingStage.Seen), DifficultyBias.Harder),
        )
    }

    @Test
    fun `已经在最难那一档就不再往上`() {
        assertEquals(
            SpellingQuestionType.FreeRecall,
            SpellingEngine.questionType(progress(SpellingStage.FreeRecall), DifficultyBias.Harder),
        )
        assertEquals(
            SpellingQuestionType.DelayedFreeRecall,
            SpellingEngine.questionType(progress(SpellingStage.Retained), DifficultyBias.Harder),
        )
    }

    @Test
    fun `吃力时题型不变，帮助走提示阶梯`() {
        SpellingStage.entries.forEach { stage ->
            assertEquals(
                "$stage 不该因为吃力就换成更浅的题",
                SpellingEngine.questionType(progress(stage)),
                SpellingEngine.questionType(progress(stage), DifficultyBias.Easier),
            )
        }
        assertEquals(1, SpellingEngine.startingHintLevel(SpellingQuestionType.FreeRecall, DifficultyBias.Easier))
        // 提示答对只拿 0.8 分，掌握度自己会慢下来——这才是诚实的降难度。
        assertEquals(0.8, SpellingEngine.masteryCredit(correct = true, hintLevel = 1), 0.0001)
    }

    @Test
    fun `四选一和接触卡没有首字母可给`() {
        assertEquals(0, SpellingEngine.startingHintLevel(SpellingQuestionType.Recognition, DifficultyBias.Easier))
        assertEquals(0, SpellingEngine.startingHintLevel(SpellingQuestionType.Exposure, DifficultyBias.Easier))
    }

    @Test
    fun `顺和稳都从零级提示起`() {
        assertEquals(0, SpellingEngine.startingHintLevel(SpellingQuestionType.FreeRecall, DifficultyBias.Steady))
        assertEquals(0, SpellingEngine.startingHintLevel(SpellingQuestionType.FreeRecall, DifficultyBias.Harder))
    }
}
