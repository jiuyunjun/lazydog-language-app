package com.lazydog.english.domain.assessment

import kotlinx.serialization.Serializable

/** 测试流程的四个阶段：客观题梯度 → 深度阅读 → 写一句话 → 完成。 */
@Serializable
enum class AssessmentStage { Ladder, DeepReading, Writing, Done }

/**
 * 中断恢复用的持久化快照。深度阅读的题面存下来，是为了退出重进时看到同一篇短文，
 * 而不是每次都重新生成一篇不一样的（呼应"中途退出会保留已答题目，下次接着来"）。
 * 写一句话的题面不需要存——它是本地模板按 [AssessmentState.score] 确定性算出来的，
 * 见 [WritingTaskLibrary.taskFor]。
 */
@Serializable
data class SavedAssessment(
    val state: AssessmentState,
    val stage: AssessmentStage = AssessmentStage.Ladder,
    val deepReadingTask: DeepReadingTask? = null,
)
