package com.lazydog.english.feature.assessment

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Insights
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
import com.lazydog.english.domain.assessment.AssessmentEngine
import com.lazydog.english.domain.assessment.AssessmentOutcome
import com.lazydog.english.domain.assessment.AssessmentQuestion
import com.lazydog.english.domain.assessment.AssessmentState
import com.lazydog.english.domain.assessment.SavedAssessment
import com.lazydog.english.domain.generation.GenerationResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true }

private sealed interface AssessmentPhase {
    data object Intro : AssessmentPhase
    data class ResumeOffer(val saved: SavedAssessment) : AssessmentPhase
    data object FetchingQuestions : AssessmentPhase
    data class Answering(val question: AssessmentQuestion, val selected: Int?) : AssessmentPhase
    data class Failed(val reason: String) : AssessmentPhase
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
    var checkedResume by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val savedJson = prefs.assessmentStateJson.first()
        if (savedJson.isNotBlank()) {
            runCatching { json.decodeFromString<SavedAssessment>(savedJson) }
                .getOrNull()
                ?.takeIf { !AssessmentEngine.isComplete(it.state) }
                ?.let { phase = AssessmentPhase.ResumeOffer(it) }
        }
        checkedResume = true
    }

    suspend fun persist(state: AssessmentState, remaining: List<AssessmentQuestion>) {
        prefs.saveAssessmentState(json.encodeToString(SavedAssessment.serializer(), SavedAssessment(state, remaining)))
    }

    fun advance(state: AssessmentState, remaining: List<AssessmentQuestion>) {
        engineState = state
        if (AssessmentEngine.isComplete(state)) {
            val outcome = AssessmentEngine.result(state)
            phase = AssessmentPhase.Result(outcome, saved = false)
            scope.launch {
                prefs.saveLearnerProfile(outcome.level.label, outcome.confidencePercent)
                prefs.clearAssessmentState()
                (phase as? AssessmentPhase.Result)?.let { phase = it.copy(saved = true) }
            }
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

    fun onAnswerConfirmed(question: AssessmentQuestion, selected: Int) {
        val correct = selected == question.answerIndex
        val newState = AssessmentEngine.record(engineState, correct)
        val remaining = queue.drop(1)
        // 等级变了，剩下的题难度不再匹配，丢弃重出。
        val usable = if (newState.currentLevel == engineState.currentLevel) remaining else emptyList()
        advance(newState, usable)
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
                            text = "${engineState.answered.size} / ${AssessmentEngine.TOTAL_QUESTIONS}",
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
            if (phase is AssessmentPhase.Answering || phase is AssessmentPhase.FetchingQuestions) {
                LinearProgressIndicator(
                    progress = { engineState.answered.size.toFloat() / AssessmentEngine.TOTAL_QUESTIONS },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp),
                )
            }
            when (val p = phase) {
                AssessmentPhase.Intro -> IntroView(
                    enabled = checkedResume,
                    onStart = {
                        engineState = AssessmentEngine.initial()
                        advance(engineState, emptyList())
                    },
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
                    selected = p.selected,
                    onSelect = { index -> phase = p.copy(selected = index) },
                    onNext = { p.selected?.let { onAnswerConfirmed(p.question, it) } },
                )
                is AssessmentPhase.Result -> ResultView(outcome = p.outcome, saved = p.saved, onExit = onExit)
            }
        }
    }
}

@Composable
private fun IntroView(enabled: Boolean, onStart: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(
            imageVector = Icons.Outlined.Insights,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Text("摸个底，AI 出题才有准头", style = MaterialTheme.typography.headlineSmall)
        Text(
            text = "${AssessmentEngine.TOTAL_QUESTIONS} 道单选题，答对会变难、答错会变简单，大概 5 分钟。" +
                "中途退出进度会保存。测完得到一个 CEFR 等级估计，之后的新词、语法、阅读都按它出。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onStart, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text("开始")
        }
    }
}

@Composable
private fun ResumeView(saved: SavedAssessment, onResume: () -> Unit, onRestart: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("上次测到一半", style = MaterialTheme.typography.headlineSmall)
        Text(
            text = "已答 ${saved.state.answered.size} / ${AssessmentEngine.TOTAL_QUESTIONS} 题，当前难度 ${saved.state.currentLevel.label}。",
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
    selected: Int?,
    onSelect: (Int) -> Unit,
    onNext: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = if (question.skill == "grammar") "语法" else "语境选词",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(question.prompt, style = MaterialTheme.typography.titleLarge)
        question.options.forEachIndexed { index, option ->
            Surface(
                onClick = { onSelect(index) },
                color = if (index == selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = option,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(14.dp),
                )
            }
        }
        Button(
            onClick = onNext,
            enabled = selected != null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("确定")
        }
        Text(
            text = "测试中不显示对错，免得越测越慌。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun ResultView(outcome: AssessmentOutcome, saved: Boolean, onExit: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("测完了", style = MaterialTheme.typography.headlineSmall)
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = outcome.level.label,
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = "${outcome.confidenceLabel}（置信度 ${outcome.confidencePercent}%）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        Text(
            text = "答对 ${outcome.correctCount} / ${outcome.totalCount}。这只是初始画像，之后每次学习都会慢慢修正它，不用太当真。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (saved) {
                Text(
                    text = "画像已保存，AI 出题会按这个水平来。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Button(onClick = onExit, modifier = Modifier.fillMaxWidth()) { Text("完成") }
    }
}
