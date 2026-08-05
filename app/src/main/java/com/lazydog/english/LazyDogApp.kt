package com.lazydog.english

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.lazydog.english.core.data.KnowledgeRepository
import com.lazydog.english.core.data.UserPreferences
import com.lazydog.english.feature.main.MainScreen
import com.lazydog.english.feature.onboarding.GoalsScreen
import com.lazydog.english.feature.onboarding.WelcomeScreen
import com.lazydog.english.feature.assessment.AssessmentScreen
import com.lazydog.english.feature.reading.ReadingMode
import com.lazydog.english.feature.reading.ReadingScreen
import com.lazydog.english.feature.speaking.SpeakingScreen
import com.lazydog.english.feature.scenario.ScenarioScreen
import com.lazydog.english.feature.study.GrammarStudyScreen
import com.lazydog.english.feature.study.WordStudyScreen
import kotlinx.coroutines.launch

object Routes {
    const val Onboarding = "onboarding"
    const val OnboardingWelcome = "onboarding/welcome"
    const val OnboardingGoals = "onboarding/goals"
    const val Main = "main"
    const val Speaking = "speaking"
    const val WordStudy = "study/words"
    const val GrammarStudy = "study/grammar"
    const val Assessment = "assessment"
    const val Scenario = "scenario/new"
    const val ScenarioOpen = "scenario/open/{sessionId}"
    const val ReadingGenerate = "reading/generate"
    const val ReadingPaste = "reading/paste"
    const val ReadingOpen = "reading/open/{materialId}"

    fun readingOpen(materialId: Long) = "reading/open/$materialId"
    fun scenarioOpen(sessionId: Long) = "scenario/open/$sessionId"
}

@Composable
fun LazyDogApp() {
    val context = LocalContext.current
    val app = remember { context.applicationContext as LazyDogApplication }
    val prefs = app.userPreferences
    val onboardingCompleted by prefs.onboardingCompleted.collectAsState(initial = null)

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

@Composable
private fun AppNavHost(
    prefs: UserPreferences,
    knowledgeRepository: KnowledgeRepository,
    startAtMain: Boolean,
) {
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()

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
                onStartWordStudy = { navController.navigate(Routes.WordStudy) },
                onStartGrammarStudy = { navController.navigate(Routes.GrammarStudy) },
                onStartReading = { navController.navigate(Routes.ReadingGenerate) },
                onStartReadingPaste = { navController.navigate(Routes.ReadingPaste) },
                onStartScenario = { navController.navigate(Routes.Scenario) },
                onOpenScenario = { id -> navController.navigate(Routes.scenarioOpen(id)) },
                onOpenMaterial = { id -> navController.navigate(Routes.readingOpen(id)) },
                onStartAssessment = { navController.navigate(Routes.Assessment) },
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

        composable(Routes.Scenario) {
            ScenarioScreen(sessionId = null, onExit = { navController.popBackStack() })
        }

        composable(
            route = Routes.ScenarioOpen,
            arguments = listOf(navArgument("sessionId") { type = NavType.LongType }),
        ) { entry ->
            ScenarioScreen(
                sessionId = entry.arguments?.getLong("sessionId"),
                onExit = { navController.popBackStack() },
            )
        }

        composable(Routes.WordStudy) {
            WordStudyScreen(
                repository = knowledgeRepository,
                onExit = { navController.popBackStack() },
            )
        }

        composable(Routes.GrammarStudy) {
            GrammarStudyScreen(
                repository = knowledgeRepository,
                onExit = { navController.popBackStack() },
            )
        }

        composable(Routes.ReadingGenerate) {
            ReadingScreen(mode = ReadingMode.Generate, onExit = { navController.popBackStack() })
        }

        composable(Routes.ReadingPaste) {
            ReadingScreen(mode = ReadingMode.Paste, onExit = { navController.popBackStack() })
        }

        composable(
            route = Routes.ReadingOpen,
            arguments = listOf(navArgument("materialId") { type = NavType.LongType }),
        ) { entry ->
            val materialId = entry.arguments?.getLong("materialId") ?: 0L
            ReadingScreen(mode = ReadingMode.Open(materialId), onExit = { navController.popBackStack() })
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
