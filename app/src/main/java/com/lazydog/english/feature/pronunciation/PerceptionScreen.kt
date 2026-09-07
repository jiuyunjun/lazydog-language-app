package com.lazydog.english.feature.pronunciation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.lazydog.english.LazyDogApplication
import com.lazydog.english.core.designsystem.LazyDogTheme
import com.lazydog.english.core.speech.PlaybackSource
import com.lazydog.english.core.speech.PlaybackStatus
import com.lazydog.english.domain.pronunciation.DifficultyPolicy
import com.lazydog.english.domain.pronunciation.PerceptionAttempt
import com.lazydog.english.domain.pronunciation.PerceptionDifficulty
import com.lazydog.english.domain.pronunciation.PerceptionExercise
import com.lazydog.english.domain.pronunciation.PerceptionOption
import com.lazydog.english.domain.pronunciation.PerceptionQuestion
import com.lazydog.english.domain.pronunciation.PerceptionQuestionFactory
import com.lazydog.english.domain.pronunciation.PerceptionScoring
import com.lazydog.english.domain.pronunciation.PhonemeContrast
import com.lazydog.english.domain.pronunciation.PronunciationHint
import com.lazydog.english.domain.pronunciation.PronunciationTarget
import com.lazydog.english.domain.pronunciation.allowsSymbolRecall
import kotlinx.coroutines.launch

/**
 * 听辨训练（`发音与音标学习DESIGN.md` §10、§11、§18，设计稿 `design/pronunciation/Perceive.dc.html`）。
 *
 * 三条口径写在这一屏上，改动前请先读：
 *
 * - **答题时一屏只问一件事**：没有音标、没有解释、没有分数、没有下一步推荐（§31）。
 * - **顺序是先答 → 再给正确答案 → 再给解释**，答题前不泄露任何一半。
 * - **答错的主按钮是「再听一次这两个」，不是「下一题」**：错的那一刻是这组对比最值得听的
 *   时候，直接推进等于把这次错误浪费掉。
 */
private const val SESSION_QUESTIONS = 6

private sealed interface Phase {
    data object Asking : Phase
    data class Answered(val chosen: PerceptionOption) : Phase
    data object Finished : Phase
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PerceptionScreen(
    contrastId: String,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val app = remember { context.applicationContext as LazyDogApplication }
    val catalog = remember { app.phonemeCatalog }
    val repository = remember { app.pronunciationRepository }
    val scope = rememberCoroutineScope()
    val playback by app.speechController.playback.collectAsState()

    val contrast = remember(contrastId) { catalog.contrast(contrastId) }
    val factory = remember(catalog) { PerceptionQuestionFactory(catalog) }
    val targetId = remember(contrastId) { PronunciationTarget.ofContrast(contrastId) }

    var difficulty by remember { mutableStateOf(PerceptionDifficulty.FixedPair) }
    var question by remember { mutableStateOf<PerceptionQuestion?>(null) }
    var phase by remember { mutableStateOf<Phase>(Phase.Asking) }
    var index by remember { mutableStateOf(0) }
    var correctCount by remember { mutableStateOf(0) }
    var hint by remember { mutableStateOf(PronunciationHint.None) }
    var replays by remember { mutableStateOf(0) }
    var askedAt by remember { mutableStateOf(0L) }
    var recentVariants by remember { mutableStateOf(listOf<String>()) }
    var ready by remember { mutableStateOf(false) }

    fun promptSource(q: PerceptionQuestion) = PlaybackSource.word(q.promptWord)

    fun ask(next: PerceptionQuestion) {
        question = next
        phase = Phase.Asking
        hint = PronunciationHint.None
        replays = 0
        askedAt = System.currentTimeMillis()
        recentVariants = (recentVariants + next.variantId).takeLast(2)
        // 进来就自动播一次：这一题的题面是声音，让用户自己去点等于多一步。
        app.speechController.play(promptSource(next))
    }

    fun advance() {
        val current = contrast ?: return
        if (index >= SESSION_QUESTIONS) {
            phase = Phase.Finished
            app.speechController.stop()
            return
        }
        val next = factory.next(current, difficulty, recentVariants)
        if (next == null) {
            phase = Phase.Finished
            return
        }
        index += 1
        ask(next)
    }

    // 起始难度按这个目标最近的表现定：一直答得很好的人不该每次都从固定词对重新开始。
    LaunchedEffect(contrastId) {
        val progress = repository.progressFor(targetId)
        val recent = repository.recentPerception(targetId)
        val accuracy = PerceptionScoring.recentAccuracy(recent)
        var start = DifficultyPolicy.adjust(PerceptionDifficulty.RotatingPair, accuracy, recent.size)
        // Sound → IPA 只在这个音已经能听辨之后才出（§11.6）。
        if (start == PerceptionDifficulty.SymbolRecall && !progress.allowsSymbolRecall()) {
            start = PerceptionDifficulty.ThreeWay
        }
        difficulty = start
        ready = true
        advance()
    }

    fun answer(option: PerceptionOption) {
        val q = question ?: return
        phase = Phase.Answered(option)
        val attempt = PerceptionAttempt(
            targetId = q.targetId,
            exercise = q.exercise,
            correct = option.correct,
            replayCount = replays,
            hintLevel = hint.level,
            responseTimeMillis = (System.currentTimeMillis() - askedAt).coerceAtLeast(0L),
            variantId = q.variantId,
            occurredAt = System.currentTimeMillis(),
        )
        if (option.correct) correctCount += 1
        scope.launch {
            repository.recordPerception(attempt, expected = q.correctOption.label, answer = option.label)
            val recent = repository.recentPerception(q.targetId)
            val accuracy = PerceptionScoring.recentAccuracy(recent)
            var next = DifficultyPolicy.adjust(difficulty, accuracy, recent.size)
            if (next == PerceptionDifficulty.SymbolRecall &&
                !repository.progressFor(q.targetId).allowsSymbolRecall()
            ) {
                next = PerceptionDifficulty.ThreeWay
            }
            // 降级是静默的：档位变了，界面上不出现任何「退步」的话（UI_BRIEF §2.1）。
            difficulty = next
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = contrastLabel(
                            catalog.phoneme(contrast?.leftPhonemeId.orEmpty()),
                            catalog.phoneme(contrast?.rightPhonemeId.orEmpty()),
                        ),
                        fontFamily = FontFamily.Monospace,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { app.speechController.stop(); onExit() }) {
                        Icon(Icons.Outlined.Close, contentDescription = "退出")
                    }
                },
                actions = {
                    if (phase != Phase.Finished) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(end = 16.dp),
                        ) {
                            Text(
                                text = "$index / $SESSION_QUESTIONS",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            LinearProgressIndicator(
                                progress = { index.toFloat() / SESSION_QUESTIONS },
                                gapSize = 0.dp,
                                drawStopIndicator = {},
                                modifier = Modifier.size(width = 56.dp, height = 4.dp),
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        val current = contrast
        if (current == null) {
            CatalogUnavailable(Modifier.padding(padding))
            return@Scaffold
        }
        if (!ready) {
            Box(Modifier.padding(padding).fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        when (val state = phase) {
            Phase.Finished -> SessionSummary(
                correct = correctCount,
                total = index,
                onAgain = {
                    index = 0
                    correctCount = 0
                    advance()
                },
                onExit = onExit,
                modifier = Modifier.padding(padding),
            )

            else -> {
                val q = question ?: return@Scaffold
                QuestionBody(
                    question = q,
                    answered = state as? Phase.Answered,
                    hint = hint,
                    contrast = current,
                    catalog = catalog,
                    playbackStatus = playback.statusOf(promptSource(q).id),
                    onReplay = {
                        replays += 1
                        app.speechController.play(promptSource(q))
                    },
                    onPlayWord = { app.speechController.onPlayClicked(PlaybackSource.word(it)) },
                    onHint = {
                        hint = hint.next()
                        if (hint == PronunciationHint.Replay) {
                            replays += 1
                            app.speechController.play(promptSource(q))
                        }
                    },
                    onAnswer = ::answer,
                    onNext = { advance() },
                    modifier = Modifier.padding(padding),
                )
            }
        }
    }
}

@Composable
private fun QuestionBody(
    question: PerceptionQuestion,
    answered: Phase.Answered?,
    hint: PronunciationHint,
    contrast: PhonemeContrast,
    catalog: com.lazydog.english.domain.pronunciation.PhonemeCatalog,
    playbackStatus: PlaybackStatus,
    onReplay: () -> Unit,
    onPlayWord: (String) -> Unit,
    onHint: () -> Unit,
    onAnswer: (PerceptionOption) -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.extraLarge,
                    modifier = Modifier.size(104.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (playbackStatus == PlaybackStatus.Loading) {
                            CircularProgressIndicator(modifier = Modifier.size(32.dp))
                        } else {
                            Icon(
                                Icons.AutoMirrored.Outlined.VolumeUp,
                                contentDescription = "已播放，请选择",
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(48.dp),
                            )
                        }
                    }
                }
                Text(
                    text = when (question.exercise) {
                        PerceptionExercise.SoundToIpa -> "刚才这个声音，写成哪个符号？"
                        else -> "你听到的是哪一个？"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                question.options.forEach { option ->
                    OptionCard(
                        option = option,
                        answered = answered,
                        symbol = question.exercise == PerceptionExercise.SoundToIpa,
                        onClick = { if (answered == null) onAnswer(option) },
                    )
                }
            }

            // 答完才教：解释在这里，答题时这一整块不存在。
            if (answered != null) {
                Explanation(
                    question = question,
                    chosen = answered.chosen,
                    contrast = contrast,
                    catalog = catalog,
                    onPlayWord = onPlayWord,
                )
            } else if (hint != PronunciationHint.None) {
                HintPanel(question = question, hint = hint, catalog = catalog, onPlayWord = onPlayWord)
            }

            Spacer(Modifier.height(8.dp))
        }

        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (answered == null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = onReplay, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Outlined.Replay, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(8.dp))
                            Text("再放一次")
                        }
                        TextButton(
                            onClick = onHint,
                            enabled = !hint.isFinal,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Outlined.HelpOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(8.dp))
                            Text(if (hint == PronunciationHint.None) "给点提示" else "还是分不清")
                        }
                    }
                } else if (answered.chosen.correct) {
                    Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) { Text("下一题") }
                    TextButton(
                        onClick = { onPlayWord(question.correctOption.label) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("再听一次这两个") }
                } else {
                    // 错的那一刻是这组对比最值得听的时候，所以主按钮是重听，不是推进。
                    Button(
                        onClick = { onPlayWord(question.correctOption.label) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.VolumeUp, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("再听一次这两个")
                    }
                    TextButton(onClick = onNext, modifier = Modifier.fillMaxWidth()) { Text("下一题") }
                }
            }
        }
    }
}

@Composable
private fun OptionCard(
    option: PerceptionOption,
    answered: Phase.Answered?,
    symbol: Boolean,
    onClick: () -> Unit,
) {
    val extended = LazyDogTheme.extendedColors
    val chosen = answered?.chosen?.label == option.label
    val reveal = answered != null
    val container = when {
        reveal && option.correct -> extended.correctContainer
        reveal && chosen -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surface
    }
    val content = when {
        reveal && option.correct -> extended.onCorrectContainer
        reveal && chosen -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    val border = when {
        reveal && option.correct -> BorderStroke(2.dp, extended.correct)
        reveal && chosen -> BorderStroke(1.dp, MaterialTheme.colorScheme.error)
        reveal -> BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        else -> BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    }
    Surface(
        onClick = onClick,
        enabled = answered == null,
        color = container,
        contentColor = content,
        border = border,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().height(72.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (reveal) {
                Icon(
                    imageVector = if (option.correct) Icons.Outlined.Check else Icons.Outlined.Close,
                    contentDescription = null,
                    tint = if (option.correct || chosen) content else content.copy(alpha = 0.4f),
                    modifier = Modifier.size(24.dp),
                )
            }
            if (symbol) {
                IpaText(
                    ipa = option.label,
                    style = MaterialTheme.typography.headlineSmall,
                    color = content,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Text(
                    text = option.label,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f),
                )
            }
            if (reveal && !symbol) {
                Text(
                    text = option.ipa,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

/**
 * 提示面板。
 *
 * 是页面内的一块 `Surface`，**不是 BottomSheet**：题目必须一直看得见，
 * 遮住题目再给提示没有意义。
 */
@Composable
private fun HintPanel(
    question: PerceptionQuestion,
    hint: PronunciationHint,
    catalog: com.lazydog.english.domain.pronunciation.PhonemeCatalog,
    onPlayWord: (String) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row {
                Text("提示", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(
                    text = "第 ${hint.level} 级 / 共 ${PronunciationHint.RevealAnswer.level} 级",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            if (hint.level >= PronunciationHint.ContrastPlay.level) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    question.options.filter { it.correct || question.options.size == 2 }.forEach { option ->
                        OutlinedButton(onClick = { onPlayWord(option.label) }) { Text(option.label) }
                    }
                }
            }
            if (hint.level >= PronunciationHint.ShowIpa.level) {
                question.options.forEach { option ->
                    Text(
                        text = "${option.label}  ${option.ipa}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
            if (hint.level >= PronunciationHint.ShowArticulation.level) {
                listOf(question.leftPhonemeId, question.rightPhonemeId)
                    .mapNotNull { catalog.phoneme(it) }
                    .forEach { phoneme ->
                        Text(
                            text = "/${phoneme.ipa}/：${phoneme.articulation.keyActionZh}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
            }
            if (hint.isFinal) {
                Text(
                    text = "答案是 ${question.correctOption.label}。这一题不计入听辨分。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(
                text = "用了提示也算练。这一题会记成「需要第 ${hint.level} 级提示」。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun Explanation(
    question: PerceptionQuestion,
    chosen: PerceptionOption,
    contrast: PhonemeContrast,
    catalog: com.lazydog.english.domain.pronunciation.PhonemeCatalog,
    onPlayWord: (String) -> Unit,
) {
    val left = catalog.phoneme(contrast.leftPhonemeId)
    val right = catalog.phoneme(contrast.rightPhonemeId)
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = if (chosen.correct) "为什么是它" else "差在哪",
                style = MaterialTheme.typography.titleSmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOfNotNull(left, right).forEach { phoneme ->
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.weight(1f),
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            IpaText(
                                ipa = phoneme.ipa,
                                style = MaterialTheme.typography.titleMedium,
                                spokenName = phoneme.shortDescriptionZh,
                            )
                            Text(
                                text = phoneme.shortDescriptionZh,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            val pairNote = contrast.minimalPairs
                .firstOrNull { it.leftWord == question.correctOption.label || it.rightWord == question.correctOption.label }
                ?.noteZh
                .orEmpty()
            if (pairNote.isNotBlank()) {
                Text(text = pairNote, style = MaterialTheme.typography.bodyMedium)
            }
            OutlinedButton(onClick = { onPlayWord(question.correctOption.label) }) {
                Icon(Icons.AutoMirrored.Outlined.VolumeUp, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("再听 ${question.correctOption.label}")
            }
        }
    }
}

/**
 * 一轮练完。
 *
 * 完整的进步证据页在 M21.5，这里先给「这一轮对了几题」——它是事实，不是评价，
 * 而且不出现 XP、不出现「今天学了几个音标」。
 */
@Composable
private fun SessionSummary(
    correct: Int,
    total: Int,
    onAgain: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("这一轮练完了", style = MaterialTheme.typography.headlineSmall)
        Text(
            text = "$total 题里对了 $correct 题",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onAgain, modifier = Modifier.fillMaxWidth()) { Text("再练一轮") }
        TextButton(onClick = onExit, modifier = Modifier.fillMaxWidth()) { Text("今天到这里") }
    }
}
