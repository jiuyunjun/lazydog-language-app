package com.lazydog.english.feature.pronunciation

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.lazydog.english.LazyDogApplication
import com.lazydog.english.core.designsystem.LazyDogTheme
import com.lazydog.english.core.speech.PlaybackSource
import com.lazydog.english.core.speech.PlaybackStatus
import com.lazydog.english.domain.pronunciation.CommonError
import com.lazydog.english.domain.pronunciation.Phoneme
import com.lazydog.english.domain.pronunciation.ProductionAttempt
import com.lazydog.english.domain.pronunciation.ProductionEvidence
import com.lazydog.english.domain.pronunciation.ProductionLevel
import com.lazydog.english.domain.pronunciation.ProductionScoring
import com.lazydog.english.domain.pronunciation.PronunciationTarget
import com.lazydog.english.domain.pronunciation.RecordingQuality
import com.lazydog.english.domain.speaking.AssessmentResult
import com.lazydog.english.domain.speaking.PronunciationFeedback
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 跟读（`发音与音标学习DESIGN.md` §12、§13、§26，设计稿 `design/pronunciation/Produce.dc.html`）。
 *
 * 反馈的排版顺序就是这一屏的主张：**目标音 → 这次的分 → 最近 5 次 → 哪个音出了问题 →
 * 怎么改**。只丢一个 73 分用户不知道该动哪儿；把「最近 5 次」摆在分数旁边，
 * 是为了让单次分数在上下文里被读，而不是被当成判决（§26.1）。
 *
 * 修正动作那句话来自音位表里手写的常见误读，不是现编的：猜错了会让用户照着一个
 * 错误的动作去改，比不给建议更糟。
 */
private sealed interface ProducePhase {
    data object Idle : ProducePhase
    data object Recording : ProducePhase
    data class Feedback(
        val attempt: ProductionAttempt,
        val feedback: PronunciationFeedback?,
        val problem: CommonError?,
        val history: List<Int>,
    ) : ProducePhase

    /** 录音不算数。不给低分、不进历史（§26.3）。 */
    data class Unusable(val quality: RecordingQuality) : ProducePhase

    /** 服务没返回。可以重试，不用重录。 */
    data class ServiceFailed(val reason: String) : ProducePhase
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProduceScreen(
    phonemeId: String,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val app = remember { context.applicationContext as LazyDogApplication }
    val catalog = remember { app.phonemeCatalog }
    val repository = remember { app.pronunciationRepository }
    val speech = remember { app.speechController }
    val scope = rememberCoroutineScope()
    val playback by app.speechController.playback.collectAsState()

    val phoneme = remember(phonemeId) { catalog.phoneme(phonemeId) }
    val targetId = remember(phonemeId) { PronunciationTarget.ofPhoneme(phonemeId) }
    val words = remember(phoneme) { phoneme?.exampleWords.orEmpty() }

    var wordIndex by remember { mutableStateOf(0) }
    var phase by remember { mutableStateOf<ProducePhase>(ProducePhase.Idle) }
    var speechConfigured by remember { mutableStateOf(true) }
    var showRationale by remember { mutableStateOf(false) }
    var micDenied by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        speechConfigured = app.userPreferences.speechKey.first().isNotBlank()
    }

    val current = words.getOrNull(wordIndex)?.word

    fun assess() {
        val text = current ?: return
        val target = phoneme ?: return
        phase = ProducePhase.Recording
        scope.launch {
            val result = speech.assessReading(text, phonemeLevel = true)
            val quality = ProductionEvidence.qualityOf(result, expectedWordCount = 1)
            if (result is AssessmentResult.Failed) {
                phase = ProducePhase.ServiceFailed(result.reason)
                return@launch
            }
            if (!quality.usable) {
                // 记下来是为了排查「为什么老提示我再录一次」，但它不算分。
                repository.recordProduction(
                    ProductionAttempt(
                        targetId = targetId,
                        level = ProductionLevel.Word,
                        text = text,
                        providerScore = null,
                        targetScore = null,
                        quality = quality,
                        occurredAt = System.currentTimeMillis(),
                    ),
                )
                phase = ProducePhase.Unusable(quality)
                return@launch
            }
            val feedback = (result as AssessmentResult.Done).feedback
            val targetScore = ProductionEvidence.targetScore(feedback, target.ipa)
            val attempt = ProductionAttempt(
                targetId = targetId,
                level = ProductionLevel.Word,
                text = text,
                providerScore = feedback.pronunciationScore,
                targetScore = targetScore,
                quality = quality,
                occurredAt = System.currentTimeMillis(),
            )
            repository.recordProduction(attempt)
            val history = repository.recentProduction(targetId)
                .sortedBy { it.occurredAt }
                .mapNotNull { it.usableScore }
                .takeLast(5)
            phase = ProducePhase.Feedback(
                attempt = attempt,
                feedback = feedback,
                problem = ProductionEvidence.problem(target, attempt.usableScore),
                history = history,
            )
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) assess() else micDenied = true
    }

    fun onRecord() {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) assess() else showRationale = true
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    phoneme?.let {
                        IpaText(
                            ipa = it.ipa,
                            style = MaterialTheme.typography.titleLarge,
                            spokenName = it.shortDescriptionZh,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { speech.stop(); onExit() }) {
                        Icon(Icons.Outlined.Close, contentDescription = "退出")
                    }
                },
                actions = {
                    if (words.isNotEmpty()) {
                        Text(
                            text = "${wordIndex + 1} / ${words.size}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 16.dp),
                        )
                    }
                },
            )
        },
    ) { padding ->
        if (phoneme == null || current == null) {
            CatalogUnavailable(Modifier.padding(padding))
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            if (micDenied) {
                MicDenied(onListenOnly = onExit)
            }
            if (!speechConfigured) {
                SpeechNotConfigured()
            }

            Prompt(
                word = current,
                phoneme = phoneme,
                playbackStatus = playback.statusOf(PlaybackSource.word(current).id),
                onPlaySample = { speech.onPlayClicked(PlaybackSource.word(current)) },
            )

            when (val state = phase) {
                is ProducePhase.Feedback -> FeedbackBlock(state, phoneme)
                is ProducePhase.Unusable -> UnusableBlock(state.quality)
                is ProducePhase.ServiceFailed -> ServiceFailedBlock(state.reason, onRetry = ::assess)
                else -> Unit
            }

            RecordControl(
                phase = phase,
                enabled = speechConfigured && !micDenied,
                onRecord = ::onRecord,
            )

            if (phase is ProducePhase.Feedback) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = ::onRecord, modifier = Modifier.fillMaxWidth()) { Text("再读一次") }
                    if (wordIndex + 1 < words.size) {
                        TextButton(
                            onClick = {
                                wordIndex += 1
                                phase = ProducePhase.Idle
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("下一个词") }
                    } else {
                        TextButton(onClick = onExit, modifier = Modifier.fillMaxWidth()) {
                            Text("今天到这里")
                        }
                    }
                }
            }
        }
    }

    if (showRationale) {
        AlertDialog(
            onDismissRequest = { showRationale = false },
            icon = { Icon(Icons.Outlined.Mic, contentDescription = null) },
            title = { Text("允许「懒狗放洋屁」录音？") },
            text = {
                Text(
                    "用来看你这个音发得怎么样。录音实时传给语音服务做这一次评估，" +
                        "评完就没了，不保存在手机上。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRationale = false
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    },
                ) { Text("允许") }
            },
            dismissButton = {
                TextButton(onClick = { showRationale = false }) { Text("不用了") }
            },
        )
    }
}

@Composable
private fun Prompt(
    word: String,
    phoneme: Phoneme,
    playbackStatus: PlaybackStatus,
    onPlaySample: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "听一遍，然后你来读",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = word, style = MaterialTheme.typography.displaySmall)
        Text(
            text = phoneme.exampleWords.firstOrNull { it.word == word }?.ipa.orEmpty(),
            style = MaterialTheme.typography.bodyLarge,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = onPlaySample) {
            if (playbackStatus == PlaybackStatus.Loading) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp))
            } else {
                Icon(
                    Icons.AutoMirrored.Outlined.VolumeUp,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            Text("听标准发音")
        }
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = phoneme.articulation.keyActionZh,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@Composable
private fun RecordControl(
    phase: ProducePhase,
    enabled: Boolean,
    onRecord: () -> Unit,
) {
    if (phase is ProducePhase.Feedback) return
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FilledIconButton(
            onClick = onRecord,
            enabled = enabled && phase !is ProducePhase.Recording,
            colors = if (phase is ProducePhase.Recording) {
                IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                )
            } else {
                IconButtonDefaults.filledIconButtonColors()
            },
            modifier = Modifier.size(88.dp),
        ) {
            if (phase is ProducePhase.Recording) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.onError,
                    modifier = Modifier.size(32.dp),
                )
            } else {
                Icon(Icons.Outlined.Mic, contentDescription = "开始录音", modifier = Modifier.size(38.dp))
            }
        }
        Text(
            text = if (phase is ProducePhase.Recording) "正在听，读完自动结束" else "点一下开始读",
            style = MaterialTheme.typography.labelLarge,
        )
        // 隐私那句每个录音屏都出现，不能只在第一次说一遍。
        Text(
            text = "录音评完就删，不留在手机上",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun FeedbackBlock(state: ProducePhase.Feedback, phoneme: Phoneme) {
    val extended = LazyDogTheme.extendedColors
    val score = state.attempt.usableScore ?: return
    val good = score >= ProductionScoring.WEAK_SCORE
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Surface(
            color = if (good) extended.correctContainer else extended.attentionContainer,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        ) {
            val onColor = if (good) extended.onCorrectContainer else extended.onAttentionContainer
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "$score",
                        style = MaterialTheme.typography.displaySmall,
                        fontFamily = FontFamily.Monospace,
                        color = onColor,
                    )
                    Text(
                        text = "这次 · ${state.attempt.text}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = onColor,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
                Text(
                    text = when {
                        score >= 85 -> "这个音站住了"
                        good -> "接近了，但还不稳"
                        else -> "这个音还没出来"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = onColor,
                )
                if (state.history.size > 1) {
                    // 单次分数必须在上下文里被读：一个 61 说明不了什么，连着五个才说明问题。
                    Text(
                        text = "最近 ${state.history.size} 次：${state.history.joinToString(" · ")}",
                        style = MaterialTheme.typography.labelSmall,
                        color = onColor,
                    )
                }
                if (state.attempt.targetScore == null) {
                    Text(
                        text = "这次没拿到 /${phoneme.ipa}/ 单独的分，上面是整个词的分数。",
                        style = MaterialTheme.typography.labelSmall,
                        color = onColor,
                    )
                }
            }
        }

        state.problem?.let { problem ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("这次主要问题", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "/${phoneme.ipa}/ 容易滑成 /${problem.substituteIpa}/——" +
                            "那样 ${problem.exampleEn} 会听成 ${problem.soundsLikeEn}。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(
                            text = "试试：${problem.fixZh}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        )
                    }
                }
            }
        }

        state.feedback?.words?.takeIf { it.size > 1 }?.let { words ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("逐词")
                words.forEach { word ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = word.word,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.width(84.dp),
                        )
                        LinearProgressIndicator(
                            progress = { word.accuracyScore / 100f },
                            gapSize = 0.dp,
                            drawStopIndicator = {},
                            modifier = Modifier.weight(1f).height(4.dp),
                        )
                        Text(
                            text = "${word.accuracyScore}",
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }
}

/** 录音不算数。不给低分、不进历史——没收到声音不代表这个音发不好。 */
@Composable
private fun UnusableBlock(quality: RecordingQuality) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("这次录音听不太清", style = MaterialTheme.typography.titleSmall)
            Text(
                text = quality.messageZh ?: "再来一次。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "这次不算分，也不会拉低你的记录。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun ServiceFailedBlock(reason: String, onRetry: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "这次没评上分",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = reason,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            OutlinedButton(onClick = onRetry) { Text("重试") }
        }
    }
}

/** 没给麦克风权限只停跟读，听辨照练——不能因为没麦克风整个模块进不去。 */
@Composable
private fun MicDenied(onListenOnly: () -> Unit) {
    Surface(
        color = LazyDogTheme.extendedColors.attentionContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val onColor = LazyDogTheme.extendedColors.onAttentionContainer
            Text("跟读需要麦克风", style = MaterialTheme.typography.titleSmall, color = onColor)
            Text(
                text = "系统里把麦克风权限关掉了。听辨训练不受影响，现在就能继续练。",
                style = MaterialTheme.typography.bodyMedium,
                color = onColor,
            )
            TextButton(onClick = onListenOnly) { Text("只练听辨") }
        }
    }
}

@Composable
private fun SpeechNotConfigured() {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("跟读需要先配置 Azure Speech", style = MaterialTheme.typography.titleSmall)
            Text(
                text = "和朗读、听力用的是同一份配置，在设置里填一次就行。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
