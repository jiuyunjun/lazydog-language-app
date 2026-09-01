package com.lazydog.english.feature.production

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lazydog.english.LazyDogApplication
import com.lazydog.english.core.ask.ProvideAskContext
import com.lazydog.english.core.data.displayPattern
import com.lazydog.english.core.designsystem.InteractiveEnglishText
import com.lazydog.english.core.designsystem.LazyDogTheme
import com.lazydog.english.domain.ask.AskContext
import com.lazydog.english.domain.ask.AskContextKind
import com.lazydog.english.domain.ask.AskDetail
import com.lazydog.english.core.designsystem.AiWaiting
import com.lazydog.english.domain.generation.GenerationResult
import com.lazydog.english.domain.generation.GenerationStage
import com.lazydog.english.domain.planning.DailyStep
import com.lazydog.english.domain.practice.GrammarErrorTag
import com.lazydog.english.domain.production.TranslationFeedback
import com.lazydog.english.domain.production.TranslationRequest
import com.lazydog.english.domain.production.TranslationTask
import com.lazydog.english.domain.production.TranslationValidation
import com.lazydog.english.domain.production.TranslationVerdict
import com.lazydog.english.feature.ask.AskTopBarAction
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val TASK_COUNT = 2

private data class WrittenLine(val task: TranslationTask, val feedback: TranslationFeedback)

private sealed interface ProductionPhase {
    data object Generating : ProductionPhase
    data class Failed(val reason: String) : ProductionPhase
    data class Writing(
        val tasks: List<TranslationTask>,
        val index: Int,
        val hintShown: Boolean = false,
    ) : ProductionPhase
    data class Checking(val tasks: List<TranslationTask>, val index: Int) : ProductionPhase
    data class Feedback(
        val tasks: List<TranslationTask>,
        val index: Int,
        val written: String,
        val feedback: TranslationFeedback,
    ) : ProductionPhase
    data class CheckFailed(
        val tasks: List<TranslationTask>,
        val index: Int,
        val reason: String,
    ) : ProductionPhase
    data class Summary(val lines: List<WrittenLine>) : ProductionPhase
}

/**
 * 每日产出：两句中译英，自己写，判定只判这一句。
 * 判错的形式类别直接进错题画像，决定之后讲什么语法点。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductionScreen(onExit: () -> Unit) {
    val context = LocalContext.current
    val app = remember { context.applicationContext as LazyDogApplication }
    val scope = rememberCoroutineScope()

    var phase by remember { mutableStateOf<ProductionPhase>(ProductionPhase.Generating) }

    var stage by remember { mutableStateOf<GenerationStage>(GenerationStage.Connecting) }
    var input by remember { mutableStateOf("") }
    var done by remember { mutableStateOf<List<WrittenLine>>(emptyList()) }

    fun generate() {
        phase = ProductionPhase.Generating
        scope.launch {
            val prefs = app.userPreferences
            val now = System.currentTimeMillis()
            val recentGrammar = app.knowledgeRepository.grammar.first()
                .sortedByDescending { it.item.updatedAt }
                .take(3)
                .map { it.detail.displayPattern() }
            val recentWords = app.knowledgeRepository.vocabulary.first()
                .filter { (it.item.lastReviewedAt ?: 0L) > 0 }
                .sortedByDescending { it.item.lastReviewedAt }
                .take(6)
                .map { it.detail.term }
            val result = app.contentGenerator.generateTranslationTasks(
                request = TranslationRequest(
                    learnerLevel = prefs.expressionLevelDescription.first(),
                    count = TASK_COUNT,
                    targetGrammar = recentGrammar,
                    targetVocabulary = recentWords,
                    weakSpots = app.mistakeRepository.weakSpots(now),
                ),
                onStage = { stage = it },
            )
            phase = when (result) {
                is GenerationResult.Success -> ProductionPhase.Writing(result.data, 0)
                is GenerationResult.Failure -> ProductionPhase.Failed(result.reason)
            }
        }
    }

    LaunchedEffect(Unit) { generate() }

    fun submit(tasks: List<TranslationTask>, index: Int) {
        val written = input.trim()
        if (written.isBlank()) return
        phase = ProductionPhase.Checking(tasks, index)
        scope.launch {
            val task = tasks[index]
            val result = app.contentGenerator.gradeTranslation(
                onStage = { stage = it },
                task = task,
                userTextEn = written,
                learnerLevel = app.userPreferences.expressionLevelDescription.first(),
            )
            phase = when (result) {
                is GenerationResult.Failure -> ProductionPhase.CheckFailed(tasks, index, result.reason)
                is GenerationResult.Success -> {
                    // 形式错误进错题画像：写错的东西决定明天讲什么。
                    TranslationValidation.mistakeTags(result.data).forEach { tag ->
                        app.mistakeRepository.recordMistake(
                            itemId = null,
                            patternEn = GrammarErrorTag.labelZh(tag),
                            errorTag = tag,
                            sentenceEn = task.promptZh,
                            chosen = written,
                            answer = result.data.correctedEn,
                        )
                    }
                    done = done + WrittenLine(task, result.data)
                    ProductionPhase.Feedback(tasks, index, written, result.data)
                }
            }
        }
    }

    fun next(tasks: List<TranslationTask>, index: Int) {
        input = ""
        phase = if (index + 1 < tasks.size) {
            ProductionPhase.Writing(tasks, index + 1)
        } else {
            scope.launch {
                app.userPreferences.markTodayStepDone(
                    LocalDate.now().toString(),
                    DailyStep.Production.id,
                )
            }
            ProductionPhase.Summary(done)
        }
    }

    ProvideAskContext(
        when (val p = phase) {
            is ProductionPhase.Writing -> p.tasks[p.index].toAskContext(written = null, feedback = null)
            is ProductionPhase.Feedback -> p.tasks[p.index].toAskContext(p.written, p.feedback)
            else -> null
        },
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("自己写一句") },
                navigationIcon = {
                    IconButton(onClick = onExit) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    val p = phase
                    val position = when (p) {
                        is ProductionPhase.Writing -> p.index + 1 to p.tasks.size
                        is ProductionPhase.Checking -> p.index + 1 to p.tasks.size
                        is ProductionPhase.Feedback -> p.index + 1 to p.tasks.size
                        else -> null
                    }
                    position?.let { (current, total) ->
                        Text(
                            text = "第 $current / $total 句",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 12.dp),
                        )
                    }
                    AskTopBarAction()
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (val p = phase) {
                ProductionPhase.Generating -> AiWaiting("在挑两句你现在最该练的…", stage)
                is ProductionPhase.Failed -> Hint {
                    Text(
                        text = "没拿到句子：${p.reason}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                    Button(onClick = ::generate) { Text("再试一次") }
                    TextButton(onClick = onExit) { Text("先退出") }
                }
                is ProductionPhase.Writing -> WritingView(
                    task = p.tasks[p.index],
                    input = input,
                    hintShown = p.hintShown,
                    onInputChange = { input = it },
                    onShowHint = { phase = p.copy(hintShown = true) },
                    onSubmit = { submit(p.tasks, p.index) },
                )
                is ProductionPhase.Checking -> AiWaiting("在看你这句…", stage)
                is ProductionPhase.CheckFailed -> Hint {
                    Text(
                        text = "判不了：${p.reason}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                    Button(onClick = { submit(p.tasks, p.index) }) { Text("再判一次") }
                    TextButton(onClick = { next(p.tasks, p.index) }) { Text("跳过这句") }
                }
                is ProductionPhase.Feedback -> FeedbackView(
                    task = p.tasks[p.index],
                    written = p.written,
                    feedback = p.feedback,
                    isLast = p.index == p.tasks.lastIndex,
                    onNext = { next(p.tasks, p.index) },
                )
                is ProductionPhase.Summary -> SummaryView(lines = p.lines, onExit = onExit)
            }
        }
    }
}

private fun TranslationTask.toAskContext(
    written: String?,
    feedback: TranslationFeedback?,
): AskContext = AskContext(
    kind = AskContextKind.Question,
    title = "要写的这句 · $promptZh",
    details = buildList {
        add(AskDetail("要表达的中文", promptZh))
        add(AskDetail("这句练的形式", GrammarErrorTag.labelZh(errorTag)))
        if (feedback == null) {
            add(AskDetail("提示", hintZh))
            add(
                AskDetail(
                    "状态",
                    "学习者正在自己写这句英文，不要直接给出完整译文，只能提示结构或用法",
                ),
            )
        } else {
            add(AskDetail("你写的", written.orEmpty()))
            add(AskDetail("改好的", feedback.correctedEn))
            add(AskDetail("参考答案", referenceEn))
            add(AskDetail("判定说明", feedback.noteZh))
        }
    },
    suggestions = if (feedback == null) {
        listOf("这句该用什么时态？", "这个意思英文一般怎么起头？")
    } else {
        listOf("我这么写为什么不地道？", "两种写法差在哪？", "再给我一句类似的练")
    },
)

@Composable
private fun WritingView(
    task: TranslationTask,
    input: String,
    hintShown: Boolean,
    onInputChange: (String) -> Unit,
    onShowHint: () -> Unit,
    onSubmit: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = "把这句说成英文",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(text = task.promptZh, style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            value = input,
            onValueChange = onInputChange,
            label = { Text("你的英文") },
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )
        if (hintShown && task.hintZh.isNotBlank()) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Lightbulb,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(text = task.hintZh, style = MaterialTheme.typography.bodyMedium)
                }
            }
        } else if (task.hintZh.isNotBlank()) {
            TextButton(onClick = onShowHint) { Text("卡住了，给点提示") }
        }
        Button(
            onClick = onSubmit,
            enabled = input.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("写好了，看看对不对")
        }
        Text(
            text = "写错很正常，这一步就是拿来发现自己哪种形式还不稳的。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FeedbackView(
    task: TranslationTask,
    written: String,
    feedback: TranslationFeedback,
    isLast: Boolean,
    onNext: () -> Unit,
) {
    val extended = LazyDogTheme.extendedColors
    val tint = when (feedback.verdict) {
        TranslationVerdict.Ok -> extended.correct
        TranslationVerdict.Minor -> extended.attention
        TranslationVerdict.Wrong -> MaterialTheme.colorScheme.error
    }

    Column(
        modifier = Modifier.padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(text = task.promptZh, style = MaterialTheme.typography.titleMedium)
        Text(
            text = feedback.verdict.labelZh,
            style = MaterialTheme.typography.headlineSmall,
            color = tint,
        )
        LabelledSentence("你写的", written)
        if (feedback.correctedEn.trim() != written.trim()) {
            LabelledSentence("改成", feedback.correctedEn)
        }
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(text = feedback.noteZh, style = MaterialTheme.typography.bodyMedium)
                val tags = TranslationValidation.mistakeTags(feedback)
                if (tags.isNotEmpty()) {
                    Text(
                        text = "记到错题里：${tags.joinToString("、") { GrammarErrorTag.labelZh(it) }}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        LabelledSentence("参考写法", task.referenceEn)
        Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) {
            Text(if (isLast) "写完了" else "下一句")
        }
    }
}

@Composable
private fun LabelledSentence(label: String, sentence: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        InteractiveEnglishText(text = sentence, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun SummaryView(lines: List<WrittenLine>, onExit: () -> Unit) {
    Column(
        modifier = Modifier.padding(top = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Outlined.TaskAlt,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Text("今天的两句写完了", style = MaterialTheme.typography.headlineSmall)
        val okCount = lines.count { it.feedback.verdict == TranslationVerdict.Ok }
        Text(
            text = "${lines.size} 句里对了 $okCount 句",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        lines.forEach { line ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(line.task.promptZh, style = MaterialTheme.typography.bodySmall)
                    InteractiveEnglishText(
                        text = line.feedback.correctedEn,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        Text(
            text = "写错的形式已经记下来了，下次讲语法会优先讲这些。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onExit, modifier = Modifier.fillMaxWidth()) { Text("收工") }
    }
}

@Composable
private fun Hint(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        content()
    }
}
