package com.lazydog.english.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
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
import com.lazydog.english.core.model.SampleData
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GoalsScreen(
    onBack: () -> Unit,
    onNext: (goal: String, topics: Set<String>, dailyMinutes: Int) -> Unit,
) {
    var goal by rememberSaveable { mutableStateOf("") }
    var topics by rememberSaveable { mutableStateOf(setOf<String>()) }
    var minutes by rememberSaveable { mutableFloatStateOf(12f) }

    val topicsValid = topics.size in 2..5

    OnboardingStepScaffold(
        title = "学什么、学多久",
        step = 3,
        onBack = onBack,
        bottomBar = {
            OnboardingBottomBar {
                Button(
                    onClick = { onNext(goal, topics, minutes.roundToInt()) },
                    enabled = goal.isNotBlank() && topicsValid,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("下一步")
                }
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("主要目标", style = MaterialTheme.typography.titleMedium)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SampleData.goalOptions.forEach { option ->
                    FilterChip(
                        selected = goal == option,
                        onClick = { goal = option },
                        label = { Text(option) },
                        leadingIcon = if (goal == option) {
                            { Icon(Icons.Outlined.Check, contentDescription = null) }
                        } else {
                            null
                        },
                    )
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("感兴趣的话题", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "选 2～5 个，阅读会按这些生成",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SampleData.topicOptions.forEach { topic ->
                    val selected = topic in topics
                    FilterChip(
                        selected = selected,
                        onClick = {
                            topics = if (selected) topics - topic else topics + topic
                        },
                        label = { Text(topic) },
                        leadingIcon = if (selected) {
                            { Icon(Icons.Outlined.Check, contentDescription = null) }
                        } else {
                            null
                        },
                    )
                }
            }
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
