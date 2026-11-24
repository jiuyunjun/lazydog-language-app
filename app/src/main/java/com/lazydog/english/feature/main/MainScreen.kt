package com.lazydog.english.feature.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.lazydog.english.core.data.KnowledgeRepository
import com.lazydog.english.core.data.UserPreferences
import com.lazydog.english.domain.planning.DailyStep
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

/**
 * 底栏正中的扫一扫。
 *
 * 不用 [NavigationBarItem]：它带「页签」语义和选中态，读屏会念成「标签页，未选定」，
 * 而这里点一下是打开相机，不是切换页面。圆钮用 primaryContainer，比页签图标醒目，
 * 又不和今天页的主按钮抢同一种实心主色。
 */
@Composable
private fun RowScope.ScanAction(onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .weight(1f)
            // 和页签同高。不能用 fillMaxHeight：底栏只有最小高度，会被撑满整屏。
            .height(80.dp)
            .clickable(role = Role.Button, onClickLabel = "打开扫一扫", onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = "扫一扫，用相机认书上或屏幕上的英文"
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        // 顶对齐并按页签的尺寸排（指示条顶 12dp、高 32dp、文字在其下 4dp）：
        // 40dp 的圆以指示条中线为心，文字才和两边页签落在同一条线上。
        verticalArrangement = Arrangement.Top,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(top = 8.dp).size(40.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.DocumentScanner, contentDescription = null, modifier = Modifier.size(24.dp))
            }
        }
        Text(
            text = "扫一扫",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
fun MainScreen(
    prefs: UserPreferences,
    knowledgeRepository: KnowledgeRepository,
    onStartSpeaking: () -> Unit,
    onStartListening: () -> Unit,
    onStartPronunciation: () -> Unit,
    onStartSpelling: () -> Unit,
    onStartWordStudy: () -> Unit,
    onStartGrammarStudy: () -> Unit,
    onStartProduction: () -> Unit,
    onStartProofChallenge: () -> Unit,
    onStartReading: () -> Unit,
    onStartReadingPaste: () -> Unit,
    onStartNativeReading: () -> Unit,
    onOpenBooks: () -> Unit,
    onStartScenario: () -> Unit,
    onOpenScenario: (Long) -> Unit,
    onOpenMaterial: (Long, Boolean) -> Unit,
    onStartAssessment: () -> Unit,
    onOpenModelSettings: () -> Unit,
    onOpenWord: (Long) -> Unit,
    onAddCard: (Boolean) -> Unit,
    onScanWords: () -> Unit,
) {
    var currentTab by rememberSaveable { mutableStateOf(MainTab.Today) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                // 扫一扫坐在四个页签正中（D-094）。它是一个动作不是页签：没有选中态，点了进全屏取景页。
                MainTab.entries.forEachIndexed { index, tab ->
                    if (index == MainTab.entries.size / 2) ScanAction(onClick = onScanWords)
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
                onStartAssessment = onStartAssessment,
                onStartStep = { step ->
                    when (step) {
                        DailyStep.Words -> onStartWordStudy()
                        DailyStep.Grammar -> onStartGrammarStudy()
                        DailyStep.Production -> onStartProduction()
                        DailyStep.Reading -> onStartReading()
                        DailyStep.Speaking -> onStartSpeaking()
                    }
                },
            )
            MainTab.Study -> StudyScreen(
                modifier = contentModifier,
                onSpeakingClick = onStartSpeaking,
                onListeningClick = onStartListening,
                onPronunciationClick = onStartPronunciation,
                onSpellingClick = onStartSpelling,
                onWordsClick = onStartWordStudy,
                onGrammarClick = onStartGrammarStudy,
                onProductionClick = onStartProduction,
                onProofChallengeClick = onStartProofChallenge,
                onReadingClick = onStartReading,
                onPasteClick = onStartReadingPaste,
                onNativeReadingClick = onStartNativeReading,
                onBooksClick = onOpenBooks,
                onScenarioClick = onStartScenario,
                onScenarioSessionClick = onOpenScenario,
                onMaterialClick = onOpenMaterial,
            )
            MainTab.Library -> LibraryScreen(
                modifier = contentModifier,
                repository = knowledgeRepository,
                onOpenWord = onOpenWord,
                onAddCard = onAddCard,
            )
            MainTab.Settings -> SettingsScreen(
                modifier = contentModifier,
                prefs = prefs,
                onStartAssessment = onStartAssessment,
                onOpenModelSettings = onOpenModelSettings,
            )
        }
    }
}
