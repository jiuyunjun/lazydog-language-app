package com.lazydog.english.feature.main

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.lazydog.english.core.data.KnowledgeRepository
import com.lazydog.english.core.data.UserPreferences
import com.lazydog.english.feature.library.LibraryScreen
import com.lazydog.english.feature.settings.SettingsScreen
import com.lazydog.english.feature.study.StudyScreen
import com.lazydog.english.feature.today.TodayScreen

enum class MainTab(val label: String) {
    Today("今天"),
    Study("学习"),
    Library("记录"),
    Settings("设置"),
}

private val MainTab.icon: ImageVector
    get() = when (this) {
        MainTab.Today -> Icons.Outlined.Today
        MainTab.Study -> Icons.Outlined.School
        MainTab.Library -> Icons.Outlined.Inventory2
        MainTab.Settings -> Icons.Outlined.Settings
    }

@Composable
fun MainScreen(
    prefs: UserPreferences,
    knowledgeRepository: KnowledgeRepository,
    onStartSession: () -> Unit,
    onStartSpeaking: () -> Unit,
    onStartWordStudy: () -> Unit,
    onStartGrammarStudy: () -> Unit,
    onStartReading: () -> Unit,
    onStartReadingPaste: () -> Unit,
    onOpenMaterial: (Long) -> Unit,
    onStartAssessment: () -> Unit,
) {
    var currentTab by rememberSaveable { mutableStateOf(MainTab.Today) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                MainTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = currentTab == tab,
                        onClick = { currentTab = tab },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        val contentModifier = Modifier
            .fillMaxSize()
            .padding(padding)
        when (currentTab) {
            MainTab.Today -> TodayScreen(
                modifier = contentModifier,
                onStartSession = onStartSession,
                onFreeStudy = { currentTab = MainTab.Study },
                onStartAssessment = onStartAssessment,
            )
            MainTab.Study -> StudyScreen(
                modifier = contentModifier,
                onSpeakingClick = onStartSpeaking,
                onWordsClick = onStartWordStudy,
                onGrammarClick = onStartGrammarStudy,
                onReadingClick = onStartReading,
                onPasteClick = onStartReadingPaste,
                onMaterialClick = onOpenMaterial,
            )
            MainTab.Library -> LibraryScreen(
                modifier = contentModifier,
                repository = knowledgeRepository,
            )
            MainTab.Settings -> SettingsScreen(
                modifier = contentModifier,
                prefs = prefs,
                onStartAssessment = onStartAssessment,
            )
        }
    }
}
