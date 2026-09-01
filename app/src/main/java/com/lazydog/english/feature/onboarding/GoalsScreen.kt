package com.lazydog.english.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lazydog.english.core.designsystem.TagPicker
import com.lazydog.english.core.model.LearningGoals
import com.lazydog.english.core.model.SampleData
import kotlin.math.roundToInt

@Composable
fun GoalsScreen(
    onBack: () -> Unit,
    onNext: (goal: String, topics: Set<String>, dailyMinutes: Int) -> Unit,
) {
    // Set 进不了 SavedState，这里用列表存，旋转后还在。
    var goals by rememberSaveable { mutableStateOf(listOf<String>()) }
    var topics by rememberSaveable { mutableStateOf(listOf<String>()) }
    var minutes by rememberSaveable { mutableFloatStateOf(12f) }

    OnboardingStepScaffold(
        title = "学什么、学多久",
        step = 2,
        onBack = onBack,
        bottomBar = {
            OnboardingBottomBar {
                Button(
                    onClick = { onNext(LearningGoals.join(goals), topics.toSet(), minutes.roundToInt()) },
                    enabled = goals.isNotEmpty() && topics.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("下一步")
                }
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("主要目标", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "可以多选，也可以自己加",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            TagPicker(
                options = SampleData.goalOptions,
                selected = goals.toSet(),
                onToggle = { goals = if (it in goals) goals - it else goals + it },
                addPlaceholder = "比如 面试、带娃看英文绘本",
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("感兴趣的话题", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "想选几个选几个，阅读会按这些生成",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            TagPicker(
                options = SampleData.topicOptions,
                selected = topics.toSet(),
                onToggle = { topics = if (it in topics) topics - it else topics + it },
                addPlaceholder = "比如 篮球、机械键盘",
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("每天想学多久", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(
                    value = minutes,
                    onValueChange = { minutes = it },
                    valueRange = 5f..30f,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${minutes.roundToInt()} 分钟",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 14.dp),
                )
            }
            Text(
                text = "到期复习多的日子会自动少给新知识，不会硬塞。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
