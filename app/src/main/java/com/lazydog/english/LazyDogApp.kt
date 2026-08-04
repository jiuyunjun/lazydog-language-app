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
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import com.lazydog.english.core.data.UserPreferences
import com.lazydog.english.feature.main.MainScreen
import com.lazydog.english.feature.onboarding.AiServiceScreen
import com.lazydog.english.feature.onboarding.GoalsScreen
import com.lazydog.english.feature.onboarding.SpeechScreen
import com.lazydog.english.feature.onboarding.WelcomeScreen
import com.lazydog.english.feature.session.LearningSessionScreen
import kotlinx.coroutines.launch

object Routes {
    const val Onboarding = "onboarding"
    const val OnboardingWelcome = "onboarding/welcome"
    const val OnboardingAi = "onboarding/ai"
    const val OnboardingGoals = "onboarding/goals"
    const val OnboardingSpeech = "onboarding/speech"
    const val Main = "main"
    const val Session = "session"
}

@Composable
fun LazyDogApp() {
    val context = LocalContext.current
    val prefs = remember { UserPreferences(context.applicationContext) }
    val onboardingCompleted by prefs.onboardingCompleted.collectAsState(initial = null)

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        when (onboardingCompleted) {
            null -> Box(Modifier.fillMaxSize()) // DataStore 首帧未就绪，避免闪错页面
            else -> AppNavHost(prefs = prefs, startAtMain = onboardingCompleted == true)
        }
    }
}

@Composable
private fun AppNavHost(prefs: UserPreferences, startAtMain: Boolean) {
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
                onStartSession = { navController.navigate(Routes.Session) },
            )
        }

        composable(Routes.Session) {
            LearningSessionScreen(onExit = { navController.popBackStack() })
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
            WelcomeScreen(onStart = { navigateNext(Routes.OnboardingAi) })
        }
        composable(Routes.OnboardingAi) {
            val scope = rememberCoroutineScope()
            AiServiceScreen(
                onBack = navigateBack,
                onNext = { baseUrl, apiKey, model ->
                    scope.launch {
                        prefs.saveAiConfig(baseUrl, apiKey, model)
                        navigateNext(Routes.OnboardingGoals)
                    }
                },
            )
        }
        composable(Routes.OnboardingGoals) {
            val scope = rememberCoroutineScope()
            GoalsScreen(
                onBack = navigateBack,
                onNext = { goal, topics, minutes ->
                    scope.launch {
                        prefs.saveLearningGoals(goal, topics, minutes)
                        navigateNext(Routes.OnboardingSpeech)
                    }
                },
            )
        }
        composable(Routes.OnboardingSpeech) {
            SpeechScreen(
                onBack = navigateBack,
                onFinish = finishOnboarding,
            )
        }
    }
}
