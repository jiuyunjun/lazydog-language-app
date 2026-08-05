package com.lazydog.english.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import com.lazydog.english.core.data.displayPattern
import com.lazydog.english.core.data.displaySummary
import com.lazydog.english.core.data.stageOrDefault
import com.lazydog.english.core.database.GrammarRecord
import com.lazydog.english.core.database.KnowledgeItemEntity
import com.lazydog.english.core.database.VocabularyRecord
import com.lazydog.english.core.designsystem.LazyDogTheme
import com.lazydog.english.core.designsystem.InteractiveEnglishText
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
    val expressions by repository.expressions.collectAsState(initial = emptyList())

    val pagerState = rememberPagerState(pageCount = { 3 })
    val tabIndex = pagerState.currentPage
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
                    onClick = { scope.launch { pagerState.animateScrollToPage(0) } },
                    text = { Text("单词 ${vocab.size}") },
                )
                Tab(
                    selected = tabIndex == 1,
                    onClick = { scope.launch { pagerState.animateScrollToPage(1) } },
                    text = { Text("语法 ${grammar.size}") },
                )
                Tab(
                    selected = tabIndex == 2,
                    onClick = { scope.launch { pagerState.animateScrollToPage(2) } },
                    text = { Text("表达 ${expressions.size}") },
                )
            }
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.Top,
            ) { page ->
                when (page) {
                    0 -> WordRecords(
                        records = visibleVocab,
                        allWordsEmpty = vocab.isEmpty(),
                        dueTodayOnly = dueTodayOnly,
                        onDueTodayChange = { dueTodayOnly = it },
                        now = now,
                        onSelect = { selectedItemId = it },
                    )
                    1 -> GrammarRecords(
                        records = grammar,
                        now = now,
                        onSelect = { selectedItemId = it },
                    )
                    else -> ExpressionRecords(
                        records = expressions,
                        now = now,
                        onSelect = { selectedItemId = it },
                    )
                }
            }
        }

        if (tabIndex < 2) {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
            ) {
                Icon(Icons.Outlined.Add, contentDescription = "添加${if (tabIndex == 0) "单词" else "语法点"}")
            }
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
                        else -> repository.addGrammar(
                            patternEn = title,
                            summaryZh = explanation,
                            explanationZh = explanation,
                            exampleEn = example,
                        )
                    }
                    if (id != null) showAddDialog = false
                }
            },
            isDuplicate = { title ->
                if (tabIndex == 0) vocab.any { it.detail.term.equals(title.trim(), ignoreCase = true) }
                else grammar.any { it.detail.displayPattern().equals(title.trim(), ignoreCase = true) }
            },
        )
    }

    val selectedWord = vocab.firstOrNull { it.item.id == selectedItemId }
    val selectedExpression = expressions.firstOrNull { it.item.id == selectedItemId }
    val selectedVocab = selectedWord ?: selectedExpression
    val selectedGrammar = grammar.firstOrNull { it.item.id == selectedItemId }

    // 打开单词详情时自动读一遍（设置里可关）。
    LaunchedEffect(selectedVocab?.item?.id) {
        val term = selectedVocab?.detail?.term ?: return@LaunchedEffect
        if (app.userPreferences.autoReadWords.first()) speech.speak(term)
    }

    if (selectedVocab != null) {
        val example = when {
            selectedExpression != null -> ""
            selectedWord != null -> selectedWord.detail.exampleEn
            else -> ""
        }
        ItemDetailSheet(
            title = selectedVocab.detail.term,
            ipa = selectedVocab.detail.ipa,
            explanation = selectedVocab.detail.meaningZh,
            example = example,
            item = selectedVocab.item,
            onSpeakWord = { scope.launch { speech.speak(selectedVocab.detail.term) } },
            speakDescription = if (selectedExpression != null) "朗读这条表达" else "朗读这个词",
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

    selectedGrammar?.let { record ->
        GrammarDetailSheet(
            record = record,
            onSpeakExample = record.detail.exampleEn.takeIf { it.isNotBlank() }?.let { text ->
                { scope.launch { speech.speak(text) } }
            },
            onDismiss = { selectedItemId = null },
            onReview = { grade ->
                scope.launch {
                    repository.recordReview(record.item.id, grade)
                    selectedItemId = null
                }
            },
            onDelete = {
                scope.launch {
                    repository.deleteItem(record.item.id)
                    selectedItemId = null
                }
            },
        )
    }
}

@Composable
private fun WordRecords(
    records: List<VocabularyRecord>,
    allWordsEmpty: Boolean,
    dueTodayOnly: Boolean,
    onDueTodayChange: (Boolean) -> Unit,
    now: Long,
    onSelect: (Long) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            FilterChip(
                selected = dueTodayOnly,
                onClick = { onDueTodayChange(!dueTodayOnly) },
                label = { Text("今天到期") },
                leadingIcon = if (dueTodayOnly) {
                    { Icon(Icons.Outlined.Check, contentDescription = null) }
                } else {
                    null
                },
            )
        }
        if (records.isEmpty()) {
            EmptyHint(
                if (allWordsEmpty) "还没有记过单词。点右下角加一个，或等学习流程自动加入。"
                else "今天没有到期的单词，懒狗可以歇会儿。",
            )
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(records, key = { it.item.id }) { record ->
                    LibraryRow(
                        title = record.detail.term,
                        subtitle = record.detail.meaningZh,
                        item = record.item,
                        now = now,
                        onClick = { onSelect(record.item.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun GrammarRecords(
    records: List<GrammarRecord>,
    now: Long,
    onSelect: (Long) -> Unit,
) {
    if (records.isEmpty()) {
        EmptyHint("还没有记过语法点。点右下角加一个。")
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize().padding(top = 8.dp)) {
            items(records, key = { it.item.id }) { record ->
                LibraryRow(
                    title = record.detail.displayPattern(),
                    subtitle = record.detail.displaySummary(),
                    item = record.item,
                    now = now,
                    stackedSubtitle = true,
                    onClick = { onSelect(record.item.id) },
                )
            }
        }
    }
}

@Composable
private fun ExpressionRecords(
    records: List<VocabularyRecord>,
    now: Long,
    onSelect: (Long) -> Unit,
) {
    if (records.isEmpty()) {
        EmptyHint("还没有摘下表达。情景总结或句子讲解里遇到想复用的句子，可以收进这里。")
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize().padding(top = 8.dp)) {
            items(records, key = { it.item.id }) { record ->
                LibraryRow(
                    title = record.detail.term,
                    subtitle = record.detail.meaningZh,
                    item = record.item,
                    now = now,
                    stackedSubtitle = true,
                    onClick = { onSelect(record.item.id) },
                )
            }
        }
    }
}

@Composable
private fun LibraryRow(
    title: String,
    subtitle: String,
    item: KnowledgeItemEntity,
    now: Long,
    stackedSubtitle: Boolean = false,
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
                if (stackedSubtitle) {
                    InteractiveEnglishText(title, style = MaterialTheme.typography.bodyLarge, onSingleTap = onClick)
                    if (subtitle.isNotBlank()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        InteractiveEnglishText(title, style = MaterialTheme.typography.bodyLarge, onSingleTap = onClick)
                        if (subtitle.isNotBlank()) {
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
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
private fun GrammarDetailSheet(
    record: GrammarRecord,
    onSpeakExample: (() -> Unit)?,
    onDismiss: () -> Unit,
    onReview: (ReviewGrade) -> Unit,
    onDelete: () -> Unit,
) {
    val detail = record.detail
    val pattern = detail.displayPattern()
    val summary = detail.displaySummary()
    var confirmDelete by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            InteractiveEnglishText(
                text = pattern,
                style = MaterialTheme.typography.headlineSmall,
            )
            if (detail.labelZh.isNotBlank()) {
                Text(detail.labelZh, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            if (summary.isNotBlank()) {
                Text(summary, style = MaterialTheme.typography.bodyLarge)
            }
            if (detail.explanationZh.isNotBlank() && detail.explanationZh != summary) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("详细说明", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(detail.explanationZh, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (detail.exampleEn.isNotBlank()) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("例句", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            InteractiveEnglishText(detail.exampleEn, modifier = Modifier.weight(1f))
                            if (onSpeakExample != null) {
                                IconButton(onClick = onSpeakExample) {
                                    Icon(Icons.AutoMirrored.Outlined.VolumeUp, contentDescription = "朗读例句")
                                }
                            }
                        }
                        if (detail.exampleZh.isNotBlank()) {
                            Text(detail.exampleZh, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (detail.badExampleEn.isNotBlank()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("容易说错", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                    InteractiveEnglishText(detail.badExampleEn)
                    if (detail.badExampleNoteZh.isNotBlank()) {
                        Text(detail.badExampleNoteZh, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (detail.tipZh.isNotBlank()) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("易混提醒", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text(detail.tipZh, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            RecordReviewControls(record.item, onReview)
            TextButton(onClick = { confirmDelete = true }) {
                Text("删除这条记录", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (confirmDelete) {
        DeleteRecordDialog(pattern, onDismiss = { confirmDelete = false }) {
            confirmDelete = false
            onDelete()
        }
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
    speakDescription: String,
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
                InteractiveEnglishText(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f),
                )
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
                            contentDescription = speakDescription,
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
                    InteractiveEnglishText(
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
            RecordReviewControls(item, onReview)

            TextButton(onClick = { confirmDelete = true }) {
                Text("删除这条记录", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (confirmDelete) {
        DeleteRecordDialog(title, onDismiss = { confirmDelete = false }) {
            confirmDelete = false
            onDelete()
        }
    }
}

@Composable
private fun RecordReviewControls(item: KnowledgeItemEntity, onReview: (ReviewGrade) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StageChip(item.stageOrDefault())
        Text(
            text = "复习 ${item.reviewCount} 次 · 忘过 ${item.lapseCount} 次 · " + dueLabel(item.nextReviewAt, System.currentTimeMillis()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
    Text("现在想到它，什么感觉？", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
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
}

@Composable
private fun DeleteRecordDialog(title: String, onDismiss: () -> Unit, onDelete: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除「$title」？") },
        text = { Text("它的复习计划和学习事件会一起删除，删了就找不回来。") },
        confirmButton = {
            TextButton(onClick = onDelete) { Text("删除", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("留着") } },
    )
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
    val invalidGrammarPattern = !isVocab && title.isNotBlank() &&
        (!title.any { it in 'A'..'Z' || it in 'a'..'z' } || title.any { it.code in 0x4E00..0x9FFF })

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isVocab) "记一个单词" else "记一个语法点") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(if (isVocab) "单词" else "结构公式") },
                    placeholder = if (!isVocab) {
                        { Text("例如 be going to + base verb") }
                    } else {
                        null
                    },
                    isError = duplicate || invalidGrammarPattern,
                    supportingText = when {
                        duplicate -> ({ Text("已经记过它了") })
                        invalidGrammarPattern -> ({ Text("这里只写英文结构公式，中文用途放下一栏") })
                        else -> null
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = explanation,
                    onValueChange = { explanation = it },
                    label = { Text(if (isVocab) "中文意思" else "一句话用途（可不填）") },
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
                enabled = title.isNotBlank() && !duplicate && !invalidGrammarPattern && (!isVocab || explanation.isNotBlank()),
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
