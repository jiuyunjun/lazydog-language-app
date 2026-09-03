package com.lazydog.english

import android.app.Application
import com.lazydog.english.core.ai.AiConfig
import com.lazydog.english.core.ai.AiTask
import com.lazydog.english.core.ai.ModelCatalog
import com.lazydog.english.core.ai.OpenAiContentGenerator
import com.lazydog.english.core.backup.BackupFileStore
import com.lazydog.english.core.backup.BackupRepository
import com.lazydog.english.core.data.KnowledgeRepository
import com.lazydog.english.core.data.ListeningMaterialRepository
import com.lazydog.english.core.data.MemoryHintRepository
import com.lazydog.english.core.data.MistakeRepository
import com.lazydog.english.core.data.ReadingRepository
import com.lazydog.english.core.data.ScenarioSessionRepository
import kotlinx.coroutines.flow.first
import com.lazydog.english.core.data.UserPreferences
import com.lazydog.english.core.database.AppDatabase
import com.lazydog.english.core.speech.SpeechController
import com.lazydog.english.domain.generation.LearningContentGenerator
import com.lazydog.english.domain.scheduling.SimpleIntervalScheduler

/**
 * 手工组装的应用级单例。依赖关系还很浅，先不上 Hilt（ARCHITECTURE.md §2）。
 */
class LazyDogApplication : Application() {

    val database: AppDatabase by lazy { AppDatabase.create(this) }

    val userPreferences: UserPreferences by lazy { UserPreferences(this) }

    val knowledgeRepository: KnowledgeRepository by lazy {
        KnowledgeRepository(database, SimpleIntervalScheduler())
    }

    val mistakeRepository: MistakeRepository by lazy { MistakeRepository(database) }

    /** 记忆提示要现生成，所以这个仓储拿着生成器；contentGenerator 本身仍然是懒的。 */
    val memoryHintRepository: MemoryHintRepository by lazy {
        MemoryHintRepository(database, contentGenerator)
    }

    val speechController: SpeechController by lazy { SpeechController(this, userPreferences) }

    val readingRepository: ReadingRepository by lazy { ReadingRepository(database) }

    val listeningMaterialRepository: ListeningMaterialRepository by lazy {
        ListeningMaterialRepository(database)
    }

    val scenarioSessionRepository: ScenarioSessionRepository by lazy { ScenarioSessionRepository(database) }

    val backupRepository: BackupRepository by lazy { BackupRepository(database, userPreferences) }

    val backupFileStore: BackupFileStore by lazy { BackupFileStore(this) }

    /** 模型清单拉一次就留着，「各功能使用的模型」两级页面共用（见 ModelCatalog）。 */
    val modelCatalog: ModelCatalog by lazy { ModelCatalog(userPreferences) }

    val contentGenerator: LearningContentGenerator by lazy {
        OpenAiContentGenerator(
            // 模型按功能取：没单独设过的功能自动跟随默认模型（设置页「各功能使用的模型」）。
            config = { task ->
                val model = userPreferences.aiModelFor(task).first()
                AiConfig(
                    baseUrl = userPreferences.aiBaseUrl.first(),
                    apiKey = userPreferences.aiApiKey.first(),
                    model = model,
                    // 撞过一次就记住了，不用每次调用都先用错的字段名试一遍。
                    useCompletionTokens = model in userPreferences.completionTokenModels.first(),
                    effortCandidates = if (model in userPreferences.noReasoningEffortModels.first()) {
                        emptyList()
                    } else {
                        AiTask.effortCandidates(
                            task = task,
                            chosen = userPreferences.aiEffortFor(task).first(),
                            rejected = userPreferences.rejectedEfforts(model).first(),
                        )
                    },
                )
            },
            onNeedsCompletionTokens = userPreferences::rememberCompletionTokenModel,
            onRejectsReasoningEffort = userPreferences::rememberNoReasoningEffortModel,
            onRejectsEffortValue = userPreferences::rememberRejectedEffort,
        )
    }
}
