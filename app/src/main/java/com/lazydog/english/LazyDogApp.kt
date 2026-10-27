package com.lazydog.english

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.lazydog.english.core.ai.AiTask
import com.lazydog.english.core.data.KnowledgeRepository
import com.lazydog.english.core.data.UserPreferences
import com.lazydog.english.core.ask.AskPagePolicy
import com.lazydog.english.core.speech.SpeechController
import com.lazydog.english.feature.main.MainScreen
import com.lazydog.english.feature.production.ProductionScreen
import com.lazydog.english.feature.pronunciation.AllSoundsScreen
import com.lazydog.english.feature.pronunciation.PerceptionScreen
import com.lazydog.english.feature.pronunciation.ProduceScreen
import com.lazydog.english.feature.pronunciation.PronunciationHomeScreen
import com.lazydog.english.feature.pronunciation.VoiceProfileScreen
import com.lazydog.english.feature.pronunciation.VoiceScreeningScreen
import com.lazydog.english.feature.pronunciation.SoundCardScreen
import com.lazydog.english.feature.proof.ProofChallengeScreen
import com.lazydog.english.feature.settings.ModelPickScreen
import com.lazydog.english.feature.settings.ModelSettingsScreen
import com.lazydog.english.feature.onboarding.GoalsScreen
import com.lazydog.english.feature.onboarding.WelcomeScreen
import com.lazydog.english.feature.assessment.AssessmentScreen
import com.lazydog.english.feature.reading.NativeReadingMode
import com.lazydog.english.feature.reading.NativeReadingScreen
import com.lazydog.english.feature.reading.ReadingMode
import com.lazydog.english.feature.reading.ReadingScreen
import com.lazydog.english.feature.speaking.SpeakingScreen
import com.lazydog.english.feature.scenario.ScenarioScreen
import com.lazydog.english.feature.listening.ListeningProfileScreen
import com.lazydog.english.feature.listening.ListeningScreen
import com.lazydog.english.feature.study.GrammarStudyScreen
import com.lazydog.english.feature.spelling.SpellingProfileScreen
import com.lazydog.english.feature.spelling.SpellingScreen
import com.lazydog.english.feature.study.WordStudyScreen
import com.lazydog.english.feature.vocabulary.WordDetailScreen
import kotlinx.coroutines.launch

object Routes {
    const val Onboarding = "onboarding"
    const val OnboardingWelcome = "onboarding/welcome"
    const val OnboardingGoals = "onboarding/goals"
    const val Main = "main"
    const val Speaking = "speaking"
    const val Listening = "listening"
    const val AddWordCard = "library/add-word"
    const val AddGrammarCard = "library/add-grammar"
    const val ScanWords = "library/scan"
    const val WordStudy = "study/words"
    const val Spelling = "study/spelling"
    const val SpellingProfile = "study/spelling/profile"
    const val ListeningProfile = "listening/profile"
    const val Pronunciation = "pronunciation"
    const val PronunciationAllSounds = "pronunciation/sounds"
    const val PronunciationProfile = "pronunciation/profile"
    const val PronunciationScreening = "pronunciation/screening"
    const val PronunciationSound = "pronunciation/sound/{phonemeId}"
    const val PronunciationPerceive = "pronunciation/perceive/{contrastId}"
    const val PronunciationProduce = "pronunciation/produce/{phonemeId}"
    const val GrammarStudy = "study/grammar"
    const val Production = "study/production"
    const val ProofChallenge = "study/proof"
    const val Assessment = "assessment"
    const val ModelSettings = "settings/models"
    const val ModelPick = "settings/models/{task}"
    const val Scenario = "scenario/new"
    const val ScenarioOpen = "scenario/open/{sessionId}"
    const val ReadingGenerate = "reading/generate"
    const val ReadingPaste = "reading/paste"
    const val ReadingOpen = "reading/open/{materialId}"
    const val NativeReading = "reading/native"
    const val NativeReadingOpen = "reading/native/open/{materialId}"
    const val WordDetail = "library/word/{itemId}"

    /** [taskKey] 传 [DEFAULT_MODEL_KEY] 表示改的是默认模型。 */
    fun modelPick(taskKey: String) = "settings/models/$taskKey"

    const val DEFAULT_MODEL_KEY = "default"

    fun readingOpen(materialId: Long) = "reading/open/$materialId"
    fun nativeReadingOpen(materialId: Long) = "reading/native/open/$materialId"
    fun wordDetail(itemId: Long) = "library/word/$itemId"
    fun scenarioOpen(sessionId: Long) = "scenario/open/$sessionId"
    fun pronunciationSound(phonemeId: String) = "pronunciation/sound/$phonemeId"
    fun pronunciationPerceive(contrastId: String) = "pronunciation/perceive/$contrastId"
    fun pronunciationProduce(phonemeId: String) = "pronunciation/produce/$phonemeId"
}

@Composable
fun LazyDogApp() {
    val context = LocalContext.current
    val app = remember { context.applicationContext as LazyDogApplication }
    val prefs = app.userPreferences
    val onboardingCompleted by prefs.onboardingCompleted.collectAsState(initial = null)

    StopSpeakingWhenNotVisible(app.speechController)

    // 键盘弹出时整个界面往上让。
    //
    // `enableEdgeToEdge()` 之后窗口不再被系统自动压缩，manifest 里的 adjustResize 也就不起作用了：
    // 键盘只是一层 inset，没人消费它，界面就一动不动，输入框和它下面的按钮全被盖住。
    // 放在根上是因为这件事对每一屏都成立——各屏各写一次，漏掉哪屏就哪屏打不了字。
    // 弹窗和 ModalBottomSheet 是独立窗口，不在这棵树里，得各自处理。
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        when (onboardingCompleted) {
            null -> Box(Modifier.fillMaxSize()) // DataStore 首帧未就绪，避免闪错页面
            else -> AppNavHost(
                prefs = prefs,
                knowledgeRepository = app.knowledgeRepository,
                startAtMain = onboardingCompleted == true,
            )
        }
    }
}

/**
 * 界面不可见就停朗读：退到后台、锁屏、被别的 App 挡住都算。
 * 朗读的生命周期不该长过看得见它的界面——念到一半切走还在响，只会吓人一跳。
 */
@Composable
private fun StopSpeakingWhenNotVisible(speech: SpeechController) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, speech) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) speech.stop()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

@Composable
private fun AppNavHost(
    prefs: UserPreferences,
    knowledgeRepository: KnowledgeRepository,
    startAtMain: Boolean,
) {
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val speech = remember { (context.applicationContext as LazyDogApplication).speechController }

    // 换页面就停朗读：内容都换了，还在念上一页的句子只会让人莫名其妙。
    DisposableEffect(navController, speech) {
        val listener = NavController.OnDestinationChangedListener { _, _, _ -> speech.stop() }
        navController.addOnDestinationChangedListener(listener)
        onDispose { navController.removeOnDestinationChangedListener(listener) }
    }

    NavHost(
        navController = navController,
        startDestination = if (startAtMain) Routes.Main else Routes.Onboarding,
    ) {
        onboardingGraph(
            prefs = prefs,
            navigateNext = { route -> navController.navigate(route) },
            navigateBack = { navController.popOnce() },
            finishOnboarding = {
                scope.launch {
                    prefs.setOnboardingCompleted()
                    navController.navigate(Routes.Main) {
                        popUpTo(Routes.Onboarding) { inclusive = true }
                    }
                }
            },
        )

        appScreen(Routes.Main, ask = AskPagePolicy.Disabled("首页、学习目录、记录和设置不提供学习提问")) {
            MainScreen(
                prefs = prefs,
                knowledgeRepository = knowledgeRepository,
                onStartSpeaking = { navController.navigate(Routes.Speaking) },
                onStartListening = { navController.navigate(Routes.Listening) },
                onStartPronunciation = { navController.navigate(Routes.Pronunciation) },
                onStartWordStudy = { navController.navigate(Routes.WordStudy) },
                onStartSpelling = { navController.navigate(Routes.Spelling) },
                onStartGrammarStudy = { navController.navigate(Routes.GrammarStudy) },
                onStartProduction = { navController.navigate(Routes.Production) },
                onStartProofChallenge = { navController.navigate(Routes.ProofChallenge) },
                onStartReading = { navController.navigate(Routes.ReadingGenerate) },
                onStartNativeReading = { navController.navigate(Routes.NativeReading) },
                onStartReadingPaste = { navController.navigate(Routes.ReadingPaste) },
                onStartScenario = { navController.navigate(Routes.Scenario) },
                onOpenScenario = { id -> navController.navigate(Routes.scenarioOpen(id)) },
                onOpenMaterial = { id, native ->
                    navController.navigate(
                        if (native) Routes.nativeReadingOpen(id) else Routes.readingOpen(id),
                    )
                },
                onStartAssessment = { navController.navigate(Routes.Assessment) },
                onOpenModelSettings = { navController.navigate(Routes.ModelSettings) },
                onOpenWord = { id -> navController.navigate(Routes.wordDetail(id)) },
                onAddCard = { isWord -> navController.navigate(if (isWord) Routes.AddWordCard else Routes.AddGrammarCard) },
                onScanWords = { navController.navigate(Routes.ScanWords) },
            )
        }

        appScreen(Routes.ScanWords, ask = AskPagePolicy.Disabled("扫一扫取景页没有学习正文，查词与讲句在面板里进行")) {
            com.lazydog.english.feature.library.ScanWordsScreen(onExit = { navController.popOnce() })
        }

        listOf(Routes.AddWordCard, Routes.AddGrammarCard).forEach { route ->
            appScreen(route, ask = AskPagePolicy.Disabled("记录中的学习卡草稿")) {
                com.lazydog.english.feature.library.AddLearningCardScreen(
                    isVocab = route == Routes.AddWordCard,
                    app = context.applicationContext as LazyDogApplication,
                    repository = knowledgeRepository,
                    onDismiss = { navController.popOnce() },
                )
            }
        }

        appScreen(Routes.ModelSettings, ask = AskPagePolicy.Disabled("模型设置")) {
            ModelSettingsScreen(
                prefs = prefs,
                onPick = { task ->
                    navController.navigate(Routes.modelPick(task?.key ?: Routes.DEFAULT_MODEL_KEY))
                },
                onExit = { navController.popOnce() },
            )
        }

        appScreen(
            route = Routes.ModelPick,
            ask = AskPagePolicy.Disabled("模型设置"),
            arguments = listOf(navArgument("task") { type = NavType.StringType }),
        ) { entry ->
            ModelPickScreen(
                prefs = prefs,
                task = entry.arguments?.getString("task")?.let(AiTask::fromKey),
                onExit = { navController.popOnce() },
            )
        }

        appScreen(Routes.Assessment, ask = AskPagePolicy.Disabled("能力测评期间不提供辅助答案")) {
            AssessmentScreen(onExit = { navController.popOnce() })
        }

        appScreen(Routes.Speaking, ask = AskPagePolicy.Required) {
            SpeakingScreen(
                prefs = prefs,
                repository = knowledgeRepository,
                onExit = { navController.popOnce() },
            )
        }

        // 所有页面走 appScreen；提问策略没有默认值，新增页面必须明确选择。
        appScreen(Routes.Listening, ask = AskPagePolicy.Required) {
            ListeningScreen(
                onExit = { navController.popOnce() },
                onOpenProfile = { navController.navigate(Routes.ListeningProfile) },
            )
        }

        appScreen(Routes.Scenario, ask = AskPagePolicy.Required) {
            ScenarioScreen(sessionId = null, onExit = { navController.popOnce() })
        }

        appScreen(
            route = Routes.ScenarioOpen,
            ask = AskPagePolicy.Required,
            arguments = listOf(navArgument("sessionId") { type = NavType.LongType }),
        ) { entry ->
            ScenarioScreen(
                sessionId = entry.arguments?.getLong("sessionId"),
                onExit = { navController.popOnce() },
            )
        }

        appScreen(Routes.WordStudy, ask = AskPagePolicy.Required) {
            WordStudyScreen(
                repository = knowledgeRepository,
                onExit = { navController.popOnce() },
            )
        }

        appScreen(Routes.Spelling, ask = AskPagePolicy.Required) {
            SpellingScreen(
                repository = knowledgeRepository,
                onExit = { navController.popOnce() },
                onOpenProfile = { navController.navigate(Routes.SpellingProfile) },
            )
        }

        appScreen(Routes.ListeningProfile, ask = AskPagePolicy.Disabled("听力统计")) {
            ListeningProfileScreen(onExit = { navController.popBackStack() })
        }

        appScreen(Routes.Pronunciation, ask = AskPagePolicy.Disabled("发音训练目录")) {
            PronunciationHomeScreen(
                onExit = { navController.popOnce() },
                onScreening = { navController.navigate(Routes.PronunciationScreening) },
                onPractice = { id -> navController.navigate(Routes.pronunciationPerceive(id)) },
                onOpenAllSounds = { navController.navigate(Routes.PronunciationAllSounds) },
                onOpenProfile = { navController.navigate(Routes.PronunciationProfile) },
            )
        }

        appScreen(Routes.PronunciationAllSounds, ask = AskPagePolicy.Disabled("音位目录，具体讲解在音位卡")) {
            AllSoundsScreen(
                onExit = { navController.popOnce() },
                onOpenSound = { id -> navController.navigate(Routes.pronunciationSound(id)) },
                onPracticeContrast = { id -> navController.navigate(Routes.pronunciationPerceive(id)) },
            )
        }

        appScreen(Routes.PronunciationScreening, ask = AskPagePolicy.Disabled("首次摸底期间不提供辅助答案")) {
            VoiceScreeningScreen(
                onExit = { navController.popOnce() },
                onPractice = { id ->
                    navController.popOnce()
                    navController.navigate(Routes.pronunciationPerceive(id))
                },
            )
        }

        appScreen(Routes.PronunciationProfile, ask = AskPagePolicy.Disabled("发音统计")) {
            VoiceProfileScreen(
                onExit = { navController.popOnce() },
                onPractice = { id -> navController.navigate(Routes.pronunciationPerceive(id)) },
            )
        }

        appScreen(
            route = Routes.PronunciationSound,
            ask = AskPagePolicy.Required,
            arguments = listOf(navArgument("phonemeId") { type = NavType.StringType }),
        ) { entry ->
            val app = context.applicationContext as LazyDogApplication
            val phonemeId = entry.arguments?.getString("phonemeId").orEmpty()
            SoundCardScreen(
                phonemeId = phonemeId,
                onExit = { navController.popOnce() },
                onProduce = { id -> navController.navigate(Routes.pronunciationProduce(id)) },
                onPractice = { id ->
                    // 练一个音位，实际练的是它参与的第一组对比：孤立地听一个音建立不了类别，
                    // 边界要靠对比才立得起来（设计文档 §4.2）。
                    val contrast = app.phonemeCatalog.contrastsOf(id).firstOrNull()
                    if (contrast != null) {
                        navController.navigate(Routes.pronunciationPerceive(contrast.id))
                    }
                },
            )
        }

        appScreen(
            route = Routes.PronunciationProduce,
            ask = AskPagePolicy.Required,
            arguments = listOf(navArgument("phonemeId") { type = NavType.StringType }),
        ) { entry ->
            ProduceScreen(
                phonemeId = entry.arguments?.getString("phonemeId").orEmpty(),
                onExit = { navController.popOnce() },
            )
        }

        appScreen(
            route = Routes.PronunciationPerceive,
            ask = AskPagePolicy.Required,
            arguments = listOf(navArgument("contrastId") { type = NavType.StringType }),
        ) { entry ->
            PerceptionScreen(
                contrastId = entry.arguments?.getString("contrastId").orEmpty(),
                onExit = { navController.popOnce() },
            )
        }

        appScreen(Routes.SpellingProfile, ask = AskPagePolicy.Disabled("拼写统计")) {
            SpellingProfileScreen(
                repository = knowledgeRepository,
                onBack = { navController.popOnce() },
                onStartPractice = {
                    // 回到已经在栈里的那一页，而不是再压一个同名页上去；
                    // 否则退出时要连按两次「拼写练习」才出得来。
                    navController.navigate(Routes.Spelling) {
                        popUpTo(Routes.Spelling) { inclusive = true }
                    }
                },
            )
        }

        appScreen(Routes.GrammarStudy, ask = AskPagePolicy.Required) {
            GrammarStudyScreen(
                repository = knowledgeRepository,
                onExit = { navController.popOnce() },
            )
        }

        appScreen(Routes.ProofChallenge, ask = AskPagePolicy.Disabled("独立小测期间不提供辅助答案")) {
            ProofChallengeScreen(onExit = { navController.popBackStack() })
        }

        appScreen(Routes.Production, ask = AskPagePolicy.Required) {
            ProductionScreen(onExit = { navController.popOnce() })
        }

        appScreen(Routes.ReadingGenerate, ask = AskPagePolicy.Required) {
            ReadingScreen(mode = ReadingMode.Generate, onExit = { navController.popOnce() })
        }

        appScreen(Routes.NativeReading, ask = AskPagePolicy.Required) {
            NativeReadingScreen(mode = NativeReadingMode.New, onExit = { navController.popOnce() })
        }

        appScreen(
            route = Routes.NativeReadingOpen,
            ask = AskPagePolicy.Required,
            arguments = listOf(navArgument("materialId") { type = NavType.LongType }),
        ) { entry ->
            val materialId = entry.arguments?.getLong("materialId") ?: 0L
            NativeReadingScreen(
                mode = NativeReadingMode.Open(materialId),
                onExit = { navController.popOnce() },
            )
        }

        appScreen(Routes.ReadingPaste, ask = AskPagePolicy.Required) {
            ReadingScreen(mode = ReadingMode.Paste, onExit = { navController.popOnce() })
        }

        appScreen(
            route = Routes.WordDetail,
            ask = AskPagePolicy.Disabled("记录详情按 UI_BRIEF 禁用提问"),
            arguments = listOf(navArgument("itemId") { type = NavType.LongType }),
        ) { entry ->
            // 记录详情明确禁用提问；正文手势仍独立可用。
            WordDetailScreen(
                itemId = entry.arguments?.getLong("itemId") ?: 0L,
                repository = knowledgeRepository,
                onExit = { navController.popOnce() },
            )
        }

        appScreen(
            route = Routes.ReadingOpen,
            ask = AskPagePolicy.Required,
            arguments = listOf(navArgument("materialId") { type = NavType.LongType }),
        ) { entry ->
            val materialId = entry.arguments?.getLong("materialId") ?: 0L
            ReadingScreen(mode = ReadingMode.Open(materialId), onExit = { navController.popOnce() })
        }
    }
}

private fun NavGraphBuilder.onboardingGraph(
    prefs: UserPreferences,
    navigateNext: (String) -> Unit,
    navigateBack: () -> Unit,
    finishOnboarding: () -> Unit,
) {
    navigation(startDestination = Routes.OnboardingWelcome, route = Routes.Onboarding) {
        appScreen(Routes.OnboardingWelcome, ask = AskPagePolicy.Disabled("首次设置")) {
            WelcomeScreen(
                onStart = { navigateNext(Routes.OnboardingGoals) },
                onRestored = finishOnboarding,
            )
        }
        // AI 服务与朗读服务改为读取 LocalEnv 写死的本地配置，不再让用户在 onboarding 里填写。
        // 对应的 AiServiceScreen / SpeechScreen 暂时保留，后续开放手动配置时再接回来。
        appScreen(Routes.OnboardingGoals, ask = AskPagePolicy.Disabled("首次设置")) {
            val scope = rememberCoroutineScope()
            GoalsScreen(
                onBack = navigateBack,
                onNext = { goal, topics, minutes ->
                    scope.launch {
                        prefs.saveLearningGoals(goal, topics, minutes)
                        finishOnboarding()
                    }
                },
            )
        }
    }
}

/**
 * 页面内「返回 / 关闭」用的退栈，防连点。
 *
 * 直接调 [NavHostController.popBackStack] 有两个坑，快按两下会同时踩中：
 * 转场还没完成时第二下照样派发，于是第一下弹掉当前页、第二下把起始页也弹了；
 * back stack 一空，NavHost 就没有目的地可画——界面直接白掉，而且退不回来。
 *
 * 所以两道闸：当前页真正在前台（RESUMED）才认这一下，转场途中的第二下丢掉；
 * 起始页不由页面内的返回键弹掉，那是系统返回键的事。
 */
private fun NavHostController.popOnce() {
    val current = currentBackStackEntry ?: return
    // 转场途中的第二下：这一页已经不在前台了，丢掉。
    if (!current.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
    // 转场瞬间完成时上面那道闸拦不住（两下都落在真实状态上），所以再挡一道：
    // 起始页永远不由页面内的返回键弹掉，那是系统返回键的事。
    if (current.destination.id == graph.findStartDestination().id) return
    popBackStack()
}
