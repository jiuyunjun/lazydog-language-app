package com.lazydog.english.core.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.lazydog.english.LazyDogApplication
import com.lazydog.english.domain.generation.GenerationResult
import com.lazydog.english.domain.generation.NewWordsRequest
import com.lazydog.english.domain.vocabulary.EnrichmentResult
import com.lazydog.english.domain.vocabulary.QuickCardEnrichment
import com.lazydog.english.domain.vocabulary.posLabelZh
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 查词速记卡入库后，在后台按完整学习卡的规格补全（D-080）。
 *
 * 放进 WorkManager 而不是页面协程：用户存完词多半马上关掉面板、翻下一页甚至退出 App，
 * 页面协程会跟着被取消；没网时也该等联网再补，而不是直接失败。
 * 每个词一个唯一任务，重复入队不会补两次。
 */
object WordEnrichmentWorker {

    private const val KEY_ITEM_ID = "itemId"
    private const val MAX_ATTEMPTS = 3

    enum class State { Idle, Waiting, Running, Failed }

    private fun workName(itemId: Long) = "enrich_word_$itemId"

    fun enqueue(context: Context, itemId: Long) {
        val request = OneTimeWorkRequestBuilder<Worker>()
            .setInputData(workDataOf(KEY_ITEM_ID to itemId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        // REPLACE 而不是 KEEP：上一次失败后用户手动点"补全"，KEEP 会因为旧任务还在（已失败）而什么都不做。
        // 正在跑的那次被替换也无妨，写回前仓储会再确认一次它还是速记卡。
        WorkManager.getInstance(context)
            .enqueueUniqueWork(workName(itemId), ExistingWorkPolicy.REPLACE, request)
    }

    fun observe(context: Context, itemId: Long): Flow<State> =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkFlow(workName(itemId))
            .map { infos ->
                when (infos.lastOrNull()?.state) {
                    WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> State.Waiting
                    WorkInfo.State.RUNNING -> State.Running
                    WorkInfo.State.FAILED -> State.Failed
                    // 成功了速记卡就不再是速记卡，界面那一行会自己消失；取消了就当没开始。
                    else -> State.Idle
                }
            }

    class Worker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val itemId = inputData.getLong(KEY_ITEM_ID, -1)
            val app = applicationContext as LazyDogApplication
            val repository = app.knowledgeRepository
            // 已经补过、被删了、或者本来就是完整卡：没有要做的。
            val quick = repository.quickCardOf(itemId) ?: return Result.success()

            val level = app.userPreferences.learnerLevel.first().ifBlank { "A2" }
            val topics = app.userPreferences.topics.first().toList()
            val result = try {
                app.contentGenerator.generateNewWords(
                    NewWordsRequest(
                        count = 1,
                        learnerLevel = level,
                        topics = topics,
                        knownTerms = emptyList(),
                        targetTerm = quick.term,
                        sentenceContext = quick.exampleEn,
                        confirmedSenseZh = listOf(posLabelZh(quick.pos), quick.meaningZh)
                            .filter { it.isNotBlank() }
                            .joinToString(" "),
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return retryOrGiveUp()
            }

            val card = when (result) {
                // 网络、限流、超时、格式不对都在这里，值得过一会儿再试。
                is GenerationResult.Failure -> return retryOrGiveUp()
                is GenerationResult.Success -> result
            }
            // 模型换了词或换了意思：再试大概率还是这样，不白花第二次。
            val merged = when (val outcome = QuickCardEnrichment.merge(quick, card.data.first())) {
                is EnrichmentResult.Rejected -> return Result.failure()
                is EnrichmentResult.Merged -> outcome.word
            }
            val metadata = LearningCardMetadata(
                input = quick.term,
                context = quick.exampleEn,
                learnerLevel = level,
                topics = topics,
                model = card.model,
                promptVersion = card.promptVersion,
                generatedAt = System.currentTimeMillis(),
                origin = "quick-lookup",
            )
            repository.applyEnrichment(itemId, merged, Json.encodeToString(metadata))
            return Result.success()
        }

        private fun retryOrGiveUp(): Result =
            if (runAttemptCount + 1 < MAX_ATTEMPTS) Result.retry() else Result.failure()
    }
}
