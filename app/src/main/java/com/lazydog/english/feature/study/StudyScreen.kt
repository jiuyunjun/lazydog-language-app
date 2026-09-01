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
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Spellcheck
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

private sealed interface RecentStudyItem {
    val title: String
    val timestamp: Long

    data class Reading(val id: Long, override val title: String, override val timestamp: Long, val source: String) : RecentStudyItem
    data class Scenario(val id: Long, override val title: String, override val timestamp: Long, val stage: String) : RecentStudyItem
}

@Composable
fun StudyScreen(
    modifier: Modifier = Modifier,
    onSpeakingClick: () -> Unit,
    onListeningClick: () -> Unit,
    onSpellingClick: () -> Unit,
    onWordsClick: () -> Unit,
    onGrammarClick: () -> Unit,
    onProductionClick: () -> Unit,
    onReadingClick: () -> Unit,
    onPasteClick: () -> Unit,
    onScenarioClick: () -> Unit,
    onScenarioSessionClick: (Long) -> Unit,
    onMaterialClick: (Long) -> Unit,
) {
    val context = LocalContext.current
    val app = remember { context.applicationContext as LazyDogApplication }
    val recentMaterials by app.readingRepository.recent.collectAsState(initial = emptyList())
    val recentScenarios by app.scenarioSessionRepository.recent.collectAsState(initial = emptyList())
    val recentItems = remember(recentMaterials, recentScenarios) {
        (
            recentMaterials.map { RecentStudyItem.Reading(it.id, it.title, it.createdAt, it.source) } +
                recentScenarios.map { RecentStudyItem.Scenario(it.id, it.titleZh, it.updatedAt, it.stage) }
            ).sortedByDescending { it.timestamp }.take(8)
    }

    val entries = listOf(
        StudyEntry(Icons.Outlined.Abc, "单词", "复习到期 + AI 上新", onClick = onWordsClick),
        StudyEntry(Icons.AutoMirrored.Outlined.Rule, "语法", "让 AI 讲一个", onClick = onGrammarClick),
        StudyEntry(Icons.Outlined.Edit, "自己写一句", "中译英，判完记进错题", onClick = onProductionClick),
        StudyEntry(Icons.AutoMirrored.Outlined.Article, "阅读", "生成一篇定制短文", onClick = onReadingClick),
        StudyEntry(Icons.Outlined.Mic, "朗读", "读一句，拿反馈", onClick = onSpeakingClick),
        StudyEntry(Icons.Outlined.Headphones, "听力", "先听声音，再猜意思", onClick = onListeningClick),
        StudyEntry(Icons.Outlined.Spellcheck, "拼写", "认得不算，写得出才算", onClick = onSpellingClick),
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
                        text = "双击查词、三击讲句，生词顺手入库",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (recentItems.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "最近的材料",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp),
                )
                recentItems.forEach { item ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = MaterialTheme.shapes.medium,
                        onClick = {
                            when (item) {
                                is RecentStudyItem.Reading -> onMaterialClick(item.id)
                                is RecentStudyItem.Scenario -> onScenarioSessionClick(item.id)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(
                                imageVector = if (item is RecentStudyItem.Reading) {
                                    Icons.AutoMirrored.Outlined.Article
                                } else {
                                    Icons.Outlined.RecordVoiceOver
                                },
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(item.title, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    text = buildString {
                                        append(formatDate(item.timestamp))
                                        append(" · ")
                                        append(
                                            when (item) {
                                                is RecentStudyItem.Reading ->
                                                    if (item.source == ReadingRepository.SOURCE_AI) "AI 定制" else "粘贴导入"
                                                is RecentStudyItem.Scenario ->
                                                    if (item.stage == "Finished") "情景演练 · 已完成" else "情景演练 · 继续"
                                            },
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
