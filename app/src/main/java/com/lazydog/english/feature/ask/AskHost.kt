package com.lazydog.english.feature.ask

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.lazydog.english.LazyDogApplication
import com.lazydog.english.core.ask.AskController
import com.lazydog.english.core.ask.LocalAskController
import com.lazydog.english.core.ask.ShakeDetector
import com.lazydog.english.core.ask.ShakeToAsk
import com.lazydog.english.core.designsystem.InteractiveEnglishText
import com.lazydog.english.domain.ask.AskAddableTerm
import com.lazydog.english.domain.ask.AskAnswer
import com.lazydog.english.domain.ask.AskContext
import com.lazydog.english.domain.ask.AskExchange
import com.lazydog.english.domain.ask.AskRequest
import com.lazydog.english.domain.ask.AskValidation
import com.lazydog.english.domain.generation.GenerationResult
import com.lazydog.english.domain.speaking.TranscriptionResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 抽屉里的一轮问答；answerText 是流式累积的正文，answer 是校验通过后的完整结果。 */
private data class AskRound(
    val question: String,
    val answerText: String = "",
    val answer: AskAnswer? = null,
    val error: String? = null,
)

private enum class AddState { Added, Existing }

/**
 * 把一个学习页面包成"可以摇一摇提问"。页面自己用 ProvideAskContext 注册上下文，
 * 这里只负责触发方式、抽屉和一次会话的问答状态。关掉抽屉状态即清空（不留历史）。
 */
@Composable
fun AskHost(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val app = remember { context.applicationContext as LazyDogApplication }
    val scope = rememberCoroutineScope()
    val controller = remember { AskController() }

    val shakeEnabled by app.userPreferences.askShakeEnabled.collectAsState(initial = true)
    val sensitivity by app.userPreferences.askShakeSensitivity.collectAsState(initial = 1)
    val topBarIconPref by app.userPreferences.askTopBarIcon.collectAsState(initial = false)
    val hasSensor = remember { ShakeDetector.isAvailable(context) }

    // 没传感器就自动降级成顶栏问号，不管设置里那个开关是什么。
    controller.showTopBarIcon = controller.canAsk && (!hasSensor || topBarIconPref)

    ShakeToAsk(
        enabled = shakeEnabled && hasSensor && controller.canAsk && !controller.visible,
        sensitivity = sensitivity,
        onShake = controller::open,
    )

    CompositionLocalProvider(LocalAskController provides controller) {
        content()
        val askContext = controller.context
        if (controller.visible && askContext != null) {
            AskSheet(
                askContext = askContext,
                onDismiss = controller::close,
                onAsk = { request, onPartial ->
                    app.contentGenerator.askAboutContext(request, onPartial)
                },
                onTranscribe = { partial ->
                    app.speechController.transcribeContinuously(
                        languages = listOf("zh-CN", "en-US"),
                        onPartial = partial,
                    )
                },
                onStopTranscribing = app.speechController::stopTranscribing,
                learnerLevel = { app.userPreferences.learnerLevelDescription.first() },
                onAddToReview = { term ->
                    val id = app.knowledgeRepository.addVocabulary(
                        term = term.term,
                        meaningZh = term.meaningZh,
                    )
                    if (id != null) AddState.Added else AddState.Existing
                },
                scopeLaunch = { block -> scope.launch { block() } },
            )
        }
    }
}

/**
 * 学习页面顶栏里的问号入口。没有降级需要时不占位置，所以四个学习页可以无条件放进 actions。
 */
@Composable
fun AskTopBarAction() {
    val controller = LocalAskController.current ?: return
    if (!controller.showTopBarIcon) return
    IconButton(onClick = controller::open) {
        Icon(
            imageVector = Icons.Outlined.HelpOutline,
            contentDescription = "问一句",
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AskSheet(
    askContext: AskContext,
    onDismiss: () -> Unit,
    onAsk: suspend (AskRequest, ((String) -> Unit)?) -> GenerationResult<AskAnswer>,
    onTranscribe: suspend ((String) -> Unit) -> TranscriptionResult,
    onStopTranscribing: () -> Unit,
    learnerLevel: suspend () -> String,
    onAddToReview: suspend (AskAddableTerm) -> AddState,
    scopeLaunch: (suspend () -> Unit) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var rounds by remember { mutableStateOf<List<AskRound>>(emptyList()) }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var transcribing by remember { mutableStateOf(false) }
    var transcriptionError by remember { mutableStateOf<String?>(null) }
    var contextExpanded by remember { mutableStateOf(false) }
    val added = remember { mutableStateMapOf<String, AddState>() }
    val listScroll = rememberScrollState()
    val context = LocalContext.current

    fun transcribe() {
        if (busy) return
        if (transcribing) {
            onStopTranscribing()
            return
        }
        transcribing = true
        transcriptionError = null
        val beforeDictation = input.trim()
        scopeLaunch {
            val result = onTranscribe { partial ->
                scopeLaunch {
                    input = listOf(beforeDictation, partial.trim())
                        .filter(String::isNotBlank)
                        .joinToString(" ")
                        .take(AskValidation.MAX_QUESTION_LENGTH)
                }
            }
            when (result) {
                is TranscriptionResult.Done -> {
                    input = listOf(beforeDictation, result.text.trim())
                        .filter(String::isNotBlank)
                        .joinToString(" ")
                        .take(AskValidation.MAX_QUESTION_LENGTH)
                }
                TranscriptionResult.NothingRecognized -> transcriptionError = "没听清，再说一次试试。"
                is TranscriptionResult.Failed -> transcriptionError = result.reason
            }
            transcribing = false
        }
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) transcribe() else transcriptionError = "需要麦克风权限才能把语音转成文字。"
    }

    fun requestTranscription() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            transcribe()
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun ask(question: String) {
        val clean = question.trim()
        if (clean.isBlank() || busy) return
        input = ""
        busy = true
        rounds = rounds + AskRound(question = clean)
        scopeLaunch {
            val history = rounds.dropLast(1).mapNotNull { round ->
                round.answer?.let { AskExchange(round.question, it.answerZh) }
            }
            val result = onAsk(
                AskRequest(
                    context = askContext,
                    learnerLevel = learnerLevel(),
                    history = history,
                    question = clean,
                ),
                { partial ->
                    rounds = rounds.mapIndexed { index, round ->
                        if (index == rounds.lastIndex) round.copy(answerText = partial) else round
                    }
                },
            )
            rounds = rounds.mapIndexed { index, round ->
                if (index != rounds.lastIndex) {
                    round
                } else when (result) {
                    is GenerationResult.Success ->
                        round.copy(answerText = result.data.answerZh, answer = result.data, error = null)
                    is GenerationResult.Failure ->
                        round.copy(error = result.reason)
                }
            }
            busy = false
        }
    }

    LaunchedEffect(rounds.lastOrNull()?.answerText, rounds.size) {
        if (rounds.isNotEmpty()) listScroll.animateScrollTo(listScroll.maxValue)
    }

    ModalBottomSheet(
        onDismissRequest = {
            if (transcribing) onStopTranscribing()
            onDismiss()
        },
        sheetState = sheetState,
    ) {
        Column(
            // 这一屏的主体就是底部那个输入框，键盘一弹起来正好把它盖住。
            // sheet 是独立窗口，根上那次 imePadding 到不了这里，得自己让。
            modifier = (if (rounds.isEmpty()) Modifier else Modifier.fillMaxHeight(0.88f))
                .imePadding()
                .navigationBarsPadding()
                .padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (rounds.isEmpty()) {
                Text(
                    text = "想问什么？",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 18.dp),
                )
            }

            ContextCard(
                askContext = askContext,
                expanded = contextExpanded,
                onToggle = { contextExpanded = !contextExpanded },
            )

            if (rounds.isEmpty()) {
                askContext.suggestions.take(3).forEach { suggestion ->
                    SuggestionChip(
                        onClick = { ask(suggestion) },
                        label = { Text(suggestion) },
                        modifier = Modifier.padding(horizontal = 18.dp),
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(listScroll)
                        .padding(horizontal = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    rounds.forEach { round ->
                        QuestionBubble(round.question)
                        AnswerBubble(
                            round = round,
                            addedState = added,
                            onAdd = { term ->
                                scopeLaunch { added[term.term] = onAddToReview(term) }
                            },
                            onRetry = { ask(round.question) },
                        )
                    }
                }
            }

            InputRow(
                value = input,
                onValueChange = { if (it.length <= AskValidation.MAX_QUESTION_LENGTH) input = it },
                placeholder = if (rounds.isEmpty()) "自己打字问" else "接着问",
                enabled = !busy,
                transcribing = transcribing,
                onMic = ::requestTranscription,
                onSend = { ask(input) },
            )

            transcriptionError?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 18.dp),
                )
            }

            if (transcribing) {
                RecordingStatus(modifier = Modifier.padding(horizontal = 18.dp))
            }

            if (rounds.isEmpty()) {
                Text(
                    text = "摇一摇随时能叫我出来 · 往下拉关掉",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun RecordingStatus(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "recording")
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.height(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            repeat(3) { index ->
                val heightFraction by transition.animateFloat(
                    initialValue = 0.3f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 420, delayMillis = index * 110),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "recordingBar$index",
                )
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(16.dp * heightFraction)
                        .clip(MaterialTheme.shapes.extraSmall)
                        .background(MaterialTheme.colorScheme.error),
                )
            }
        }
        Text(
            text = "正在收音 · 停顿后自动结束，也可再点麦克风停止",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun ContextCard(
    askContext: AskContext,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp)
            .clickable(onClick = onToggle),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = askContext.kind.cardLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(text = askContext.title, style = MaterialTheme.typography.bodyMedium)
                }
                Icon(
                    imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) "收起 AI 拿到的内容" else "看看 AI 拿到了什么",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                askContext.details.forEach { detail ->
                    Column {
                        Text(
                            text = detail.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = detail.value,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    text = "只发这些内容给 AI，不截屏、不发整页文本。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

@Composable
private fun QuestionBubble(text: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            color = MaterialTheme.colorScheme.primary,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(0.84f),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.padding(horizontal = 13.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun AnswerBubble(
    round: AskRound,
    addedState: Map<String, AddState>,
    onAdd: (AskAddableTerm) -> Unit,
    onRetry: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                round.error != null -> {
                    Text(
                        text = "没答上来：${round.error}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = onRetry) { Text("再问一次") }
                }
                round.answerText.isBlank() -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    Text(
                        text = "想想…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> InteractiveEnglishText(
                    text = round.answerText,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            round.answer?.addable?.forEach { term ->
                val state = addedState[term.term]
                AssistChip(
                    onClick = { if (state == null) onAdd(term) },
                    enabled = state == null,
                    label = {
                        Text(
                            when (state) {
                                AddState.Added -> "${term.term} 已加进复习"
                                AddState.Existing -> "${term.term} 早就在知识库里了"
                                null -> "把 ${term.term} 加进复习"
                            },
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.BookmarkAdd,
                            contentDescription = null,
                            modifier = Modifier.size(AssistChipDefaults.IconSize),
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun InputRow(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    enabled: Boolean,
    transcribing: Boolean,
    onMic: () -> Unit,
    onSend: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(if (transcribing) "边说边显示…" else placeholder) },
            enabled = enabled && !transcribing,
            maxLines = 4,
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = onMic,
            enabled = enabled,
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                imageVector = if (transcribing) Icons.Outlined.StopCircle else Icons.Outlined.Mic,
                contentDescription = if (transcribing) "停止收音" else "语音提问",
                tint = if (transcribing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
        }
        FilledIconButton(
            onClick = onSend,
            enabled = enabled && !transcribing && value.isNotBlank(),
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.Send,
                contentDescription = "发出去",
            )
        }
    }
}
