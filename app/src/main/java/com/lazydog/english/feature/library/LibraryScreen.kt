package com.lazydog.english.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.lazydog.english.LazyDogApplication
import com.lazydog.english.core.data.KnowledgeRepository
import com.lazydog.english.core.data.stageOrDefault
import com.lazydog.english.core.database.KnowledgeItemEntity
import com.lazydog.english.core.designsystem.LazyDogTheme
import com.lazydog.english.core.model.KnowledgeStage
import com.lazydog.english.core.model.KnowledgeType
import com.lazydog.english.core.model.ReviewGrade
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun LibraryScreen(
    modifier: Modifier = Modifier,
    repository: KnowledgeRepository,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val app = remember { context.applicationContext as LazyDogApplication }
    val speech = app.speechController
    val vocab by repository.vocabulary.collectAsState(initial = emptyList())
    val grammar by repository.grammar.collectAsState(initial = emptyList())

    var tabIndex by rememberSaveable { mutableIntStateOf(0) }
    var dueTodayOnly by rememberSaveable { mutableStateOf(false) }
    var selectedItemId by rememberSaveable { mutableStateOf<Long?>(null) }
    var showAddDialog by rememberSaveable { mutableStateOf(false) }

    val now = System.currentTimeMillis()
    val endOfToday = remember {
        LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
    val visibleVocab =
        if (dueTodayOnly) vocab.filter { (it.item.nextReviewAt ?: Long.MAX_VALUE) < endOfToday }
        else vocab

    Box(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                text = "记录",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 12.dp),
            )
            TabRow(selectedTabIndex = tabIndex) {
                Tab(
                    selected = tabIndex == 0,
                    onClick = { tabIndex = 0 },
                    text = { Text("单词 ${vocab.size}") },
                )
                Tab(
                    selected = tabIndex == 1,
                    onClick = { tabIndex = 1 },
                    text = { Text("语法 ${grammar.size}") },
                )
            }
            if (tabIndex == 0) {
                Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    FilterChip(
                        selected = dueTodayOnly,
                        onClick = { dueTodayOnly = !dueTodayOnly },
                        label = { Text("今天到期") },
                        leadingIcon = if (dueTodayOnly) {
                            { Icon(Icons.Outlined.Check, contentDescription = null) }
                        } else {
                            null
                        },
                    )
                }
                if (visibleVocab.isEmpty()) {
                    EmptyHint(
                        if (vocab.isEmpty()) "还没有记过单词。点右下角加一个，或等学习流程自动加入。"
                        else "今天没有到期的单词，懒狗可以歇会儿。",
                    )
                } else {
                    LazyColumn {
                        items(visibleVocab, key = { it.item.id }) { record ->
                            LibraryRow(
                                title = record.detail.term,
                                subtitle = record.detail.meaningZh,
                                item = record.item,
                                now = now,
                                onClick = { selectedItemId = record.item.id },
                            )
                        }
                    }
                }
            } else {
                if (grammar.isEmpty()) {
                    EmptyHint("还没有记过语法点。点右下角加一个。")
                } else {
                    LazyColumn(modifier = Modifier.padding(top = 8.dp)) {
                        items(grammar, key = { it.item.id }) { record ->
                            LibraryRow(
                                title = record.detail.name,
                                subtitle = record.detail.explanationZh,
                                item = record.item,
                                now = now,
                                onClick = { selectedItemId = record.item.id },
                            )
                        }
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = { showAddDialog = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        ) {
            Icon(Icons.Outlined.Add, contentDescription = "添加${if (tabIndex == 0) "单词" else "语法点"}")
        }
    }

    if (showAddDialog) {
        AddItemDialog(
            type = if (tabIndex == 0) KnowledgeType.Vocabulary else KnowledgeType.Grammar,
            onDismiss = { showAddDialog = false },
            onConfirm = { title, explanation, example ->
                scope.launch {
                    val id = when (tabIndex) {
                        0 -> repository.addVocabulary(term = title, meaningZh = explanation, exampleEn = example)
                        else -> repository.addGrammar(name = title, explanationZh = explanation, exampleEn = example)
                    }
                    if (id != null) showAddDialog = false
                }
            },
            isDuplicate = { title ->
                if (tabIndex == 0) vocab.any { it.detail.term.equals(title.trim(), ignoreCase = true) }
                else grammar.any { it.detail.name == title.trim() }
            },
        )
    }

    val selectedVocab = vocab.firstOrNull { it.item.id == selectedItemId }
    val selectedGrammar = grammar.firstOrNull { it.item.id == selectedItemId }

    // 打开单词详情时自动读一遍（设置里可关）。
    LaunchedEffect(selectedVocab?.item?.id) {
        val term = selectedVocab?.detail?.term ?: return@LaunchedEffect
        if (app.userPreferences.autoReadWords.first()) speech.speak(term)
    }

    if (selectedVocab != null || selectedGrammar != null) {
        val example = selectedVocab?.detail?.exampleEn ?: selectedGrammar?.detail?.exampleEn.orEmpty()
        ItemDetailSheet(
            title = selectedVocab?.detail?.term ?: selectedGrammar?.detail?.name.orEmpty(),
            ipa = selectedVocab?.detail?.ipa.orEmpty(),
            explanation = selectedVocab?.detail?.meaningZh ?: selectedGrammar?.detail?.explanationZh.orEmpty(),
            example = example,
            item = (selectedVocab?.item ?: selectedGrammar?.item)!!,
            onSpeakWord = selectedVocab?.let { record ->
                { scope.launch { speech.speak(record.detail.term) } }
            },
            onSpeakExample = example.takeIf { it.isNotBlank() }?.let { text ->
                { scope.launch { speech.speak(text) } }
            },
            onDismiss = { selectedItemId = null },
            onReview = { grade ->
                val id = selectedItemId ?: return@ItemDetailSheet
                scope.launch {
                    repository.recordReview(id, grade)
                    selectedItemId = null
                }
            },
            onDelete = {
                val id = selectedItemId ?: return@ItemDetailSheet
                scope.launch {
                    repository.deleteItem(id)
                    selectedItemId = null
                }
            },
        )
    }
}

@Composable
private fun LibraryRow(
    title: String,
    subtitle: String,
    item: KnowledgeItemEntity,
    now: Long,
    onClick: () -> Unit,
) {
    Column(modifier = Modifier.clickable(onClick = onClick)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(title, style = MaterialTheme.typography.bodyLarge)
                    if (subtitle.isNotBlank()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StageChip(item.stageOrDefault())
                    Text(
                        text = dueLabel(item.nextReviewAt, now),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(20.dp),
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHigh)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ItemDetailSheet(
    title: String,
    ipa: String,
    explanation: String,
    example: String,
    item: KnowledgeItemEntity,
    onSpeakWord: (() -> Unit)?,
    onSpeakExample: (() -> Unit)?,
    onDismiss: () -> Unit,
    onReview: (ReviewGrade) -> Unit,
    onDelete: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                if (ipa.isNotBlank()) {
                    Text(
                        text = ipa,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (onSpeakWord != null) {
                    IconButton(onClick = onSpeakWord) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.VolumeUp,
                            contentDescription = "朗读这个词",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            if (explanation.isNotBlank()) {
                Text(explanation, style = MaterialTheme.typography.bodyMedium)
            }
            if (example.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = example,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (onSpeakExample != null) {
                        IconButton(onClick = onSpeakExample) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.VolumeUp,
                                contentDescription = "朗读例句",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StageChip(item.stageOrDefault())
                Text(
                    text = "复习 ${item.reviewCount} 次 · 忘过 ${item.lapseCount} 次 · " +
                        dueLabel(item.nextReviewAt, System.currentTimeMillis()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }

            Text(
                text = "现在想到它，什么感觉？",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                ReviewGrade.entries.forEach { grade ->
                    OutlinedButton(
                        onClick = { onReview(grade) },
                        modifier = Modifier.weight(1f),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                    ) {
                        Text(grade.label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                    }
                }
            }

            TextButton(onClick = { confirmDelete = true }) {
                Text("删除这条记录", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除「$title」？") },
            text = { Text("它的复习计划和学习事件会一起删除，删了就找不回来。") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("留着") }
            },
        )
    }
}

@Composable
private fun AddItemDialog(
    type: KnowledgeType,
    onDismiss: () -> Unit,
    onConfirm: (title: String, explanation: String, example: String) -> Unit,
    isDuplicate: (String) -> Boolean,
) {
    var title by rememberSaveable { mutableStateOf("") }
    var explanation by rememberSaveable { mutableStateOf("") }
    var example by rememberSaveable { mutableStateOf("") }
    val duplicate = title.isNotBlank() && isDuplicate(title)
    val isVocab = type == KnowledgeType.Vocabulary

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isVocab) "记一个单词" else "记一个语法点") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(if (isVocab) "单词" else "语法点名称") },
                    isError = duplicate,
                    supportingText = if (duplicate) {
                        { Text("已经记过它了") }
                    } else {
                        null
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = explanation,
                    onValueChange = { explanation = it },
                    label = { Text(if (isVocab) "中文意思" else "一句话说明（可不填）") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = example,
                    onValueChange = { example = it },
                    label = { Text("例句（可不填）") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(title, explanation, example) },
                enabled = title.isNotBlank() && !duplicate && (!isVocab || explanation.isNotBlank()),
            ) {
                Text("记下")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("算了") }
        },
    )
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 32.dp),
    )
}

/** 距离下次复习的展示文字。 */
internal fun dueLabel(nextReviewAt: Long?, now: Long): String {
    if (nextReviewAt == null) return "未安排复习"
    val diff = nextReviewAt - now
    return when {
        diff <= 0 -> "已到期"
        diff < 60 * 60 * 1000 -> "${(diff / (60 * 1000)).coerceAtLeast(1)} 分钟后"
        diff < 24 * 60 * 60 * 1000 -> "${diff / (60 * 60 * 1000)} 小时后"
        diff < 2 * 24 * 60 * 60 * 1000 -> "明天"
        else -> "${diff / (24 * 60 * 60 * 1000)} 天后"
    }
}

/** 掌握状态标签：文字 + 底色，不只靠颜色区分。 */
@Composable
private fun StageChip(stage: KnowledgeStage) {
    val extended = LazyDogTheme.extendedColors
    val (bg, fg) = when (stage) {
        KnowledgeStage.Unseen -> MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurfaceVariant
        KnowledgeStage.Exposed -> MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurfaceVariant
        KnowledgeStage.Learning -> extended.attentionContainer to extended.onAttentionContainer
        KnowledgeStage.Familiar -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        KnowledgeStage.Mastered -> extended.correctContainer to extended.onCorrectContainer
    }
    StageChipContent(bg, fg, stage.label)
}

@Composable
private fun StageChipContent(bg: Color, fg: Color, label: String) {
    Surface(color = bg, shape = MaterialTheme.shapes.small) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}
