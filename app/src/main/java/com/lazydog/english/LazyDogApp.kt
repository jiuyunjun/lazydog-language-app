package com.lazydog.english

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.lazydog.english.core.ai.AiTask
import com.lazydog.english.core.data.KnowledgeRepository
import com.lazydog.english.core.data.UserPreferences
import com.lazydog.english.feature.ask.AskHost
import com.lazydog.english.core.speech.SpeechController
import com.lazydog.english.feature.main.MainScreen
import com.lazydog.english.feature.production.ProductionScreen
import com.lazydog.english.feature.settings.ModelPickScreen
import com.lazydog.english.feature.settings.ModelSettingsScreen
import com.lazydog.english.feature.onboarding.GoalsScreen
import com.lazydog.english.feature.onboarding.WelcomeScreen
import com.lazydog.english.feature.assessment.AssessmentScreen
import com.lazydog.english.feature.reading.ReadingMode
import com.lazydog.english.feature.reading.ReadingScreen
import com.lazydog.english.feature.speaking.SpeakingScreen
import com.lazydog.english.feature.scenario.ScenarioScreen
import com.lazydog.english.feature.listening.ListeningScreen
import com.lazydog.english.feature.study.GrammarStudyScreen
import com.lazydog.english.feature.spelling.SpellingProfileScreen
import com.lazydog.english.feature.spelling.SpellingScreen
import com.lazydog.english.feature.study.WordStudyScreen
import kotlinx.coroutines.launch

object Routes {
    const val Onboarding = "onboarding"
    const val OnboardingWelcome = "onboarding/welcome"
    const val OnboardingGoals = "onboarding/goals"
    const val Main = "main"
    const val Speaking = "speaking"
    const val Listening = "listening"
    const val WordStudy = "study/words"
    const val Spelling = "study/spelling"
    const val SpellingProfile = "study/spelling/profile"
    const val GrammarStudy = "study/grammar"
    const val Production = "study/production"
    const val Assessment = "assessment"
    const val ModelSettings = "settings/models"
    const val ModelPick = "settings/models/{task}"
    const val Scenario = "scenario/new"
    const val ScenarioOpen = "scenario/open/{sessionId}"
    const val ReadingGenerate = "reading/generate"
    const val ReadingPaste = "reading/paste"
    const val ReadingOpen = "reading/open/{materialId}"

    /** [taskKey] 传 [DEFAULT_MODEL_KEY] 表示改的是默认模型。 */
    fun modelPick(taskKey: String) = "settings/models/$taskKey"

    const val DEFAULT_MODEL_KEY = "default"

    fun readingOpen(materialId: Long) = "reading/open/$materialId"
    fun scenarioOpen(sessionId: Long) = "scenario/open/$sessionId"
}

@Composable
fun LazyDogApp() {
    val context = LocalContext.current
    val app = remember { context.applicationContext as LazyDogApplication }
    val prefs = app.userPreferences
    val onboardingCompleted by prefs.onboardingCompleted.collectAsState(initial = null)

    StopSpeakingWhenNotVisible(app.speechController)

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
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
            if (event == Lifecycle.Event.ON_STOP) speech.stopSpeaking()
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
        val listener = NavController.OnDestinationChangedListener { _, _, _ -> speech.stopSpeaking() }
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
            navigateBack = { navController.popBackStack() },
            finishOnboarding = {
                scope.launch {
                    prefs.setOnboardingCompleted()
                    navController.navigate(Routes.Main) {
                        popUpTo(Routes.Onboarding) { inclusive = true }
                    }
                }
            },
        )

        composable(Routes.Main) {
            MainScreen(
                prefs = prefs,
                knowledgeRepository = knowledgeRepository,
                onStartSpeaking = { navController.navigate(Routes.Speaking) },
                onStartListening = { navController.navigate(Routes.Listening) },
                onStartWordStudy = { navController.navigate(Routes.WordStudy) },
                onStartSpelling = { navController.navigate(Routes.Spelling) },
                onStartGrammarStudy = { navController.navigate(Routes.GrammarStudy) },
                onStartProduction = { navController.navigate(Routes.Production) },
                onStartReading = { navController.navigate(Routes.ReadingGenerate) },
                onStartReadingPaste = { navController.navigate(Routes.ReadingPaste) },
                onStartScenario = { navController.navigate(Routes.Scenario) },
                onOpenScenario = { id -> navController.navigate(Routes.scenarioOpen(id)) },
                onOpenMaterial = { id -> navController.navigate(Routes.readingOpen(id)) },
                onStartAssessment = { navController.navigate(Routes.Assessment) },
                onOpenModelSettings = { navController.navigate(Routes.ModelSettings) },
            )
        }

        composable(Routes.ModelSettings) {
            ModelSettingsScreen(
                prefs = prefs,
                onPick = { task ->
                    navController.navigate(Routes.modelPick(task?.key ?: Routes.DEFAULT_MODEL_KEY))
                },
                onExit = { navController.popBackStack() },
            )
        }

        composable(
            route = Routes.ModelPick,
            arguments = listOf(navArgument("task") { type = NavType.StringType }),
        ) { entry ->
            ModelPickScreen(
                prefs = prefs,
                task = entry.arguments?.getString("task")?.let(AiTask::fromKey),
                onExit = { navController.popBackStack() },
            )
        }

        composable(Routes.Assessment) {
            AssessmentScreen(onExit = { navController.popBackStack() })
        }

        composable(Routes.Speaking) {
            SpeakingScreen(
                prefs = prefs,
                repository = knowledgeRepository,
                onExit = { navController.popBackStack() },
            )
        }

        // 学习类页面包一层 AskHost：摇一摇提问只在这些页面可用（DESIGN 屏 45～49）。
        composable(Routes.Listening) {
            AskHost {
                ListeningScreen(onExit = { navController.popBackStack() })
            }
        }

        composable(Routes.Scenario) {
            AskHost {
                ScenarioScreen(sessionId = null, onExit = { navController.popBackStack() })
            }
        }

        composable(
            route = Routes.ScenarioOpen,
            arguments = listOf(navArgument("sessionId") { type = NavType.LongType }),
        ) { entry ->
            AskHost {
                ScenarioScreen(
                    sessionId = entry.arguments?.getLong("sessionId"),
                    onExit = { navController.popBackStack() },
                )
            }
        }

        composable(Routes.WordStudy) {
            AskHost {
                WordStudyScreen(
                    repository = knowledgeRepository,
                    onExit = { navController.popBackStack() },
                )
            }
        }

        composable(Routes.Spelling) {
            AskHost {
                SpellingScreen(
                    repository = knowledgeRepository,
                    onExit = { navController.popBackStack() },
                    onOpenProfile = { navController.navigate(Routes.SpellingProfile) },
                )
            }
        }

        composable(Routes.SpellingProfile) {
            SpellingProfileScreen(
                repository = knowledgeRepository,
                onBack = { navController.popBackStack() },
                onStartPractice = {
                    navController.navigate(Routes.Spelling) {
                        popUpTo(Routes.SpellingProfile) { inclusive = true }
                    }
                },
            )
        }

        composable(Routes.GrammarStudy) {
            AskHost {
                GrammarStudyScreen(
                    repository = knowledgeRepository,
                    onExit = { navController.popBackStack() },
                )
            }
        }

        composable(Routes.Production) {
            AskHost {
                ProductionScreen(onExit = { navController.popBackStack() })
            }
        }

        composable(Routes.ReadingGenerate) {
            AskHost {
                ReadingScreen(mode = ReadingMode.Generate, onExit = { navController.popBackStack() })
            }
        }

        composable(Routes.ReadingPaste) {
            AskHost {
                ReadingScreen(mode = ReadingMode.Paste, onExit = { navController.popBackStack() })
            }
        }

        composable(
            route = Routes.ReadingOpen,
            arguments = listOf(navArgument("materialId") { type = NavType.LongType }),
        ) { entry ->
            val materialId = entry.arguments?.getLong("materialId") ?: 0L
            AskHost {
                ReadingScreen(mode = ReadingMode.Open(materialId), onExit = { navController.popBackStack() })
            }
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
        composable(Routes.OnboardingWelcome) {
            WelcomeScreen(
                onStart = { navigateNext(Routes.OnboardingGoals) },
                onRestored = finishOnboarding,
            )
        }
        // AI 服务与朗读服务改为读取 LocalEnv 写死的本地配置，不再让用户在 onboarding 里填写。
        // 对应的 AiServiceScreen / SpeechScreen 暂时保留，后续开放手动配置时再接回来。
        composable(Routes.OnboardingGoals) {
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
