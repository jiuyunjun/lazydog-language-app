package com.lazydog.english

import android.app.Application
import com.lazydog.english.core.ai.AiConfig
import com.lazydog.english.core.ai.AiTask
import com.lazydog.english.core.ai.ModelCatalog
import com.lazydog.english.core.ai.OpenAiContentGenerator
import com.lazydog.english.core.backup.BackupFileStore
import com.lazydog.english.core.backup.BackupRepository
import com.lazydog.english.core.data.AssetWordFrequencyIndex
import com.lazydog.english.core.data.KnowledgeRepository
import com.lazydog.english.core.data.ListeningMaterialRepository
import com.lazydog.english.core.data.MemoryHintRepository
import com.lazydog.english.core.data.MistakeRepository
import com.lazydog.english.core.data.ProgressRepository
import com.lazydog.english.core.data.ReadingRepository
import com.lazydog.english.core.data.ScenarioSessionRepository
import kotlinx.coroutines.flow.first
import com.lazydog.english.core.data.UserPreferences
import com.lazydog.english.core.database.AppDatabase
import com.lazydog.english.core.data.VocabularyImageRepository
import com.lazydog.english.core.network.BraveImageSearchClient
import com.lazydog.english.core.network.ImageDownloader
import com.lazydog.english.core.network.LocalImageDownloader
import com.lazydog.english.core.network.BraveSearchClient
import com.lazydog.english.core.network.ImageSearchProvider
import com.lazydog.english.domain.generation.WebSearchProvider
import com.lazydog.english.core.speech.SpeechController
import com.lazydog.english.domain.generation.LearningContentGenerator
import com.lazydog.english.domain.scheduling.FsrsScheduler
import com.lazydog.english.domain.vocabulary.WordFrequencyIndex

/**
 * 手工组装的应用级单例。依赖关系还很浅，先不上 Hilt（ARCHITECTURE.md §2）。
 */
class LazyDogApplication : Application() {

    val database: AppDatabase by lazy { AppDatabase.create(this) }

    val userPreferences: UserPreferences by lazy { UserPreferences(this) }

    val knowledgeRepository: KnowledgeRepository by lazy {
        KnowledgeRepository(database, FsrsScheduler())
    }

    val mistakeRepository: MistakeRepository by lazy { MistakeRepository(database) }

    /** 词频表：给"下一批学什么"排优先级，读 assets，失败退化成空索引。 */
    val wordFrequencyIndex: WordFrequencyIndex by lazy { AssetWordFrequencyIndex(this) }

    /** 进步证据：不存新数据，从既有学习事件里推（`持续学习DESIGN.md` §14）。 */
    val progressRepository: ProgressRepository by lazy { ProgressRepository(database) }

    /** 记忆提示要现生成，所以这个仓储拿着生成器；contentGenerator 本身仍然是懒的。 */
    val memoryHintRepository: MemoryHintRepository by lazy {
        MemoryHintRepository(database, contentGenerator)
    }

    val speechController: SpeechController by lazy { SpeechController(this, userPreferences) }

    val readingRepository: ReadingRepository by lazy { ReadingRepository(database) }

    /**
     * 联网检索。母语阅读写时效内容之前要先有可信事实（`母语阅读DESIGN.md` §36.7），
     * 但它是可选增强：没配密钥就是"这次不搜"，不是错误，文章照常生成。
     */
    val webSearch: WebSearchProvider by lazy {
        BraveSearchClient(apiKey = { userPreferences.braveApiKey.first() })
    }

    /**
     * 图片检索用的是和联网检索同一个 Brave 密钥（`单词视觉记忆图片DESIGN.md` §16，D-071）。
     * 没配就是"这次不配图"，词卡照常。
     */
    val imageSearch: ImageSearchProvider by lazy {
        BraveImageSearchClient(apiKey = { userPreferences.braveApiKey.first() })
    }

    /**
     * 选中的那张图存进 `filesDir`，不是 `cacheDir`（D-076）：
     * 系统清缓存、外链失效都不该让用户挑好的配图消失。
     */
    val imageDownloader: ImageDownloader by lazy {
        LocalImageDownloader(java.io.File(filesDir, LocalImageDownloader.DIRECTORY_NAME))
    }

    /** 单词视觉记忆图片：查缓存、找图、换图都在这里，页面不碰 Brave 也不碰模型。 */
    val vocabularyImageRepository: VocabularyImageRepository by lazy {
        VocabularyImageRepository(database, contentGenerator, imageSearch, imageDownloader)
    }

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
