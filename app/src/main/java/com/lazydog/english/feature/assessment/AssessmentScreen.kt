package com.lazydog.english.feature.assessment

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.Rule
import androidx.compose.material.icons.outlined.Abc
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Forum
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
import com.lazydog.english.core.designsystem.InteractiveEnglishText
import com.lazydog.english.domain.assessment.AnswerOutcome
import com.lazydog.english.domain.assessment.AssessmentEngine
import com.lazydog.english.domain.assessment.AssessmentOutcome
import com.lazydog.english.domain.assessment.AssessmentQuestion
import com.lazydog.english.domain.assessment.AssessmentReport
import com.lazydog.english.domain.assessment.AssessmentSkill
import com.lazydog.english.domain.assessment.AssessmentStage
import com.lazydog.english.domain.assessment.AssessmentState
import com.lazydog.english.domain.assessment.CefrLevel
import com.lazydog.english.domain.assessment.CorrectionGrading
import com.lazydog.english.domain.assessment.CorrectionItem
import com.lazydog.english.domain.assessment.DeepReadingAnswer
import com.lazydog.english.domain.assessment.DeepReadingOutcome
import com.lazydog.english.domain.assessment.DeepReadingTask
import com.lazydog.english.domain.assessment.DeepReadingValidation
import com.lazydog.english.domain.assessment.ExpressionAssessment
import com.lazydog.english.domain.assessment.ExpressionDimension
import com.lazydog.english.domain.assessment.ExpressionValidation
import com.lazydog.english.domain.assessment.NextLadderStep
import com.lazydog.english.domain.assessment.ReadingTag
import com.lazydog.english.domain.assessment.SavedAssessment
import com.lazydog.english.domain.assessment.WritingTask
import com.lazydog.english.domain.assessment.WritingTaskLibrary
import com.lazydog.english.domain.assessment.labelForScore
import com.lazydog.english.domain.assessment.scoreForLabel
import com.lazydog.english.domain.generation.GenerationResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true }

/**
 * 测试流程对齐《CEFR 英语能力评测与个性化学习系统设计.md》：3 题定位 → 客观题梯度自适应升降
 * （词汇/语法/阅读/语用/纠错短答五类技能，长度不固定）→ 深度阅读（1 篇短文 + 4 道标签题）
 * → 写一段话（AI 两轮打分，不反过来影响上面的能力值）→ 结果页（CEFR 区间 + 加权分项画像 +
 * 理解/输出差距提示 + 手动微调）。
 */
private sealed interface AssessmentPhase {
    data object Intro : AssessmentPhase
    data class ResumeOffer(val saved: SavedAssessment) : AssessmentPhase
    data object FetchingQuestion : AssessmentPhase
    data class Answering(
        val question: AssessmentQuestion,
        val levelScore: Double,
        val selected: Int?,
        val shownAtMillis: Long,
    ) : AssessmentPhase
    data class LadderFailed(val reason: String) : AssessmentPhase

    data object FetchingCorrection : AssessmentPhase
    data class CorrectionAnswering(val item: CorrectionItem, val levelScore: Double, val shownAtMillis: Long) :
        AssessmentPhase
    data class CorrectionFailed(val reason: String) : AssessmentPhase

    data object FetchingDeepReading : AssessmentPhase
    data class DeepReadingAnswering(val task: DeepReadingTask, val selections: Map<Int, Int>) : AssessmentPhase
    data class DeepReadingFailed(val reason: String) : AssessmentPhase

    data class WritingPrompt(val task: WritingTask) : AssessmentPhase
    data object WritingSubmitting : AssessmentPhase
    data class WritingFailed(val reason: String) : AssessmentPhase
    data class WritingResult(val assessment: ExpressionAssessment) : AssessmentPhase

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
    var ladderState by remember { mutableStateOf(AssessmentEngine.initial()) }
    var deepReadingOutcome by remember { mutableStateOf<DeepReadingOutcome?>(null) }
    var expressionAssessment by remember { mutableStateOf<ExpressionAssessment?>(null) }
    var writingText by rememberSaveable { mutableStateOf("") }
    var correctionText by rememberSaveable { mutableStateOf("") }
    var checkedResume by remember { mutableStateOf(false) }

    suspend fun persist(stage: AssessmentStage, deepReadingTask: DeepReadingTask? = null) {
        prefs.saveAssessmentState(
            json.encodeToString(SavedAssessment.serializer(), SavedAssessment(ladderState, stage, deepReadingTask)),
        )
    }

    fun startWriting() {
        writingText = ""
        phase = AssessmentPhase.WritingPrompt(WritingTaskLibrary.taskFor(ladderState.score))
    }

    fun fetchDeepReading() {
        phase = AssessmentPhase.FetchingDeepReading
        scope.launch {
            val topics = prefs.topics.first().toList()
            when (
                val result = app.contentGenerator.generateDeepReading(labelForScore(ladderState.score), topics)
            ) {
                is GenerationResult.Failure -> phase = AssessmentPhase.DeepReadingFailed(result.reason)
                is GenerationResult.Success -> {
                    persist(AssessmentStage.DeepReading, result.data)
                    phase = AssessmentPhase.DeepReadingAnswering(result.data, emptyMap())
                }
            }
        }
    }

    fun advanceLadder() {
        when (val step = AssessmentEngine.nextStep(ladderState)) {
            is NextLadderStep.Question -> {
                phase = AssessmentPhase.FetchingQuestion
                scope.launch {
                    val topics = prefs.topics.first().toList()
                    when (
                        val result = app.contentGenerator.generateAssessmentQuestions(
                            cefrLevel = step.level,
                            count = 1,
                            topics = topics,
                            skillFilter = step.skill,
                        )
                    ) {
                        is GenerationResult.Failure -> phase = AssessmentPhase.LadderFailed(result.reason)
                        is GenerationResult.Success -> {
                            scope.launch { persist(AssessmentStage.Ladder) }
                            phase = AssessmentPhase.Answering(
                                question = result.data.first(),
                                levelScore = scoreForLabel(step.level),
                                selected = null,
                                shownAtMillis = System.currentTimeMillis(),
                            )
                        }
                    }
                }
            }
            is NextLadderStep.Correction -> {
                phase = AssessmentPhase.FetchingCorrection
                correctionText = ""
                scope.launch {
                    val topics = prefs.topics.first().toList()
                    when (val result = app.contentGenerator.generateCorrectionItem(step.level, topics)) {
                        is GenerationResult.Failure -> phase = AssessmentPhase.CorrectionFailed(result.reason)
                        is GenerationResult.Success -> {
                            scope.launch { persist(AssessmentStage.Ladder) }
                            phase = AssessmentPhase.CorrectionAnswering(
                                item = result.data,
                                levelScore = scoreForLabel(step.level),
                                shownAtMillis = System.currentTimeMillis(),
                            )
                        }
                    }
                }
            }
            NextLadderStep.MoveToDeepReading -> {
                scope.launch { persist(AssessmentStage.DeepReading) }
                fetchDeepReading()
            }
        }
    }

    fun onLadderAnswer(question: AssessmentQuestion, levelScore: Double, selected: Int?, shownAtMillis: Long) {
        val outcome = if (selected != null && selected == question.answerIndex) AnswerOutcome.Correct else AnswerOutcome.Wrong
        val timing = AssessmentEngine.classifyTiming(System.currentTimeMillis() - shownAtMillis)
        ladderState = AssessmentEngine.record(ladderState, question.skill, levelScore, outcome, timing)
        advanceLadder()
    }

    fun onCorrectionAnswer(item: CorrectionItem, levelScore: Double, shownAtMillis: Long, text: String) {
        val outcome = if (text.isBlank()) AnswerOutcome.Wrong else CorrectionGrading.grade(item, text)
        val timing = AssessmentEngine.classifyTiming(System.currentTimeMillis() - shownAtMillis)
        ladderState = AssessmentEngine.record(ladderState, AssessmentSkill.Correction, levelScore, outcome, timing)
        advanceLadder()
    }

    fun finishAssessment() {
        val outcome = AssessmentReport.build(ladderState, deepReadingOutcome, expressionAssessment)
        phase = AssessmentPhase.Result(outcome, saved = false)
        scope.launch {
            prefs.saveLearnerProfile(outcome.levelLabel, outcome.confidencePercent)
            prefs.clearAssessmentState()
            (phase as? AssessmentPhase.Result)?.let { phase = it.copy(saved = true) }
        }
    }

    fun finishDeepReading(task: DeepReadingTask, selections: Map<Int, Int>) {
        val answers = task.questions.mapIndexed { index, q -> DeepReadingAnswer(q, selections.getValue(index)) }
        deepReadingOutcome = DeepReadingValidation.score(answers)
        scope.launch { persist(AssessmentStage.Writing) }
        startWriting()
    }

    fun skipDeepReading() {
        deepReadingOutcome = null
        scope.launch { persist(AssessmentStage.Writing) }
        startWriting()
    }

    fun submitWriting(task: WritingTask, text: String) {
        phase = AssessmentPhase.WritingSubmitting
        scope.launch {
            val firstResult = app.contentGenerator.evaluateExpressionRubric(task.promptZh, text, referenceCefrLevel = null)
            val first = when (firstResult) {
                is GenerationResult.Failure -> {
                    phase = AssessmentPhase.WritingFailed(firstResult.reason)
                    return@launch
                }
                is GenerationResult.Success -> firstResult.data
            }
            val secondResult = app.contentGenerator.evaluateExpressionRubric(
                task.promptZh,
                text,
                labelForScore(ladderState.score),
            )
            val second = when (secondResult) {
                is GenerationResult.Failure -> {
                    phase = AssessmentPhase.WritingFailed(secondResult.reason)
                    return@launch
                }
                is GenerationResult.Success -> secondResult.data
            }
            val assessment = ExpressionValidation.assess(first, second)
            expressionAssessment = assessment
            phase = AssessmentPhase.WritingResult(assessment)
        }
    }

    fun skipWriting() {
        expressionAssessment = null
        finishAssessment()
    }

    LaunchedEffect(Unit) {
        val savedJson = prefs.assessmentStateJson.first()
        if (savedJson.isNotBlank()) {
            runCatching { json.decodeFromString<SavedAssessment>(savedJson) }.getOrNull()?.let { saved ->
                ladderState = saved.state
                when (saved.stage) {
                    AssessmentStage.Ladder -> phase = AssessmentPhase.ResumeOffer(saved)
                    AssessmentStage.DeepReading -> {
                        val task = saved.deepReadingTask
                        if (task != null) {
                            phase = AssessmentPhase.DeepReadingAnswering(task, emptyMap())
                        } else {
                            fetchDeepReading()
                        }
                    }
                    AssessmentStage.Writing -> startWriting()
                    AssessmentStage.Done -> Unit
                }
            }
        }
        checkedResume = true
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
                    if (phase is AssessmentPhase.Answering || phase is AssessmentPhase.FetchingQuestion) {
                        Text(
                            text = "已答 ${ladderState.answered.size} 题 · 难度还在调",
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
                        ladderState = AssessmentEngine.initial()
                        advanceLadder()
                    },
                    onSkip = onExit,
                )
                is AssessmentPhase.ResumeOffer -> ResumeView(
                    saved = p.saved,
                    onResume = { advanceLadder() },
                    onRestart = {
                        scope.launch { prefs.clearAssessmentState() }
                        ladderState = AssessmentEngine.initial()
                        advanceLadder()
                    },
                )
                AssessmentPhase.FetchingQuestion -> LoadingView("正在出 ${labelForScore(ladderState.score)} 难度的题…")
                is AssessmentPhase.LadderFailed -> FailedView(
                    reason = p.reason,
                    onRetry = { advanceLadder() },
                    onExit = onExit,
                )
                is AssessmentPhase.Answering -> QuestionView(
                    question = p.question,
                    currentLevel = labelForScore(ladderState.score),
                    selected = p.selected,
                    onSelect = { index -> phase = p.copy(selected = index) },
                    onConfirm = { onLadderAnswer(p.question, p.levelScore, p.selected, p.shownAtMillis) },
                    onSkip = { onLadderAnswer(p.question, p.levelScore, null, p.shownAtMillis) },
                )
                AssessmentPhase.FetchingCorrection -> LoadingView("正在出一道纠错题…")
                is AssessmentPhase.CorrectionFailed -> FailedView(
                    reason = p.reason,
                    onRetry = { advanceLadder() },
                    onExit = onExit,
                )
                is AssessmentPhase.CorrectionAnswering -> CorrectionView(
                    item = p.item,
                    text = correctionText,
                    onTextChange = { correctionText = it },
                    onConfirm = { onCorrectionAnswer(p.item, p.levelScore, p.shownAtMillis, correctionText) },
                    onSkip = { onCorrectionAnswer(p.item, p.levelScore, p.shownAtMillis, "") },
                )
                AssessmentPhase.FetchingDeepReading -> LoadingView("正在准备一篇阅读短文…")
                is AssessmentPhase.DeepReadingFailed -> FailedView(
                    reason = p.reason,
                    onRetry = { fetchDeepReading() },
                    onExit = { skipDeepReading() },
                    skipLabel = "跳过阅读，直接写一段话",
                )
                is AssessmentPhase.DeepReadingAnswering -> DeepReadingView(
                    task = p.task,
                    selections = p.selections,
                    onSelect = { qIndex, optIndex ->
                        phase = p.copy(selections = p.selections + (qIndex to optIndex))
                    },
                    onSubmit = { finishDeepReading(p.task, p.selections) },
                )
                is AssessmentPhase.WritingPrompt -> WritingPromptView(
                    task = p.task,
                    text = writingText,
                    onTextChange = { writingText = it },
                    onSubmit = { submitWriting(p.task, writingText) },
                    onSkip = { skipWriting() },
                )
                AssessmentPhase.WritingSubmitting -> LoadingView("AI 正在评两轮分…")
                is AssessmentPhase.WritingFailed -> FailedView(
                    reason = p.reason,
                    onRetry = {
                        val task = WritingTaskLibrary.taskFor(ladderState.score)
                        submitWriting(task, writingText)
                    },
                    onExit = { skipWriting() },
                    skipLabel = "跳过这题，直接看结果",
                )
                is AssessmentPhase.WritingResult -> WritingResultView(
                    assessment = p.assessment,
                    onContinue = { finishAssessment() },
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
private fun LoadingView(message: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator()
        Text(message, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun FailedView(
    reason: String,
    onRetry: () -> Unit,
    onExit: () -> Unit,
    skipLabel: String = "先退出（进度已保存）",
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "没成功：$reason",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "前面答的都已经存好了，一个字没丢。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onRetry) { Text("再试一次") }
        TextButton(onClick = onExit) { Text(skipLabel) }
    }
}

@Composable
private fun IntroView(enabled: Boolean, onStart: () -> Unit, onSkip: () -> Unit) {
    val parts = listOf(
        Triple(Icons.Outlined.Abc, "语境词汇 + 语法 + 纠错", "先答 3 道定位题，再按表现自动升降难度"),
        Triple(Icons.AutoMirrored.Outlined.Rule, "语用选择", "在真实场合该怎么回复、该怎么说"),
        Triple(Icons.AutoMirrored.Outlined.Article, "一篇阅读", "读一小段短文，答 4 道理解题"),
        Triple(Icons.Outlined.EditNote, "写一段话", "AI 打两轮分，不影响上面的难度评估"),
    )
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(
            imageVector = Icons.Outlined.Insights,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Text("先摸个底", style = MaterialTheme.typography.headlineSmall)
        Text(
            text = "大概 15～25 分钟。客观题答得好会变难、答不出会变简单，答错完全不用心虚。",
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
                        Icon(imageVector = icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
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
        Text(
            text = "这次先只测阅读和书面表达，不含听力和口语，结果页会说清楚。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
            text = "已答 ${saved.state.answered.size} 题客观题，当前难度 ${labelForScore(saved.state.score)}。",
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
    currentLevel: String,
    selected: Int?,
    onSelect: (Int) -> Unit,
    onConfirm: () -> Unit,
    onSkip: () -> Unit,
) {
    val skillLabel = when (question.skill) {
        AssessmentSkill.Grammar -> "语法与句意"
        AssessmentSkill.Reading -> "完形微文本"
        AssessmentSkill.Pragmatics -> "语用选择"
        else -> "语境词汇"
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
                text = "$currentLevel 附近",
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
                InteractiveEnglishText(
                    text = question.passage,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(14.dp),
                )
            }
        }
        InteractiveEnglishText(question.prompt, style = MaterialTheme.typography.titleLarge)
        OptionsList(options = question.options, selected = selected, onSelect = onSelect)
        Button(onClick = onConfirm, enabled = selected != null, modifier = Modifier.fillMaxWidth()) {
            Text("确认")
        }
        TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
            Text("不确定，下一题")
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
private fun OptionsList(options: List<String>, selected: Int?, onSelect: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEachIndexed { index, option ->
            val isSelected = index == selected
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(selected = isSelected, onClick = { onSelect(index) })
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = isSelected, onClick = { onSelect(index) })
                InteractiveEnglishText(
                    option,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(start = 4.dp),
                    onSingleTap = { onSelect(index) },
                )
            }
        }
    }
}

@Composable
private fun CorrectionView(
    item: CorrectionItem,
    text: String,
    onTextChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onSkip: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.small) {
                Text(
                    text = "纠错短答",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
        Text("这句话有点问题，改一下：", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        ) {
            InteractiveEnglishText(item.incorrectSentence, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(14.dp))
        }
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            placeholder = { Text("Write the corrected sentence…") },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = onConfirm, enabled = text.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            Text("确认")
        }
        TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
            Text("不确定，下一题")
        }
        Text(
            text = "改对大意就有分，不用和参考答案一字不差。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun DeepReadingView(
    task: DeepReadingTask,
    selections: Map<Int, Int>,
    onSelect: (questionIndex: Int, optionIndex: Int) -> Unit,
    onSubmit: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.Article,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Text("读一段短文", style = MaterialTheme.typography.headlineSmall)
        }
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        ) {
            InteractiveEnglishText(task.passage, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(16.dp))
        }
        task.questions.forEachIndexed { index, question ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.small) {
                    Text(
                        text = ReadingTag.label(question.tag),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
                InteractiveEnglishText(question.prompt, style = MaterialTheme.typography.titleMedium)
                OptionsList(
                    options = question.options,
                    selected = selections[index],
                    onSelect = { optIndex -> onSelect(index, optIndex) },
                )
            }
        }
        Button(
            onClick = onSubmit,
            enabled = selections.size == task.questions.size,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("提交这篇")
        }
    }
}

@Composable
private fun WritingPromptView(
    task: WritingTask,
    text: String,
    onTextChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onSkip: () -> Unit,
) {
    val wordCount = Regex("[A-Za-z'\\-]+").findAll(text).count()
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.EditNote, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text("最后写一段话", style = MaterialTheme.typography.headlineSmall)
        }
        Text(task.promptZh, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = "这题只是让 AI 看看你的表达习惯，不参与上面的难度评估。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            placeholder = { Text("Write in English…") },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 160.dp),
        )
        Text(
            text = "$wordCount 词 · 建议 ${task.minWords}~${task.maxWords} 词，不用太精确",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onSubmit, enabled = text.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            Text("提交")
        }
        TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
            Text("跳过这题，直接看结果")
        }
    }
}

@Composable
private fun WritingResultView(assessment: ExpressionAssessment, onContinue: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("AI 看完了", style = MaterialTheme.typography.headlineSmall)
        if (assessment.needsReview) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "两轮打分有点不一致，这次评分仅供参考。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(14.dp),
                )
            }
        }
        assessment.display.dimensions.forEach { dim ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(ExpressionDimension.label(dim.dimension), style = MaterialTheme.typography.bodyLarge)
                    Text("${dim.score} / 4", style = MaterialTheme.typography.bodyLarge)
                }
                dim.evidenceZh.forEach { evidence ->
                    Text(
                        text = "· $evidence",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
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
    var overrideLabel by rememberSaveable { mutableStateOf<String?>(null) }
    var showLevelPicker by rememberSaveable { mutableStateOf(false) }
    val displayLabel = overrideLabel ?: outcome.levelLabel

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("你现在大概在这里", style = MaterialTheme.typography.headlineSmall)
        Text(
            text = "英语阅读与书面表达能力摸底结果，不含听力和口语。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(displayLabel, style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(
                        text = if (overrideLabel != null) "自己调的" else outcome.confidenceLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Text(
                    text = "合理区间 ${outcome.plausibleRange} · 词汇量估计 ${outcome.vocabRangeText}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            outcome.profile.forEach { row ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(row.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = row.label,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    LinearProgressIndicator(
                        progress = { row.pct / 100f },
                        modifier = Modifier.fillMaxWidth().height(8.dp),
                    )
                }
            }
        }
        if (outcome.gapNoteZh != null) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.Forum, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = outcome.gapNoteZh,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                if (outcome.expressionNeedsReview) {
                    Text(
                        text = "开放表达那项两轮打分不太一致，仅供参考。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
                    text = "手动改成 Pre-A1 / A1 / A2 / B1 / B2 / C1",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.Outlined.Tune, contentDescription = "手动调整等级", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            text = "客观题答对 ${outcome.correctCount} / ${outcome.totalCount} 道。一次测试只是初始估计，不用太当真。",
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
                                    selected = level.label == displayLabel,
                                    onClick = {
                                        overrideLabel = level.label
                                        onOverrideLevel(level)
                                        showLevelPicker = false
                                    },
                                )
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = level.label == displayLabel, onClick = null)
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
