package com.lazydog.english.core.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.lazydog.english.LazyDogApplication
import com.lazydog.english.core.database.VocabularyRecord
import com.lazydog.english.domain.generation.GenerationResult
import com.lazydog.english.domain.generation.MAX_SENSE_CHOICES_PER_REQUEST
import com.lazydog.english.domain.generation.SenseChoiceItem
import com.lazydog.english.domain.generation.SenseChoiceRequest
import com.lazydog.english.domain.vocabulary.DictionaryAlignment
import com.lazydog.english.domain.vocabulary.DictionaryAlignment.Plan
import com.lazydog.english.domain.vocabulary.DictionaryEntry
import com.lazydog.english.domain.vocabulary.DictionaryPack
import com.lazydog.english.domain.vocabulary.DictionarySense
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * 老卡回填：把库里的单词卡和内置词典的义项对齐（D-095）。
 *
 * 两段，串成一条唯一任务：
 * 1. [LocalWorker] 不要网络。词典里查不到 / 没这个词性的记未对齐，只有一个候选的直接对上。
 * 2. [ChoiceWorker] 要网络。多个候选的成批交给模型选序号；模型明说都不对的记未对齐，
 *    没答好的留着下次再问。全部定下来才记"这一版词典回填完了"。
 *
 * 每张卡对齐后在生成信息里记 (包, 版本, 词性, 序号)，所以任务可以重复跑、结果不变。
 * 复习进度不动：只改 `vocabulary_details` 那一行，知识项 id 和调度状态不变。
 */
object DictionaryBackfillWorker {

    private const val WORK_NAME = "dictionary_backfill"
    private const val MAX_ATTEMPTS = 5
    private const val KEY_FORCE = "force"

    /** App 启动时调：这一版词典已经回填完就什么都不做，正在跑就不重复排。 */
    fun enqueueIfNeeded(context: Context) = enqueue(context, ExistingWorkPolicy.KEEP, force = false)

    /**
     * 恢复备份后调：库里的卡整个换了一批，不管偏好里记着这一版做没做完都重来。
     * 已经带着当前版本对齐信息的卡（备份里本来就对齐过的）照样跳过。
     */
    fun restart(context: Context) = enqueue(context, ExistingWorkPolicy.REPLACE, force = true)

    private fun enqueue(context: Context, policy: ExistingWorkPolicy, force: Boolean) {
        val input = workDataOf(KEY_FORCE to force)
        val local = OneTimeWorkRequestBuilder<LocalWorker>().setInputData(input).build()
        val choice = OneTimeWorkRequestBuilder<ChoiceWorker>()
            .setInputData(input)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context)
            .beginUniqueWork(WORK_NAME, policy, local)
            .then(choice)
            .enqueue()
    }

    /** 这张卡在当前词典版本下还没有结论（没对齐过，或者对齐的是旧版本）。 */
    private fun VocabularyRecord.pending(pack: DictionaryPack): Boolean {
        val tag = GenerationMetadata.dictionarySenseOf(item.generationMetadataJson) ?: return true
        return tag.pack != pack.id || tag.version < pack.version
    }

    private class Session(val app: LazyDogApplication) {
        val pack: DictionaryPack get() = app.dictionary.pack
        val repository get() = app.knowledgeRepository

        suspend fun alreadyDone(force: Boolean): Boolean =
            pack.version == 0 ||
                !force && app.userPreferences.dictionaryBackfillVersion.first() >= pack.version

        suspend fun pendingWithPlans(): List<Pair<VocabularyRecord, Pair<DictionaryEntry?, Plan>>> =
            withContext(Dispatchers.IO) {
                repository.vocabulary.first()
                    .filter { it.pending(pack) }
                    .map { record ->
                        val entry = app.dictionary.entry(record.detail.term)
                        record to (entry to DictionaryAlignment.plan(record.detail.pos, entry))
                    }
            }

        fun tag(sense: DictionarySense?) = DictionarySenseTag(
            pack = pack.id,
            version = pack.version,
            pos = sense?.pos?.wire.orEmpty(),
            order = sense?.order,
        )

        suspend fun apply(record: VocabularyRecord, entry: DictionaryEntry, sense: DictionarySense) {
            val fields = DictionaryAlignment.align(
                currentIpa = record.detail.ipa,
                currentForms = VocabularyJson.decodeList(record.detail.formsJson),
                sense = sense,
                entry = entry,
            )
            repository.alignToDictionary(record.item.id, tag(sense), fields)
        }

        suspend fun markUnmatched(record: VocabularyRecord) {
            repository.alignToDictionary(record.item.id, tag(null), null)
        }
    }

    class LocalWorker(context: android.content.Context, params: WorkerParameters) :
        CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val ctx = Session(applicationContext as LazyDogApplication)
            if (ctx.alreadyDone(inputData.getBoolean(KEY_FORCE, false))) return Result.success()
            for ((record, planned) in ctx.pendingWithPlans()) {
                val (entry, plan) = planned
                when (plan) {
                    Plan.Unmatched -> ctx.markUnmatched(record)
                    is Plan.Direct -> ctx.apply(record, entry!!, plan.sense)
                    is Plan.NeedsChoice -> Unit // 交给下一段
                }
            }
            return Result.success()
        }
    }

    class ChoiceWorker(context: android.content.Context, params: WorkerParameters) :
        CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val app = applicationContext as LazyDogApplication
            val ctx = Session(app)
            if (ctx.alreadyDone(inputData.getBoolean(KEY_FORCE, false))) return Result.success()
            val waiting = ctx.pendingWithPlans().mapNotNull { (record, planned) ->
                val (entry, plan) = planned
                (plan as? Plan.NeedsChoice)?.let { Triple(record, entry!!, it.options) }
            }
            var undecided = 0
            for (batch in waiting.chunked(MAX_SENSE_CHOICES_PER_REQUEST)) {
                val request = SenseChoiceRequest(
                    batch.map { (record, _, options) ->
                        SenseChoiceItem(
                            key = record.item.id.toString(),
                            term = record.detail.term,
                            options = options,
                            meaningHintZh = record.detail.meaningZh,
                            exampleEn = record.detail.exampleEn,
                        )
                    },
                )
                val result = try {
                    app.contentGenerator.chooseDictionarySenses(request)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    return retryOrStop()
                }
                // 网络、限流、没配密钥：已经定下来的那几批都写进去了，剩下的过一会儿再试。
                val choices = (result as? GenerationResult.Success)?.data ?: return retryOrStop()
                for ((record, entry, options) in batch) {
                    val key = record.item.id.toString()
                    if (key !in choices) {
                        undecided += 1
                        continue
                    }
                    val sense = choices[key]?.let(options::getOrNull)
                    if (sense == null) ctx.markUnmatched(record) else ctx.apply(record, entry, sense)
                }
            }
            if (undecided == 0) app.userPreferences.setDictionaryBackfillVersion(ctx.pack.version)
            return Result.success()
        }

        private fun retryOrStop(): Result =
            // 失败也算这一轮结束：下次启动 enqueueIfNeeded 还会再排，不在这里无限重试。
            if (runAttemptCount + 1 < MAX_ATTEMPTS) Result.retry() else Result.failure()
    }
}
