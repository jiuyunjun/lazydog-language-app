package com.lazydog.english

import android.app.Application
import com.lazydog.english.core.ai.AiConfig
import com.lazydog.english.core.ai.AiTask
import com.lazydog.english.core.ai.ModelCatalog
import com.lazydog.english.core.ai.OpenAiContentGenerator
import com.lazydog.english.core.backup.BackupFileStore
import com.lazydog.english.core.backup.BackupRepository
import com.lazydog.english.core.data.AssetDictionary
import com.lazydog.english.core.data.BookCatalogService
import com.lazydog.english.core.data.BookRepository
import com.lazydog.english.core.data.NativeBookService
import com.lazydog.english.core.network.Ao3Client
import com.lazydog.english.core.network.GutendexClient
import com.lazydog.english.core.network.WikisourceClient
import com.lazydog.english.core.data.DictionaryBackfillWorker
import com.lazydog.english.core.data.DictionaryNewWords
import com.lazydog.english.core.data.AssetPhonemeCatalog
import com.lazydog.english.core.data.AssetWordFrequencyIndex
import com.lazydog.english.core.data.KnowledgeRepository
import com.lazydog.english.core.data.ListeningMaterialRepository
import com.lazydog.english.core.data.MemoryHintRepository
import com.lazydog.english.core.data.MistakeRepository
import com.lazydog.english.core.data.ProgressRepository
import com.lazydog.english.core.data.PronunciationRepository
import com.lazydog.english.core.data.ReadingRepository
import com.lazydog.english.core.data.ScenarioSessionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import com.lazydog.english.core.data.UserPreferences
import com.lazydog.english.core.data.WebReadingService
import com.lazydog.english.core.network.SimpleWikipediaClient
import com.lazydog.english.core.network.VoaLearningEnglishClient
import com.lazydog.english.core.database.AppDatabase
import com.lazydog.english.core.data.VocabularyImageRepository
import com.lazydog.english.core.network.BraveImageSearchClient
import com.lazydog.english.core.network.ImageDownloader
import com.lazydog.english.core.network.LocalImageDownloader
import com.lazydog.english.core.network.BraveSearchClient
import com.lazydog.english.core.network.DeepSeekSearchClient
import com.lazydog.english.core.network.VendorWebSearch
import com.lazydog.english.core.network.FallbackNewsFeed
import com.lazydog.english.core.network.GoogleNewsRssClient
import com.lazydog.english.core.network.HttpArticleFetcher
import com.lazydog.english.domain.generation.NewsArticleLoader
import com.lazydog.english.domain.generation.NewsFeed
import com.lazydog.english.core.network.ImageSearchProvider
import com.lazydog.english.domain.generation.WebSearchProvider
import com.lazydog.english.core.speech.SpeechController
import com.lazydog.english.domain.generation.LearningContentGenerator
import com.lazydog.english.domain.scheduling.FsrsScheduler
import com.lazydog.english.domain.pronunciation.PhonemeCatalog
import com.lazydog.english.domain.vocabulary.DictionaryLookup
import com.lazydog.english.domain.vocabulary.WordFrequencyIndex

/**
 * 手工组装的应用级单例。依赖关系还很浅，先不上 Hilt（ARCHITECTURE.md §2）。
 */
class LazyDogApplication : Application() {

    /**
     * 跟着进程活的协程作用域：页面关闭时还要写完的那一下（阅读器退出时存进度和阅读时长）放这里，
     * 页面自己的作用域在离开时已经取消了。
     */
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // 词典换了新版本（随 App 更新），老卡要重新和它对齐；已经做完就是一次空跑（D-095）。
        DictionaryBackfillWorker.enqueueIfNeeded(this)
    }

    val database: AppDatabase by lazy { AppDatabase.create(this) }

    val userPreferences: UserPreferences by lazy { UserPreferences(this) }

    val knowledgeRepository: KnowledgeRepository by lazy {
        KnowledgeRepository(database, FsrsScheduler())
    }

    val mistakeRepository: MistakeRepository by lazy { MistakeRepository(database) }

    /** 词频表：给"下一批学什么"排优先级，读 assets，失败退化成空索引。 */
    val wordFrequencyIndex: WordFrequencyIndex by lazy { AssetWordFrequencyIndex(this) }

    /** 内置离线词典：单词卡的词形、词性、释义、音标从这里来（D-095）。打不开退化成查不到。 */
    val dictionary: DictionaryLookup by lazy { AssetDictionary(this) }

    /** 每日新词走词典：挑词选义项、补教学材料、写生成信息（D-095）。 */
    val dictionaryNewWords: DictionaryNewWords by lazy { DictionaryNewWords(dictionary, contentGenerator) }

    /** 进步证据：不存新数据，从既有学习事件里推（`持续学习DESIGN.md` §14）。 */
    val progressRepository: ProgressRepository by lazy { ProgressRepository(database) }

    /** 记忆提示要现生成，所以这个仓储拿着生成器；contentGenerator 本身仍然是懒的。 */
    val memoryHintRepository: MemoryHintRepository by lazy {
        MemoryHintRepository(database, contentGenerator)
    }

    val speechController: SpeechController by lazy { SpeechController(this, userPreferences) }

    /** 音位表：内容不是状态，走 assets 不进库（D-077）。读不出来退化成空目录。 */
    val phonemeCatalog: PhonemeCatalog by lazy { AssetPhonemeCatalog(this) }

    /** 发音与音标：听辨和发音各记各的，页面不碰 DAO 也不碰 assets。 */
    val pronunciationRepository: PronunciationRepository by lazy {
        PronunciationRepository(database, phonemeCatalog)
    }

    val readingRepository: ReadingRepository by lazy { ReadingRepository(database) }

    /** 读书：导入、书架、章节正文、进度、书签（D-098 R2）。 */
    val bookRepository: BookRepository by lazy { BookRepository(this, database) }

    /** 中文书的母语阅读：本地分词、选词、全书替换（D-098 R4）。 */
    val nativeBookService: NativeBookService by lazy {
        NativeBookService(this, bookRepository, knowledgeRepository, userPreferences, dictionary)
    }

    /** 在线书库：古腾堡、维基文库、AO3（D-098 R3）。 */
    val bookCatalogService: BookCatalogService by lazy {
        BookCatalogService(this, GutendexClient(), WikisourceClient(), Ao3Client(), bookRepository)
    }

    /**
     * 联网检索。母语阅读写时效内容之前要先有可信事实（`母语阅读DESIGN.md` §36.7），
     * 但它是可选增强：没配密钥就是"这次不搜"，不是错误，文章照常生成。
     */
    val webSearch: WebSearchProvider by lazy {
        // Brave 或 DeepSeek，按设置现选（D-090）。配图不走这里，见 [imageSearch]。
        VendorWebSearch(
            vendor = { userPreferences.searchVendor.first() },
            brave = BraveSearchClient(apiKey = { userPreferences.braveApiKey.first() }),
            deepSeek = DeepSeekSearchClient(apiKey = { userPreferences.deepSeekApiKey }),
        )
    }

    /** 母语阅读「今日热点」的新闻列表：Google 新闻 RSS 为主，连不上时用新闻检索兜底（D-088、D-090）。 */
    val newsFeed: NewsFeed by lazy { FallbackNewsFeed(primary = GoogleNewsRssClient(), brave = webSearch) }

    /** 挑中的新闻去哪找原文、怎么抽正文（D-088）。 */
    val newsArticleLoader: NewsArticleLoader by lazy { NewsArticleLoader(search = webSearch, fetcher = HttpArticleFetcher()) }

    /** 日常阅读的网上材料：VOA、简明维基、英文新闻原文（D-098 R1）。 */
    val webReadingService: WebReadingService by lazy {
        WebReadingService(
            voa = VoaLearningEnglishClient(),
            wiki = SimpleWikipediaClient(),
            newsFeed = newsFeed,
            newsLoader = newsArticleLoader,
            dictionary = dictionary,
            readings = readingRepository,
            knowledge = knowledgeRepository,
            preferences = userPreferences,
        )
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
