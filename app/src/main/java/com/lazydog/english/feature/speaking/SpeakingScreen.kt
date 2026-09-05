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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.lazydog.english.LazyDogApplication
import com.lazydog.english.core.speech.PlaybackSource
import com.lazydog.english.core.speech.PlaybackStatus
import com.lazydog.english.core.data.KnowledgeRepository
import com.lazydog.english.core.data.UserPreferences
import com.lazydog.english.core.designsystem.LazyDogTheme
import com.lazydog.english.core.designsystem.InteractiveEnglishText
import com.lazydog.english.core.designsystem.rememberWaitedSeconds
import com.lazydog.english.core.designsystem.stageDetail
import com.lazydog.english.domain.generation.GenerationResult
import com.lazydog.english.domain.generation.GenerationStage
import com.lazydog.english.domain.planning.DailyStep
import com.lazydog.english.domain.speaking.AssessmentResult
import com.lazydog.english.domain.speaking.PronunciationFeedback
import com.lazydog.english.domain.speaking.PronunciationTip
import com.lazydog.english.domain.speaking.SpeechRate
import com.lazydog.english.domain.speaking.TipKind
import com.lazydog.english.domain.speaking.localPronunciationTips
import com.lazydog.english.domain.speaking.overallComment
import java.time.LocalDate
import kotlinx.coroutines.launch

private data class PracticeSentence(val text: String, val sourceNote: String)

private val fallbackSentences = listOf(
    PracticeSentence("The smell of coffee lingered in the kitchen all morning.", "内置练习句"),
    PracticeSentence("She sent me the first draft of the report before lunch.", "内置练习句"),
    PracticeSentence("His excuse sounded plausible, but nobody really believed it.", "内置练习句"),
    PracticeSentence("The city tried to curb traffic downtown.", "内置练习句"),
)

/**
 * 朗读反馈只给少量可理解提示，不显示综合分数（UI_BRIEF.md 屏 19、PRODUCT.md §6.5）。
 * 有问题的词直接在原句里标出来，而不是另列一份带数字的清单。
 */
private sealed interface SpeakingUiState {
    data object Idle : SpeakingUiState
    data object Listening : SpeakingUiState
    data object GeneratingTips : SpeakingUiState
    data class Feedback(val feedback: PronunciationFeedback, val tips: List<PronunciationTip>) : SpeakingUiState
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
    val app = remember { context.applicationContext as LazyDogApplication }
    val speech = remember { app.speechController }
    val speechRate by prefs.speechRate.collectAsState(initial = SpeechRate.Normal)

    val vocab by repository.vocabulary.collectAsState(initial = emptyList())
    val sentences = remember(vocab) {
        vocab.filter { it.detail.exampleEn.isNotBlank() }
            .map { PracticeSentence(it.detail.exampleEn, "来自你的单词「${it.detail.term}」") }
            .ifEmpty { fallbackSentences }
    }
    var sentenceIndex by rememberSaveable { mutableIntStateOf(0) }
    val sentence = sentences[sentenceIndex % sentences.size]

    var uiState by remember { mutableStateOf<SpeakingUiState>(SpeakingUiState.Idle) }
    var stage by remember { mutableStateOf<GenerationStage>(GenerationStage.Connecting) }
    var showPermissionRationale by rememberSaveable { mutableStateOf(false) }
    // 「在不在播标准音」不由这一页说了算：播放状态是全局的（`语音服务DESIGN.md` §23）。
    val playback by speech.playback.collectAsState()
    val sampleSource = remember(sentence.text) { PlaybackSource.sentence(sentence.text) }
    val sampleStatus = playback.statusOf(sampleSource.id)

    val busy = sampleStatus == PlaybackStatus.Loading ||
        sampleStatus == PlaybackStatus.Playing ||
        uiState is SpeakingUiState.Listening ||
        uiState is SpeakingUiState.GeneratingTips

    // 拿到过一次发音反馈就算完成今日朗读步骤。
    LaunchedEffect(uiState is SpeakingUiState.Feedback) {
        if (uiState is SpeakingUiState.Feedback) {
            app.userPreferences.markTodayStepDone(LocalDate.now().toString(), DailyStep.Speaking.id)
        }
    }

    fun startAssessment() {
        uiState = SpeakingUiState.Listening
        scope.launch {
            when (val result = speech.assessReading(sentence.text)) {
                is AssessmentResult.Done -> {
                    uiState = SpeakingUiState.GeneratingTips
                    stage = GenerationStage.Connecting
                    val tipsResult = app.contentGenerator.explainPronunciation(
                        referenceText = sentence.text,
                        feedback = result.feedback,
                        onStage = { stage = it },
                    )
                    val tips = when (tipsResult) {
                        is GenerationResult.Success -> tipsResult.data
                        is GenerationResult.Failure -> localPronunciationTips(result.feedback)
                    }
                    uiState = SpeakingUiState.Feedback(result.feedback, tips)
                }
                AssessmentResult.NothingRecognized ->
                    uiState = SpeakingUiState.Error("没听清。凑近一点，读完整句再停。")
                is AssessmentResult.Failed -> uiState = SpeakingUiState.Error(result.reason)
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
        if (granted) startAssessment() else showPermissionRationale = true
    }

    fun onPlaySample() {
        speech.onPlayClicked(sampleSource)
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
            val problemWords = remember(uiState) {
                ((uiState as? SpeakingUiState.Feedback)?.feedback?.problemWords ?: emptyList())
                    .map { it.word.lowercase() }
                    .toSet()
            }
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    HighlightedSentence(sentence.text, problemWords)
                    Text(
                        text = sentence.sourceNote,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "语速",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SpeechRate.entries.forEach { rate ->
                    FilterChip(
                        selected = speechRate == rate,
                        onClick = { scope.launch { prefs.saveSpeechRate(rate) } },
                        label = { Text(rate.label) },
                        enabled = !busy,
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = ::onPlaySample,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.AutoMirrored.Outlined.VolumeUp, contentDescription = null)
                    Text("听标准音", modifier = Modifier.padding(start = 8.dp))
                }
                Button(
                    onClick = ::onRecordClick,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Outlined.Mic, contentDescription = null)
                    Text("我来读", modifier = Modifier.padding(start = 8.dp))
                }
            }

            when {
                sampleStatus == PlaybackStatus.Loading -> BusyHint("正在取标准音…")
                sampleStatus == PlaybackStatus.Playing -> BusyHint("正在播放标准音…")
                sampleStatus == PlaybackStatus.Error -> Text(
                    text = playback.error?.message.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                else -> when (val state = uiState) {
                    SpeakingUiState.Idle -> Text(
                        text = "按「我来读」，读完整句后停一下，会自动结束录音。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SpeakingUiState.Listening -> BusyHint("在听你读…读完停一下就好")
                    SpeakingUiState.GeneratingTips -> BusyHint(
                        text = "正在想怎么跟你说…",
                        detail = stageDetail(stage, rememberWaitedSeconds()),
                    )
                    is SpeakingUiState.Error -> Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    is SpeakingUiState.Feedback -> FeedbackSection(state.feedback, state.tips)
                }
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

    if (showPermissionRationale) {
        AlertDialog(
            onDismissRequest = { showPermissionRationale = false },
            icon = { Icon(Icons.Outlined.Mic, contentDescription = null) },
            title = { Text("允许「懒狗放洋屁」录音？") },
            text = {
                Text("用来判断你这句读得怎么样。录音只会实时传给语音服务做这一次评估，不会保存在手机上，也不会用于其他用途。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showPermissionRationale = false
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    },
                ) { Text("允许") }
            },
            dismissButton = {
                TextButton(onClick = { showPermissionRationale = false }) { Text("不用了") }
            },
        )
    }
}

/** 有问题的词直接标底色，不另列数字分数（UI_BRIEF.md 屏 19）。 */
@Composable
private fun HighlightedSentence(text: String, problemWords: Set<String>) {
    val extended = LazyDogTheme.extendedColors
    InteractiveEnglishText(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        highlightWords = problemWords,
        highlightStyle = SpanStyle(background = extended.attentionContainer),
    )
}

@Composable
private fun BusyHint(text: String, detail: String? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.padding(4.dp))
        Column {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            // 等 AI 时多一行"卡在哪一步、等了多久"；等麦克风和播放时没有这一行。
            if (detail != null) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun FeedbackSection(feedback: PronunciationFeedback, tips: List<PronunciationTip>) {
    val extended = LazyDogTheme.extendedColors
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = overallComment(feedback.pronunciationScore),
            style = MaterialTheme.typography.titleMedium,
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            tips.forEach { tip ->
                val (icon, color) = when (tip.kind) {
                    TipKind.Good -> Icons.Outlined.CheckCircle to extended.correct
                    TipKind.Attention -> Icons.Outlined.RecordVoiceOver to extended.attention
                }
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            InteractiveEnglishText(tip.titleZh, style = MaterialTheme.typography.bodyLarge)
                            // 发音提示正文里全是英文音和例词，那正是要查的东西。
                            InteractiveEnglishText(
                                text = tip.bodyZh,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                Icons.Outlined.MicOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = "这次朗读没有保存录音。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
