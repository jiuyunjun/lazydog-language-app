package com.lazydog.english.feature.listening

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.lazydog.english.LazyDogApplication
import com.lazydog.english.domain.listening.FeatureScore
import com.lazydog.english.domain.listening.ListeningProfile
import com.lazydog.english.domain.listening.MIN_ATTEMPTS_FOR_PROFILE
import com.lazydog.english.domain.listening.MIN_ATTEMPTS_PER_FEATURE

/**
 * 听力画像：能力地图的第二层（`持续学习DESIGN.md` §16）。
 *
 * 第一层"听力 A2+"只说得出他大概在哪儿；这一页要回答的是**哪里弱**——
 * 连读 43%、数字 95%，这才指得动下一步练什么。所以最弱的一项排在最上面。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListeningProfileScreen(onExit: () -> Unit) {
    val context = LocalContext.current
    val app = remember { context.applicationContext as LazyDogApplication }
    val profile by app.listeningMaterialRepository.profile.collectAsState(initial = ListeningProfile.Empty)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("听力弱点") },
                navigationIcon = {
                    IconButton(onClick = onExit) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (profile.attempts == 0) {
                Text(
                    text = "还没有数据。练几轮听力，这里会告诉你到底卡在哪一类音上。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = profile.percent?.let { "总体听懂 $it%" } ?: "一共答了 ${profile.attempts} 题",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        // 正确率会被提示和重听拉高，所以另给一个更硬的数。
                        text = "其中 ${profile.firstListen} 题是裸听一遍就懂的",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (profile.percent == null) {
                        Text(
                            text = "满 $MIN_ATTEMPTS_FOR_PROFILE 题之后才给总正确率——" +
                                "三五道题的正确率是噪声，摆出来只会误导。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }

            if (profile.features.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("按听觉难点分", style = MaterialTheme.typography.titleSmall)
                    profile.features.forEach { FeatureRow(it) }
                }
            }

            if (profile.mishears.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("最常栽的误听类型", style = MaterialTheme.typography.titleSmall)
                    profile.mishears.forEach {
                        Text(
                            text = "${it.type.labelZh} · ${it.count} 次",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FeatureRow(score: FeatureScore) {
    val enough = score.attempts >= MIN_ATTEMPTS_PER_FEATURE
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(score.feature, style = MaterialTheme.typography.bodyLarge)
            Text(
                // 样本不够就只报次数，不报百分比：两题里错一题不是"50% 的水平"。
                text = if (enough) "${score.percent}%" else "还只练了 ${score.attempts} 次",
                style = MaterialTheme.typography.bodyMedium,
                color = if (enough) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.outline,
            )
        }
        if (enough) {
            LinearProgressIndicator(
                progress = { score.percent / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
