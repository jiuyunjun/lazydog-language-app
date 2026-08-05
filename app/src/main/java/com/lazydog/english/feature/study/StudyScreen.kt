package com.lazydog.english.feature.study

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.Rule
import androidx.compose.material.icons.outlined.Abc
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.lazydog.english.LazyDogApplication
import com.lazydog.english.core.data.ReadingRepository
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private data class StudyEntry(
    val icon: ImageVector,
    val name: String,
    val note: String,
    val onClick: () -> Unit,
)

@Composable
fun StudyScreen(
    modifier: Modifier = Modifier,
    onSpeakingClick: () -> Unit,
    onWordsClick: () -> Unit,
    onGrammarClick: () -> Unit,
    onReadingClick: () -> Unit,
    onPasteClick: () -> Unit,
    onScenarioClick: () -> Unit,
    onMaterialClick: (Long) -> Unit,
) {
    val context = LocalContext.current
    val app = remember { context.applicationContext as LazyDogApplication }
    val recentMaterials by app.readingRepository.recent.collectAsState(initial = emptyList())

    val entries = listOf(
        StudyEntry(Icons.Outlined.Abc, "单词", "复习到期 + AI 上新", onClick = onWordsClick),
        StudyEntry(Icons.AutoMirrored.Outlined.Rule, "语法", "让 AI 讲一个", onClick = onGrammarClick),
        StudyEntry(Icons.AutoMirrored.Outlined.Article, "阅读", "生成一篇定制短文", onClick = onReadingClick),
        StudyEntry(Icons.Outlined.Mic, "朗读", "读一句，拿反馈", onClick = onSpeakingClick),
        StudyEntry(Icons.Outlined.RecordVoiceOver, "情景演练", "和难说话的人练一轮", onClick = onScenarioClick),
    )

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "学习",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            text = "想自己挑就在这儿挑。挑了什么也照样记进复习计划。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            entries.chunked(2).forEach { rowEntries ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    rowEntries.forEach { entry ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            shape = MaterialTheme.shapes.large,
                            onClick = entry.onClick,
                            modifier = Modifier.weight(1f),
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                Icon(
                                    imageVector = entry.icon,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp),
                                )
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(entry.name, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        text = entry.note,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    if (rowEntries.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        OutlinedCard(
            onClick = onPasteClick,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.ContentPaste,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("粘贴一段英文", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "边读边点词查，生词顺手入库",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (recentMaterials.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "最近的材料",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp),
                )
                recentMaterials.take(5).forEach { material ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = MaterialTheme.shapes.medium,
                        onClick = { onMaterialClick(material.id) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.Article,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(material.title, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    text = buildString {
                                        append(formatDate(material.createdAt))
                                        append(" · ")
                                        append(
                                            if (material.source == ReadingRepository.SOURCE_AI) "AI 定制"
                                            else "粘贴导入",
                                        )
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private val dateFormatter = DateTimeFormatter.ofPattern("M 月 d 日")

private fun formatDate(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(dateFormatter)
