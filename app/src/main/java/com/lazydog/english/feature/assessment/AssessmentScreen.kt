package com.lazydog.english.feature.assessment

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.Rule
import androidx.compose.material.icons.outlined.Abc
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lazydog.english.LazyDogApplication
import com.lazydog.english.domain.assessment.AssessmentEngine
import com.lazydog.english.domain.assessment.AssessmentOutcome
import com.lazydog.english.domain.assessment.AssessmentQuestion
import com.lazydog.english.domain.assessment.AssessmentSkill
import com.lazydog.english.domain.assessment.AssessmentState
import com.lazydog.english.domain.assessment.CefrLevel
import com.lazydog.english.domain.assessment.ExpressionFeedback
import com.lazydog.english.domain.assessment.ExpressionRating
import com.lazydog.english.domain.assessment.SavedAssessment
import com.lazydog.english.domain.generation.GenerationResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true }

/**
 * 测试流程：客观题梯度（词汇 / 语法 / 阅读，长度不固定）→ 写一句话（AI 只评估，不参与升降级）→ 结果。
 * 对齐 DESIGN.md 05-07：测试中不显示总题数、不倒计时；结果页给出词汇区间、分项画像和手动微调入口。
 */
private sealed interface AssessmentPhase {
    data object Intro : AssessmentPhase
    data class ResumeOffer(val saved: SavedAssessment) : AssessmentPhase
    data object FetchingQuestions : AssessmentPhase
    data class Answering(val question: AssessmentQuestion, val selected: Int?) : AssessmentPhase
    data class Failed(val reason: String) : AssessmentPhase
    data class ExpressionPrompt(val task: String) : AssessmentPhase
    data object ExpressionSubmitting : AssessmentPhase
    data class ExpressionFailed(val task: String, val reason: String) : AssessmentPhase
    data class ExpressionResult(val feedback: ExpressionFeedback) : AssessmentPhase
    data class Result(val outcome: AssessmentOutcome, val saved: Boolean) : AssessmentPhase
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssessmentScreen(onExit: () -> Unit) {
    val context = LocalContext.current
    val app = remember { context.applicationContext as LazyDogApplication }
    val scope = rememberCoroutineScope()
    val prefs = app.userPreferences

    var phase by remember { mutableStateOf<AssessmentPhase>(AssessmentPhase.Intro) }
    var engineState by remember { mutableStateOf(AssessmentEngine.initial()) }
    var queue by remember { mutableStateOf<List<AssessmentQuestion>>(emptyList()) }
    var expressionText by rememberSaveable { mutableStateOf("") }
    var checkedResume by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val savedJson = prefs.assessmentStateJson.first()
        if (savedJson.isNotBlank()) {
            runCatching { json.decodeFromString<SavedAssessment>(savedJson) }.getOrNull()?.let { saved ->
                when {
                    !AssessmentEngine.isComplete(saved.state) -> phase = AssessmentPhase.ResumeOffer(saved)
                    !saved.expressionDone -> {
                        engineState = saved.state
                        phase = AssessmentPhase.ExpressionPrompt(
                            saved.expressionTaskZh ?: AssessmentEngine.expressionPrompts.random(),
                        )
                    }
                    else -> Unit
                }
            }
        }
        checkedResume = true
    }

    suspend fun persist(
        state: AssessmentState,
        remaining: List<AssessmentQuestion>,
        expressionTaskZh: String? = null,
        expressionDone: Boolean = false,
    ) {
        prefs.saveAssessmentState(
            json.encodeToString(
                SavedAssessment.serializer(),
                SavedAssessment(state, remaining, expressionTaskZh, expressionDone),
            ),
        )
    }

    fun startExpressionStep() {
        val task = AssessmentEngine.expressionPrompts.random()
        expressionText = ""
        phase = AssessmentPhase.ExpressionPrompt(task)
        scope.launch { persist(engineState, emptyList(), expressionTaskZh = task, expressionDone = false) }
    }

    fun finishAssessment(state: AssessmentState, expressionFeedback: ExpressionFeedback?) {
        val outcome = AssessmentEngine.result(state, expressionFeedback)
        phase = AssessmentPhase.Result(outcome, saved = false)
        scope.launch {
            prefs.saveLearnerProfile(outcome.level.label, outcome.confidencePercent)
            prefs.clearAssessmentState()
            (phase as? AssessmentPhase.Result)?.let { phase = it.copy(saved = true) }
        }
    }

    fun advance(state: AssessmentState, remaining: List<AssessmentQuestion>) {
        engineState = state
        if (AssessmentEngine.isComplete(state)) {
            startExpressionStep()
            return
        }
        // 队列里的题必须匹配当前等级，等级变了就重新出题。
        val next = remaining.firstOrNull()
        if (next != null) {
            queue = remaining
            phase = AssessmentPhase.Answering(next, selected = null)
            scope.launch { persist(state, remaining) }
        } else {
            phase = AssessmentPhase.FetchingQuestions
            scope.launch {
                val topics = prefs.topics.first().toList()
                when (
                    val result = app.contentGenerator.generateAssessmentQuestions(
                        cefrLevel = state.currentLevel.label,
                        count = 3,
                        topics = topics,
                    )
                ) {
                    is GenerationResult.Failure -> phase = AssessmentPhase.Failed(result.reason)
                    is GenerationResult.Success -> {
                        queue = result.data
                        persist(state, result.data)
                        phase = AssessmentPhase.Answering(result.data.first(), selected = null)
                    }
                }
            }
        }
    }

    fun onAnswerConfirmed(question: AssessmentQuestion, selected: Int?) {
        val correct = selected != null && selected == question.answerIndex
        val newState = AssessmentEngine.record(engineState, question.skill, correct)
        val remaining = queue.drop(1)
        // 等级变了，剩下的题难度不再匹配，丢弃重出。
        val usable = if (newState.currentLevel == engineState.currentLevel) remaining else emptyList()
        advance(newState, usable)
    }

    fun submitExpression(task: String, text: String) {
        phase = AssessmentPhase.ExpressionSubmitting
        scope.launch {
            when (
                val result = app.contentGenerator.evaluateExpression(task, text, engineState.currentLevel.label)
            ) {
                is GenerationResult.Failure -> phase = AssessmentPhase.ExpressionFailed(task, result.reason)
                is GenerationResult.Success -> {
                    persist(engineState, emptyList(), expressionTaskZh = task, expressionDone = true)
                    phase = AssessmentPhase.ExpressionResult(result.data)
                }
            }
        }
    }

    fun skipExpression() {
        scope.launch { persist(engineState, emptyList(), expressionTaskZh = null, expressionDone = true) }
        finishAssessment(engineState, expressionFeedback = null)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("能力小测") },
                navigationIcon = {
                    IconButton(onClick = onExit) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (phase is AssessmentPhase.Answering || phase is AssessmentPhase.FetchingQuestions) {
                        Text(
                            text = "已答 ${engineState.answered.size} 题 · 难度还在调",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 16.dp),
                        )
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (val p = phase) {
                AssessmentPhase.Intro -> IntroView(
                    enabled = checkedResume,
                    onStart = {
                        engineState = AssessmentEngine.initial()
                        advance(engineState, emptyList())
                    },
                    onSkip = onExit,
                )
                is AssessmentPhase.ResumeOffer -> ResumeView(
                    saved = p.saved,
                    onResume = {
                        engineState = p.saved.state
                        advance(p.saved.state, p.saved.queue)
                    },
                    onRestart = {
                        scope.launch { prefs.clearAssessmentState() }
                        engineState = AssessmentEngine.initial()
                        advance(engineState, emptyList())
                    },
                )
                AssessmentPhase.FetchingQuestions -> Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator()
                    Text(
                        text = "正在出 ${engineState.currentLevel.label} 难度的题…",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                is AssessmentPhase.Failed -> Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "出题失败：${p.reason}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                    Button(onClick = { advance(engineState, emptyList()) }) { Text("再试一次") }
                    TextButton(onClick = onExit) { Text("先退出（进度已保存）") }
                }
                is AssessmentPhase.Answering -> QuestionView(
                    question = p.question,
                    currentLevel = engineState.currentLevel,
                    selected = p.selected,
                    onSelect = { index -> phase = p.copy(selected = index) },
                    onConfirm = { onAnswerConfirmed(p.question, p.selected) },
                    onSkip = { onAnswerConfirmed(p.question, null) },
                )
                is AssessmentPhase.ExpressionPrompt -> ExpressionPromptView(
                    task = p.task,
                    text = expressionText,
                    onTextChange = { expressionText = it },
                    onSubmit = { submitExpression(p.task, expressionText) },
                    onSkip = { skipExpression() },
                )
                AssessmentPhase.ExpressionSubmitting -> Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator()
                    Text("AI 正在看你写的这几句…", style = MaterialTheme.typography.bodyMedium)
                }
                is AssessmentPhase.ExpressionFailed -> Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "评估失败：${p.reason}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = "刚才学的都已经存好了，一个字没丢。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = { submitExpression(p.task, expressionText) }) { Text("再试一次") }
                    TextButton(onClick = { skipExpression() }) { Text("跳过这题，直接看结果") }
                }
                is AssessmentPhase.ExpressionResult -> ExpressionResultView(
                    feedback = p.feedback,
                    onContinue = { finishAssessment(engineState, p.feedback) },
                )
                is AssessmentPhase.Result -> ResultView(
                    outcome = p.outcome,
                    saved = p.saved,
                    onOverrideLevel = { level ->
                        scope.launch { prefs.saveLearnerProfile(level.label, confidencePercent = 100) }
                    },
                    onExit = onExit,
                )
            }
        }
    }
}

@Composable
private fun IntroView(enabled: Boolean, onStart: () -> Unit, onSkip: () -> Unit) {
    val parts = listOf(
        Triple(Icons.Outlined.Abc, "认词", "看词选意思"),
        Triple(Icons.AutoMirrored.Outlined.Rule, "语法", "选句子 / 填空"),
        Triple(Icons.AutoMirrored.Outlined.Article, "分级阅读", "读一小段短文再答题"),
        Triple(Icons.Outlined.EditNote, "写一句话", "AI 辅助判断表达，不影响客观题的难度评估"),
    )
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(
            imageVector = Icons.Outlined.Insights,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Text("先摸个底", style = MaterialTheme.typography.headlineSmall)
        Text(
            text = "大概 6～8 分钟。答得好会变难，答不出会变简单，所以答错完全不用心虚。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = MaterialTheme.shapes.medium,
        ) {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                parts.forEach { (icon, name, note) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = note,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = MaterialTheme.shapes.medium,
        ) {
            Text(
                text = "中途退出会保留已答题目，下次接着来。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(16.dp),
            )
        }
        Button(onClick = onStart, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text("开始测试")
        }
        TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
            Text("先随便看看，稍后再测")
        }
    }
}

@Composable
private fun ResumeView(saved: SavedAssessment, onResume: () -> Unit, onRestart: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("上次测到一半", style = MaterialTheme.typography.headlineSmall)
        Text(
            text = "已答 ${saved.state.answered.size} 题，当前难度 ${saved.state.currentLevel.label}。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onResume, modifier = Modifier.fillMaxWidth()) { Text("接着测") }
        TextButton(onClick = onRestart, modifier = Modifier.fillMaxWidth()) { Text("重新开始") }
    }
}

@Composable
private fun QuestionView(
    question: AssessmentQuestion,
    currentLevel: CefrLevel,
    selected: Int?,
    onSelect: (Int) -> Unit,
    onConfirm: () -> Unit,
    onSkip: () -> Unit,
) {
    val skillLabel = when (question.skill) {
        AssessmentSkill.Grammar -> "语法"
        AssessmentSkill.Reading -> "分级阅读"
        else -> "语境选词"
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.small) {
                Text(
                    text = skillLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
            Text(
                text = "${currentLevel.label} 附近",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        if (!question.passage.isNullOrBlank()) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = question.passage,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(14.dp),
                )
            }
        }
        Text(question.prompt, style = MaterialTheme.typography.titleLarge)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            question.options.forEachIndexed { index, option ->
                val isSelected = index == selected
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(selected = isSelected, onClick = { onSelect(index) })
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = isSelected, onClick = { onSelect(index) })
                    Text(
                        text = option,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
        }
        Button(
            onClick = onConfirm,
            enabled = selected != null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("确认")
        }
        TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
            Text("不认识，下一题")
        }
        Text(
            text = "测试中不显示对错，免得越测越慌。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ExpressionPromptView(
    task: String,
    text: String,
    onTextChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onSkip: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.EditNote, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text("最后写一句话", style = MaterialTheme.typography.headlineSmall)
        }
        Text(task, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = "这题只是让 AI 看看你的表达习惯，不参与上面的难度评估，写多写少都行。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            placeholder = { Text("Write two or three sentences in English…") },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp),
        )
        Button(
            onClick = onSubmit,
            enabled = text.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("提交")
        }
        TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
            Text("跳过这题，直接看结果")
        }
    }
}

@Composable
private fun ExpressionResultView(feedback: ExpressionFeedback, onContinue: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            text = if (feedback.rating == ExpressionRating.Good) "写得不错" else "还有提升空间",
            style = MaterialTheme.typography.headlineSmall,
        )
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "AI 润色版",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(feedback.suggestionEn, style = MaterialTheme.typography.bodyLarge)
            }
        }
        Text(feedback.issueZh, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = feedback.explanationZh,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) { Text("看结果") }
    }
}

@Composable
private fun ResultView(
    outcome: AssessmentOutcome,
    saved: Boolean,
    onOverrideLevel: (CefrLevel) -> Unit,
    onExit: () -> Unit,
) {
    var overrideLevel by rememberSaveable { mutableStateOf<CefrLevel?>(null) }
    var showLevelPicker by rememberSaveable { mutableStateOf(false) }
    val displayLevel = overrideLevel ?: outcome.level

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("你现在大概在这里", style = MaterialTheme.typography.headlineSmall)
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = displayLevel.label,
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        text = if (overrideLevel != null) "自己调的" else outcome.confidenceLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Text(
                    text = "词汇量估计 ${AssessmentEngine.vocabRangeText(displayLevel)}。" +
                        "这只是起点，接下来两周的表现会持续修正它。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            outcome.profile.forEach { row ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(row.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = row.label,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    LinearProgressIndicator(
                        progress = { row.pct / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp),
                    )
                }
            }
        }
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("还看不太准的", style = MaterialTheme.typography.labelLarge)
                Text(
                    text = outcome.watchNoteZh,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectable(selected = false, onClick = { showLevelPicker = true }),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("觉得估高或估低了？", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "手动改成 A1 / A2 / B1 / B2 / C1",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.Outlined.Tune, contentDescription = "手动调整等级", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            text = "答对 ${outcome.correctCount} / ${outcome.totalCount} 道客观题。一次测试只是初始估计，不用太当真。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (saved) {
            Text(
                text = "画像已保存，AI 出题会按这个水平来。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Button(onClick = onExit, modifier = Modifier.fillMaxWidth()) { Text("就按这个安排学习") }
    }

    if (showLevelPicker) {
        AlertDialog(
            onDismissRequest = { showLevelPicker = false },
            title = { Text("手动选一个等级") },
            text = {
                Column {
                    CefrLevel.entries.forEach { level ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = level == displayLevel,
                                    onClick = {
                                        overrideLevel = level
                                        onOverrideLevel(level)
                                        showLevelPicker = false
                                    },
                                )
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = level == displayLevel, onClick = null)
                            Text(level.label, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLevelPicker = false }) { Text("关闭") }
            },
        )
    }
}
