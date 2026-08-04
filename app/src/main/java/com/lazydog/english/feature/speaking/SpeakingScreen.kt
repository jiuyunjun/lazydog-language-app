package com.lazydog.english.feature.speaking

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.lazydog.english.core.data.KnowledgeRepository
import com.lazydog.english.core.data.UserPreferences
import com.lazydog.english.core.speech.AzureSpeechProvider
import com.lazydog.english.domain.speaking.AssessmentResult
import com.lazydog.english.domain.speaking.PronunciationFeedback
import com.lazydog.english.domain.speaking.SpeakResult
import com.lazydog.english.domain.speaking.SpeechProvider
import com.lazydog.english.domain.speaking.overallComment
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private data class PracticeSentence(val text: String, val sourceNote: String)

private val fallbackSentences = listOf(
    PracticeSentence("The smell of coffee lingered in the kitchen all morning.", "内置练习句"),
    PracticeSentence("She sent me the first draft of the report before lunch.", "内置练习句"),
    PracticeSentence("His excuse sounded plausible, but nobody really believed it.", "内置练习句"),
    PracticeSentence("The city tried to curb traffic downtown.", "内置练习句"),
)

private sealed interface SpeakingUiState {
    data object Idle : SpeakingUiState
    data object Playing : SpeakingUiState
    data object Listening : SpeakingUiState
    data class Feedback(val feedback: PronunciationFeedback) : SpeakingUiState
    data class Error(val message: String) : SpeakingUiState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeakingScreen(
    prefs: UserPreferences,
    repository: KnowledgeRepository,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var provider by remember { mutableStateOf<SpeechProvider?>(null) }
    LaunchedEffect(Unit) {
        provider = AzureSpeechProvider(
            subscriptionKey = prefs.speechKey.first(),
            region = prefs.speechRegion.first(),
        )
    }
    DisposableEffect(Unit) {
        onDispose { provider?.close() }
    }

    val vocab by repository.vocabulary.collectAsState(initial = emptyList())
    val sentences = remember(vocab) {
        vocab.filter { it.detail.exampleEn.isNotBlank() }
            .map { PracticeSentence(it.detail.exampleEn, "来自你的单词「${it.detail.term}」") }
            .ifEmpty { fallbackSentences }
    }
    var sentenceIndex by rememberSaveable { mutableIntStateOf(0) }
    val sentence = sentences[sentenceIndex % sentences.size]

    var uiState by remember { mutableStateOf<SpeakingUiState>(SpeakingUiState.Idle) }
    val busy = uiState is SpeakingUiState.Playing || uiState is SpeakingUiState.Listening

    fun startAssessment() {
        val speech = provider ?: return
        uiState = SpeakingUiState.Listening
        scope.launch {
            uiState = when (val result = speech.assessReading(sentence.text)) {
                is AssessmentResult.Done -> SpeakingUiState.Feedback(result.feedback)
                AssessmentResult.NothingRecognized ->
                    SpeakingUiState.Error("没听清。凑近一点，读完整句再停。")
                is AssessmentResult.Failed -> SpeakingUiState.Error(result.reason)
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) startAssessment()
        else uiState = SpeakingUiState.Error("没有录音权限，朗读没法进行。可以在系统设置里重新允许。")
    }

    fun onRecordClick() {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) startAssessment() else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    fun onPlaySample() {
        val speech = provider ?: return
        uiState = SpeakingUiState.Playing
        scope.launch {
            uiState = when (val result = speech.speak(sentence.text)) {
                SpeakResult.Done -> SpeakingUiState.Idle
                is SpeakResult.Failed -> SpeakingUiState.Error(result.reason)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("朗读") },
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
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(sentence.text, style = MaterialTheme.typography.titleLarge)
                    Text(
                        text = sentence.sourceNote,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = ::onPlaySample,
                    enabled = !busy && provider != null,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.AutoMirrored.Outlined.VolumeUp, contentDescription = null)
                    Text("听示范", modifier = Modifier.padding(start = 8.dp))
                }
                Button(
                    onClick = ::onRecordClick,
                    enabled = !busy && provider != null,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Outlined.Mic, contentDescription = null)
                    Text("我来读", modifier = Modifier.padding(start = 8.dp))
                }
            }

            when (val state = uiState) {
                SpeakingUiState.Idle -> Text(
                    text = "按「我来读」，读完整句后停一下，会自动结束录音。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SpeakingUiState.Playing -> BusyHint("正在播放示范…")
                SpeakingUiState.Listening -> BusyHint("在听你读…读完停一下就好")
                is SpeakingUiState.Error -> Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                is SpeakingUiState.Feedback -> FeedbackSection(state.feedback)
            }

            TextButton(
                onClick = {
                    sentenceIndex += 1
                    uiState = SpeakingUiState.Idle
                },
                enabled = !busy,
            ) {
                Icon(Icons.Outlined.Refresh, contentDescription = null)
                Text("换一句", modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun BusyHint(text: String) {
    Row(
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.padding(4.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun FeedbackSection(feedback: PronunciationFeedback) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = overallComment(feedback.pronunciationScore),
            style = MaterialTheme.typography.titleMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ScoreChip("总分", feedback.pronunciationScore)
            ScoreChip("准确", feedback.accuracyScore)
            ScoreChip("流利", feedback.fluencyScore)
            ScoreChip("完整", feedback.completenessScore)
        }
        if (feedback.recognizedText.isNotBlank()) {
            Text(
                text = "听到的是：${feedback.recognizedText}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val problems = feedback.problemWords
        if (problems.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("值得再看一眼的词", style = MaterialTheme.typography.labelLarge)
                problems.forEach { word ->
                    Text(
                        text = "${word.word} · ${word.errorType.labelZh} · 准确度 ${word.accuracyScore}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ScoreChip(label: String, score: Int) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = "$label $score",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}
