package com.lazydog.english.feature.spelling

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lazydog.english.core.data.KnowledgeRepository
import com.lazydog.english.domain.spelling.SpellingDimension
import com.lazydog.english.domain.spelling.SpellingProfile
import com.lazydog.english.domain.spelling.SpellingProfiles
import kotlin.math.roundToInt

/**
 * 拼写能力档案（设计稿 64 屏）。
 *
 * 只展示能从记录里算出来的东西：六维掌握度来自各词的拼写进度，
 * 错误分布来自每次提交时分类好的错误类型。样本不够时明说不够，
 * 不拿两次错误画出一张像模像样的雷达图。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpellingProfileScreen(
    repository: KnowledgeRepository,
    onBack: () -> Unit,
    onStartPractice: () -> Unit,
) {
    var profile by remember { mutableStateOf<SpellingProfile?>(null) }

    LaunchedEffect(Unit) {
        profile = repository.spellingProfile()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("拼写能力") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        val current = profile
        if (current == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            if (current.isEmpty) {
                Text(
                    text = "还没有拼写记录。练过几轮之后，这里会告诉你错得最多的是哪几类。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
                )
                Button(onClick = onStartPractice, modifier = Modifier.fillMaxWidth()) {
                    Text("去练一轮")
                }
                return@Column
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("六个维度", style = MaterialTheme.typography.titleSmall)
                SpellingDimension.entries.forEach { dimension ->
                    val value = current.masteryVector[dimension] ?: 0.0
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = dimension.labelZh,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(76.dp),
                        )
                        LinearProgressIndicator(
                            progress = { value.toFloat().coerceIn(0f, 1f) },
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "${(value * 100).roundToInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.End,
                            modifier = Modifier.width(40.dp),
                        )
                    }
                }
            }

            if (current.errorRates.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("高频错误类型", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "占你 ${current.wrongCount} 次错误的比例，一次错可能同时命中几类。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    current.errorRates.entries
                        .sortedByDescending { it.value }
                        .forEach { (type, rate) ->
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceContainer,
                                shape = MaterialTheme.shapes.medium,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(type.labelZh, style = MaterialTheme.typography.bodyMedium)
                                    Spacer(Modifier.weight(1f))
                                    Text(
                                        text = "${(rate * 100).roundToInt()}%",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                }
            }

            current.avgFreeRecallMillis?.let { millis ->
                Text(
                    text = "无提示默写答对时，平均用 ${"%.1f".format(millis / 1000.0)} 秒。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val advice = SpellingProfiles.trainingAdvice(current)
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "建议专项训练",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Text(
                        text = advice
                            ?: "再练几轮。目前的错误还不够多，说不准哪一类是你的真弱点。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Button(onClick = onStartPractice) { Text("开始专项练习") }
                }
            }
        }
    }
}
