package com.lazydog.english.core.data

import com.lazydog.english.core.database.AppDatabase
import com.lazydog.english.core.database.PerceptionAttemptEntity
import com.lazydog.english.core.database.ProductionAttemptEntity
import com.lazydog.english.core.database.PronunciationProgressEntity
import com.lazydog.english.domain.pronunciation.PerceptionAttempt
import com.lazydog.english.domain.pronunciation.PerceptionExercise
import com.lazydog.english.domain.pronunciation.PerceptionScoring
import com.lazydog.english.domain.pronunciation.PhonemeCatalog
import com.lazydog.english.domain.pronunciation.ProductionAttempt
import com.lazydog.english.domain.pronunciation.ProductionLevel
import com.lazydog.english.domain.pronunciation.ProductionScoring
import com.lazydog.english.domain.pronunciation.PronunciationProgress
import com.lazydog.english.domain.pronunciation.PronunciationStage
import com.lazydog.english.domain.pronunciation.PronunciationStages
import com.lazydog.english.domain.pronunciation.RecordingQuality
import com.lazydog.english.domain.pronunciation.SkillEstimate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 发音与音标的仓储（D-077）。
 *
 * 界面只跟它说话：不碰 DAO，也不碰 assets。它做两件事——把作答记下来，
 * 然后**用记录重算聚合状态**再存回去。
 *
 * 为什么每次都重算而不是增量更新：分数是「最近一窗的加权平均」，增量更新做不到
 * 「旧的自然滑出窗口」，而且一旦某次写坏，错误会永远留在那个数里。全量重算的代价是
 * 每次作答多读几十行，换来的是这张表任何时候都能从记录里推平。
 */
class PronunciationRepository(
    database: AppDatabase,
    val catalog: PhonemeCatalog,
) {

    private val dao = database.pronunciationDao()

    val progress: Flow<List<PronunciationProgress>> =
        dao.observeProgress().map { rows -> rows.map { it.toDomain() } }

    suspend fun progressFor(targetId: String): PronunciationProgress =
        dao.progress(targetId)?.toDomain() ?: PronunciationProgress(targetId = targetId)

    suspend fun allProgress(): List<PronunciationProgress> = dao.allProgress().map { it.toDomain() }

    /**
     * 看过音位卡。这一步把阶段从「还没练过」推到「认识了」，但**不写任何作答**——
     * 看一眼不是一次练习，不该进分数。
     */
    suspend fun markCardSeen(targetId: String) {
        val current = dao.progress(targetId)
        if (current?.seenCard == true) return
        val perception = current?.perceptionEstimate() ?: SkillEstimate.Unknown
        val production = current?.productionEstimate() ?: SkillEstimate.Unknown
        dao.saveProgress(
            PronunciationProgressEntity(
                targetId = targetId,
                perceptionScore = perception.score,
                perceptionSamples = perception.sampleCount,
                productionScore = production.score,
                productionSamples = production.sampleCount,
                stage = maxOf(
                    PronunciationStage.Introduced,
                    current?.stageOrUnseen() ?: PronunciationStage.Unseen,
                ).name,
                seenCard = true,
                lastPracticedAt = current?.lastPracticedAt,
            ),
        )
    }

    suspend fun recordPerception(
        attempt: PerceptionAttempt,
        expected: String,
        answer: String,
    ) {
        dao.insertPerception(
            PerceptionAttemptEntity(
                targetId = attempt.targetId,
                exercise = attempt.exercise.name,
                variantId = attempt.variantId,
                expected = expected,
                answer = answer,
                correct = attempt.correct,
                replayCount = attempt.replayCount,
                hintLevel = attempt.hintLevel,
                responseTimeMillis = attempt.responseTimeMillis,
                occurredAt = attempt.occurredAt,
            ),
        )
        recompute(attempt.targetId, attempt.occurredAt)
    }

    /**
     * 记一次跟读。
     *
     * 录音不可用的那次**照样入库但不算分**（[RecordingQuality.usable] 为 false 时
     * [ProductionAttempt.usableScore] 是 null）：记下来是为了排查「为什么老提示我再录一次」，
     * 不算分是因为麦克风没收到声音不代表这个音发不好（设计文档 §26.3）。
     */
    suspend fun recordProduction(
        attempt: ProductionAttempt,
        phonemeEvidenceJson: String = "",
    ) {
        dao.insertProduction(
            ProductionAttemptEntity(
                targetId = attempt.targetId,
                level = attempt.level.name,
                text = attempt.text,
                providerScore = attempt.providerScore,
                targetScore = attempt.targetScore,
                phonemeEvidenceJson = phonemeEvidenceJson,
                recordingQuality = attempt.quality.name,
                occurredAt = attempt.occurredAt,
            ),
        )
        // 不可用的录音不推进「最近练习时间」：它不该让一个目标看起来刚练过。
        recompute(attempt.targetId, attempt.occurredAt.takeIf { attempt.quality.usable })
    }

    suspend fun recentPerception(targetId: String): List<PerceptionAttempt> =
        dao.recentPerception(targetId, PERCEPTION_FETCH).map { it.toDomain() }

    suspend fun recentProduction(targetId: String): List<ProductionAttempt> =
        dao.recentProduction(targetId, PRODUCTION_FETCH).map { it.toDomain() }

    /** 结束页和周反馈用：这段时间里的全部作答，按目标分组交给领域层算进步。 */
    suspend fun perceptionSince(since: Long): List<PerceptionAttempt> =
        dao.perceptionSince(since).map { it.toDomain() }

    suspend fun productionSince(since: Long): List<ProductionAttempt> =
        dao.productionSince(since).map { it.toDomain() }

    private suspend fun recompute(targetId: String, practicedAt: Long?) {
        val perceptionAttempts = dao.recentPerception(targetId, PERCEPTION_FETCH).map { it.toDomain() }
        val productionAttempts = dao.recentProduction(targetId, PRODUCTION_FETCH).map { it.toDomain() }
        val perception = PerceptionScoring.estimate(perceptionAttempts)
        val production = ProductionScoring.estimate(productionAttempts)
        val existing = dao.progress(targetId)
        val stage = PronunciationStages.stageFor(
            seenCard = existing?.seenCard ?: false,
            perception = perception,
            production = production,
            provenWordCount = ProductionScoring.provenWords(productionAttempts).size,
            provenInSentence = ProductionScoring.provenInSentence(productionAttempts),
        )
        dao.saveProgress(
            PronunciationProgressEntity(
                targetId = targetId,
                perceptionScore = perception.score,
                perceptionSamples = perception.sampleCount,
                productionScore = production.score,
                productionSamples = production.sampleCount,
                stage = stage.name,
                seenCard = existing?.seenCard ?: false,
                lastPracticedAt = practicedAt ?: existing?.lastPracticedAt,
            ),
        )
    }

    companion object {
        /**
         * 一次取多少条重算。取得比评分窗口多，是因为提示拉到底的那些题会在领域层被剔掉，
         * 只取窗口大小的话会把实际计入的样本取瘦。
         */
        const val PERCEPTION_FETCH = PerceptionScoring.WINDOW * 2
        const val PRODUCTION_FETCH = ProductionScoring.WINDOW * 3
    }
}

private fun PronunciationProgressEntity.perceptionEstimate() =
    SkillEstimate(perceptionScore, perceptionSamples)

private fun PronunciationProgressEntity.productionEstimate() =
    SkillEstimate(productionScore, productionSamples)

private fun PronunciationProgressEntity.stageOrUnseen(): PronunciationStage =
    runCatching { PronunciationStage.valueOf(stage) }.getOrDefault(PronunciationStage.Unseen)

private fun PronunciationProgressEntity.toDomain() = PronunciationProgress(
    targetId = targetId,
    perception = perceptionEstimate(),
    production = productionEstimate(),
    stage = stageOrUnseen(),
    lastPracticedAt = lastPracticedAt,
)

private fun PerceptionAttemptEntity.toDomain() = PerceptionAttempt(
    targetId = targetId,
    exercise = runCatching { PerceptionExercise.valueOf(exercise) }
        .getOrDefault(PerceptionExercise.MinimalPairTwo),
    correct = correct,
    replayCount = replayCount,
    hintLevel = hintLevel,
    responseTimeMillis = responseTimeMillis,
    variantId = variantId,
    occurredAt = occurredAt,
)

private fun ProductionAttemptEntity.toDomain() = ProductionAttempt(
    targetId = targetId,
    level = runCatching { ProductionLevel.valueOf(level) }.getOrDefault(ProductionLevel.Word),
    text = text,
    providerScore = providerScore,
    targetScore = targetScore,
    quality = runCatching { RecordingQuality.valueOf(recordingQuality) }
        .getOrDefault(RecordingQuality.Ok),
    occurredAt = occurredAt,
)
