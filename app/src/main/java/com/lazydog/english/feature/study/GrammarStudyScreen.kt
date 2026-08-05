package com.lazydog.english.feature.study

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.LibraryAdd
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lazydog.english.LazyDogApplication
import com.lazydog.english.core.data.KnowledgeRepository
import com.lazydog.english.core.data.displayPattern
import com.lazydog.english.core.designsystem.LazyDogTheme
import com.lazydog.english.core.designsystem.InteractiveEnglishText
import com.lazydog.english.domain.generation.GeneratedGrammarLesson
import com.lazydog.english.domain.generation.GenerationResult
import com.lazydog.english.domain.generation.GrammarLessonRequest
import com.lazydog.english.domain.planning.DailyStep
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private sealed interface GrammarPhase {
    data object Idle : GrammarPhase
    data object Generating : GrammarPhase
    data class Showing(val lesson: GeneratedGrammarLesson, val saved: Boolean) : GrammarPhase
    data class Failed(val reason: String) : GrammarPhase
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GrammarStudyScreen(
    repository: KnowledgeRepository,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val app = remember { context.applicationContext as LazyDogApplication }
    val scope = rememberCoroutineScope()

    var phase by remember { mutableStateOf<GrammarPhase>(GrammarPhase.Idle) }
    var focus by rememberSaveable { mutableStateOf("") }
    var progressChars by remember { mutableStateOf(0) }

    fun generate() {
        phase = GrammarPhase.Generating
        progressChars = 0
        scope.launch {
            val known = repository.grammar.first().map { it.detail.displayPattern() }.take(100)
            val result = app.contentGenerator.generateGrammarLesson(
                GrammarLessonRequest(
                    learnerLevel = app.userPreferences.learnerLevelDescription.first(),
                    focus = focus.trim().ifBlank { null },
                    knownGrammar = known,
                ),
                onProgress = { chars -> progressChars = chars },
            )
            phase = when (result) {
                is GenerationResult.Success -> GrammarPhase.Showing(result.data, saved = false)
                is GenerationResult.Failure -> GrammarPhase.Failed(result.reason)
            }
        }
    }

    fun saveLesson(lesson: GeneratedGrammarLesson) {
        scope.launch {
            val id = repository.addGrammar(
                patternEn = lesson.patternEn,
                labelZh = lesson.labelZh,
                summaryZh = lesson.summaryZh,
                explanationZh = lesson.explanationZh,
                exampleEn = lesson.goodExampleEn,
                exampleZh = lesson.goodExampleZh,
                badExampleEn = lesson.badExampleEn,
                badExampleNoteZh = lesson.badExampleNoteZh,
                tipZh = lesson.tipZh,
            )
            if (id != null) {
                phase = GrammarPhase.Showing(lesson, saved = true)
                app.userPreferences.markTodayStepDone(LocalDate.now().toString(), DailyStep.Grammar.id)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("语法") },
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
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (val p = phase) {
                GrammarPhase.Idle -> {
                    Text(
                        text = "想学哪个语法点？留空就让 AI 按你的水平挑一个。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    OutlinedTextField(
                        value = focus,
                        onValueChange = { focus = it },
                        label = { Text("语法点（可不填）") },
                        placeholder = { Text("比如：过去完成时、虚拟语气") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(onClick = ::generate, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.AutoAwesome, contentDescription = null)
                        Text("让 AI 讲一讲", modifier = Modifier.padding(start = 8.dp))
                    }
                }
                GrammarPhase.Generating -> Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator()
                    Text(
                        text = if (progressChars > 0) "AI 正在备课… 已生成 $progressChars 字" else "AI 正在备课…",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                is GrammarPhase.Failed -> Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "没拿到讲解：${p.reason}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                    Button(onClick = ::generate) { Text("再试一次") }
                    TextButton(onClick = { phase = GrammarPhase.Idle }) { Text("换个想法") }
                }
                is GrammarPhase.Showing -> LessonView(
                    lesson = p.lesson,
                    saved = p.saved,
                    onSave = { saveLesson(p.lesson) },
                    onAnother = {
                        focus = ""
                        phase = GrammarPhase.Idle
                    },
                )
            }
        }
    }
}

@Composable
private fun LessonView(
    lesson: GeneratedGrammarLesson,
    saved: Boolean,
    onSave: () -> Unit,
    onAnother: () -> Unit,
) {
    val context = LocalContext.current
    val app = remember { context.applicationContext as LazyDogApplication }
    val scope = rememberCoroutineScope()
    val extended = LazyDogTheme.extendedColors

    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
        InteractiveEnglishText(
            text = lesson.patternEn,
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = lesson.labelZh,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    Text(
        text = lesson.summaryZh,
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.Medium,
    )
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("怎么用", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text(
            text = lesson.explanationZh,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    ExampleBlock(
        icon = Icons.Outlined.CheckCircle,
        tint = extended.correct,
        label = "这样说",
        sentence = lesson.goodExampleEn,
        note = lesson.goodExampleZh,
        onSpeak = { scope.launch { app.speechController.speak(lesson.goodExampleEn) } },
    )
    if (lesson.badExampleEn.isNotBlank()) {
        ExampleBlock(
            icon = Icons.Outlined.Cancel,
            tint = MaterialTheme.colorScheme.error,
            label = "容易说错",
            sentence = lesson.badExampleEn,
            note = lesson.badExampleNoteZh,
            onSpeak = null,
        )
    }
    if (lesson.tipZh.isNotBlank()) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("易混提醒", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(
                    text = lesson.tipZh,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    Button(
        onClick = onSave,
        enabled = !saved,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(Icons.Outlined.LibraryAdd, contentDescription = null)
        Text(
            text = if (saved) "已记入知识库，会安排复习" else "记入知识库",
            modifier = Modifier.padding(start = 8.dp),
        )
    }
    TextButton(onClick = onAnother, modifier = Modifier.fillMaxWidth()) {
        Text("换一个语法点")
    }
}

@Composable
private fun ExampleBlock(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: androidx.compose.ui.graphics.Color,
    label: String,
    sentence: String,
    note: String,
    onSpeak: (() -> Unit)?,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = tint,
                    modifier = Modifier.weight(1f),
                )
                if (onSpeak != null) {
                    IconButton(onClick = onSpeak) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.VolumeUp,
                            contentDescription = "朗读例句",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
            InteractiveEnglishText(text = sentence, style = MaterialTheme.typography.bodyLarge)
            if (note.isNotBlank()) {
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
