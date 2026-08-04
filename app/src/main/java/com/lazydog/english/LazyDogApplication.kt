package com.lazydog.english

import android.app.Application
import com.lazydog.english.core.ai.AiConfig
import com.lazydog.english.core.ai.OpenAiContentGenerator
import com.lazydog.english.core.data.KnowledgeRepository
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

    val speechController: SpeechController by lazy { SpeechController(userPreferences) }

    val contentGenerator: LearningContentGenerator by lazy {
        OpenAiContentGenerator(
            config = {
                AiConfig(
                    baseUrl = userPreferences.aiBaseUrl.first(),
                    apiKey = userPreferences.aiApiKey.first(),
                    model = userPreferences.aiModel.first(),
                )
            },
        )
    }
}
