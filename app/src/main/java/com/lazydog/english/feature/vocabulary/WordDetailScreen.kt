package com.lazydog.english.feature.vocabulary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.lazydog.english.LazyDogApplication
import com.lazydog.english.core.data.KnowledgeRepository
import com.lazydog.english.core.data.VocabularyJson
import com.lazydog.english.core.data.stageOrDefault
import com.lazydog.english.core.designsystem.InteractiveEnglishText
import com.lazydog.english.core.model.ReviewGrade
import com.lazydog.english.feature.library.dueLabel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 记录里点开一个词，进的是这一页，不是弹一张半屏卡片。
 *
 * 词条要摆的东西已经不少：释义、搭配、记忆提示（可以现生成、可以换一条）、例句、复习状态。
 * 半屏 sheet 装这些要一直往上滑，而且顶掉了「这是一个独立的东西」的感觉——
 * 记录里的词和学习时的词卡是同一个词，就该长得一样、占同样大的地方。
 *
 * 整句表达也走这一页：它和单词存在同一张表，只是不给记忆提示——
 * 那套提示是按「一个词最值得记什么」设计的，对一整句话拆构词、标重音都无从谈起。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun WordDetailScreen(
    itemId: Long,
    repository: KnowledgeRepository,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val app = remember { context.applicationContext as LazyDogApplication }
    val scope = rememberCoroutineScope()
    val speech = app.speechController

    val words by repository.vocabulary.collectAsState(initial = emptyList())
    val expressions by repository.expressions.collectAsState(initial = emptyList())
    val record = words.firstOrNull { it.item.id == itemId }
        ?: expressions.firstOrNull { it.item.id == itemId }
    val isExpression = record != null && expressions.any { it.item.id == itemId }

    var confirmDelete by remember { mutableStateOf(false) }

    // 打开就读一遍（设置里可关）。表达是整句，按句子读；单词用播音腔。
    LaunchedEffect(record?.item?.id, isExpression) {
        val term = record?.detail?.term ?: return@LaunchedEffect
        if (!app.userPreferences.autoReadWords.first()) return@LaunchedEffect
        if (isExpression) speech.speak(term) else speech.speakWord(term)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(record?.detail?.term.orEmpty()) },
                navigationIcon = {
                    IconButton(onClick = onExit) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (record != null) {
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(
                                imageVector = Icons.Outlined.DeleteOutline,
                                contentDescription = "删除这条记录",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (record == null) {
            // 删掉之后这一帧还会重组一次；别在这里自动退栈，退栈交给删除本身做。
            Text(
                text = "这条记录已经不在了。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(padding).padding(16.dp),
            )
            return@Scaffold
        }
        val detail = record.detail
        val collocations = remember(detail.collocationsJson) {
            VocabularyJson.decodeCollocations(detail.collocationsJson)
        }

        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    InteractiveEnglishText(
                        text = detail.term,
                        style = MaterialTheme.typography.headlineLarge,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    IconButton(
                        onClick = {
                            scope.launch {
                                if (isExpression) speech.speak(detail.term) else speech.speakWord(detail.term)
                            }
                        },
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.VolumeUp,
                            contentDescription = if (isExpression) "朗读这条表达" else "再读一遍",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                if (detail.ipa.isNotBlank()) {
                    Text(
                        text = detail.ipa,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (detail.meaningZh.isNotBlank()) {
                    Text(
                        text = if (!isExpression && detail.pos.isNotBlank()) {
                            "${detail.pos} ${detail.meaningZh}"
                        } else {
                            detail.meaningZh
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                if (collocations.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        collocations.forEach { collocation -> CollocationChip(collocation) }
                    }
                }
                if (isExpression) {
                    if (detail.memoryHintZh.isNotBlank()) {
                        MemoryHintText(detail.memoryHintZh)
                    }
                } else {
                    MemoryHintPanel(itemId = record.item.id, fallbackHintZh = detail.memoryHintZh)
                }
                if (detail.exampleEn.isNotBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                InteractiveEnglishText(
                                    text = detail.exampleEn,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                IconButton(onClick = { scope.launch { speech.speak(detail.exampleEn) } }) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Outlined.VolumeUp,
                                        contentDescription = "朗读例句",
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                            if (detail.exampleZh.isNotBlank()) {
                                Text(
                                    text = detail.exampleZh,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                Text(
                    text = "${record.item.stageOrDefault().label} · 复习 ${record.item.reviewCount} 次 · " +
                        "忘过 ${record.item.lapseCount} 次 · " +
                        dueLabel(record.item.nextReviewAt, System.currentTimeMillis()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHigh)
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "现在想到它，什么感觉？照实点，算法才准",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    ReviewGrade.entries.forEach { grade ->
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    repository.recordReview(record.item.id, grade)
                                    onExit()
                                }
                            },
                            modifier = Modifier.weight(1f),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                horizontal = 4.dp,
                                vertical = 8.dp,
                            ),
                        ) {
                            Text(grade.label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                        }
                    }
                }
            }
        }
    }

    if (confirmDelete && record != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除「${record.detail.term}」？") },
            text = { Text("它的复习计划和学习事件会一起删除，删了就找不回来。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        scope.launch {
                            repository.deleteItem(record.item.id)
                            onExit()
                        }
                    },
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("留着") } },
        )
    }
}

/** 老的一句话记忆方法。整句表达只有这个，没有可重新生成的结构化提示。 */
@Composable
private fun MemoryHintText(hint: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "怎么记",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                text = hint,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}
