package com.lazydog.english

import android.app.Application
import com.lazydog.english.core.ai.AiConfig
import com.lazydog.english.core.ai.OpenAiContentGenerator
import com.lazydog.english.core.backup.BackupFileStore
import com.lazydog.english.core.backup.BackupRepository
import com.lazydog.english.core.data.KnowledgeRepository
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

    val speechController: SpeechController by lazy { SpeechController(this, userPreferences) }

    val readingRepository: ReadingRepository by lazy { ReadingRepository(database) }

    val scenarioSessionRepository: ScenarioSessionRepository by lazy { ScenarioSessionRepository(database) }

    val backupRepository: BackupRepository by lazy { BackupRepository(database, userPreferences) }

    val backupFileStore: BackupFileStore by lazy { BackupFileStore(this) }

    val contentGenerator: LearningContentGenerator by lazy {
        OpenAiContentGenerator(
            // 模型按功能取：没单独设过的功能自动跟随默认模型（设置页「各功能使用的模型」）。
            config = { task ->
                AiConfig(
                    baseUrl = userPreferences.aiBaseUrl.first(),
                    apiKey = userPreferences.aiApiKey.first(),
                    model = userPreferences.aiModelFor(task).first(),
                )
            },
        )
    }
}
