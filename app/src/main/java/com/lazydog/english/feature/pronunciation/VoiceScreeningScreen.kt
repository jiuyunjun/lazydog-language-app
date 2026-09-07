package com.lazydog.english.feature.pronunciation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Shield
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
import androidx.compose.ui.unit.dp
import com.lazydog.english.LazyDogApplication
import com.lazydog.english.core.designsystem.LazyDogTheme
import com.lazydog.english.core.speech.PlaybackSource
import com.lazydog.english.core.speech.PlaybackStatus
import com.lazydog.english.domain.pronunciation.PerceptionAttempt
import com.lazydog.english.domain.pronunciation.PerceptionDifficulty
import com.lazydog.english.domain.pronunciation.PerceptionOption
import com.lazydog.english.domain.pronunciation.PerceptionQuestion
import com.lazydog.english.domain.pronunciation.PerceptionQuestionFactory
import com.lazydog.english.domain.pronunciation.PhonemeCatalog
import com.lazydog.english.domain.pronunciation.PronunciationProgress
import com.lazydog.english.domain.pronunciation.PronunciationTarget
import com.lazydog.english.domain.pronunciation.SkillEstimate
import kotlinx.coroutines.launch

/**
 * 首次进入的声音摸底（`发音与音标学习DESIGN.md` §7，设计稿 `design/pronunciation/Onboarding.dc.html`）。
 *
 * 三条口径：
 *
 * - **不从第一个音标开始。** 只覆盖高价值、高混淆的代表项，四分钟就够。
 * - **摸底期间不给对错。** 给了反馈用户就开始学了，摸出来的就不是起点。
 * - **结果是画像不是分数。** 不给「你的发音水平 73 分」，给「容易混 / 听得出读不准 /
 *   目前没问题」三组，每组都带证据数。
 *
 * 这一版**只做听辨摸底**，发音采样没做（见 ROADMAP M21.6）：跟读这一半需要麦克风权限，
 * 而摸底是用户第一次进这个模块——第一屏就要权限会把人挡在外面。发音那条线由平时的
 * 跟读自己长出来。
 */
private const val SCREENING_QUESTIONS = 12

private sealed interface ScreeningPhase {
    data object Intro : ScreeningPhase
    data object Asking : ScreeningPhase
    data object Done : ScreeningPhase
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun VoiceScreeningScreen(
    onExit: () -> Unit,
    onPractice: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val app = remember { context.applicationContext as LazyDogApplication }
    val catalog = remember { app.phonemeCatalog }
    val repository = remember { app.pronunciationRepository }
    val scope = rememberCoroutineScope()
    val playback by app.speechController.playback.collectAsState()

    val factory = remember(catalog) { PerceptionQuestionFactory(catalog) }
    // 按目录自带的难度取最容易混的几组，每组轮流出题——摸底要的是覆盖面，不是深度。
    val contrasts = remember(catalog) {
        catalog.contrasts.sortedByDescending { it.difficulty }.take(6)
    }

    var phase by remember { mutableStateOf<ScreeningPhase>(ScreeningPhase.Intro) }
    var index by remember { mutableStateOf(0) }
    var question by remember { mutableStateOf<PerceptionQuestion?>(null) }
    var results by remember { mutableStateOf<List<PronunciationProgress>>(emptyList()) }

    fun ask() {
        if (index >= SCREENING_QUESTIONS || contrasts.isEmpty()) {
            phase = ScreeningPhase.Done
            app.speechController.stop()
            scope.launch {
                app.userPreferences.setVoiceScreeningDone()
                results = contrasts.map { repository.progressFor(PronunciationTarget.ofContrast(it.id)) }
            }
            return
        }
        val contrast = contrasts[index % contrasts.size]
        val next = factory.next(contrast, PerceptionDifficulty.RotatingPair) ?: run {
            phase = ScreeningPhase.Done
            return
        }
        index += 1
        question = next
        app.speechController.play(PlaybackSource.word(next.promptWord))
    }

    fun answer(option: PerceptionOption?) {
        val q = question ?: return
        scope.launch {
            // 跳过也记一次，记成答错：「听不出来」正是摸底要收集的信息，不该消失。
            repository.recordPerception(
                PerceptionAttempt(
                    targetId = q.targetId,
                    exercise = q.exercise,
                    correct = option?.correct == true,
                    variantId = q.variantId,
                    occurredAt = System.currentTimeMillis(),
                ),
                expected = q.correctOption.label,
                answer = option?.label ?: "(跳过)",
            )
        }
        // 摸底期间不给对错，直接下一题。
        ask()
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(if (phase == ScreeningPhase.Done) "你的声音画像" else "声音摸底") },
                navigationIcon = {
                    IconButton(onClick = { app.speechController.stop(); onExit() }) {
                        Icon(Icons.Outlined.Close, contentDescription = "退出")
                    }
                },
                actions = {
                    if (phase == ScreeningPhase.Asking) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(end = 16.dp),
                        ) {
                            Text(
                                text = "$index / $SCREENING_QUESTIONS",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            LinearProgressIndicator(
                                progress = { index.toFloat() / SCREENING_QUESTIONS },
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
        if (catalog.isEmpty) {
            CatalogUnavailable(Modifier.padding(padding))
            return@Scaffold
        }
        when (phase) {
            ScreeningPhase.Intro -> Intro(
                onStart = { phase = ScreeningPhase.Asking; ask() },
                onSkip = onExit,
                modifier = Modifier.padding(padding),
            )

            ScreeningPhase.Asking -> {
                val q = question ?: return@Scaffold
                Asking(
                    question = q,
                    playbackStatus = playback.statusOf(PlaybackSource.word(q.promptWord).id),
                    onReplay = { app.speechController.play(PlaybackSource.word(q.promptWord)) },
                    onAnswer = { answer(it) },
                    onSkip = { answer(null) },
                    modifier = Modifier.padding(padding),
                )
            }

            ScreeningPhase.Done -> Result(
                results = results,
                catalog = catalog,
                onPractice = onPractice,
                onBrowse = onExit,
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@Composable
private fun Intro(onStart: () -> Unit, onSkip: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.size(64.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Outlined.GraphicEq,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(32.dp),
                )
            }
        }
        Text("先听听你现在能分清哪些", style = MaterialTheme.typography.headlineMedium)
        Text(
            text = "十二道听辨题，大约三分钟。这不是考试——做完你会拿到一张自己的声音画像，" +
                "之后练什么由它来决定。",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(
                Icons.Outlined.Shield,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = "发音标准按美音（en-US）。以后要支持英音会单独开一套，不会改这一套的口径。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(
                Icons.Outlined.Mic,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = "这一步只用听的，不需要麦克风。跟读那一半等你想练的时候再说。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) { Text("开始摸底") }
        TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) { Text("先随便看看") }
    }
}

@Composable
private fun Asking(
    question: PerceptionQuestion,
    playbackStatus: PlaybackStatus,
    onReplay: () -> Unit,
    onAnswer: (PerceptionOption) -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier.size(96.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (playbackStatus == PlaybackStatus.Loading) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    } else {
                        Icon(
                            Icons.AutoMirrored.Outlined.VolumeUp,
                            contentDescription = "已播放，请选择",
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(44.dp),
                        )
                    }
                }
            }
            Text(
                text = "你听到的是哪一个？",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            question.options.forEach { option ->
                Surface(
                    onClick = { onAnswer(option) },
                    color = MaterialTheme.colorScheme.surface,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant,
                    ),
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                ) {
                    Box(
                        contentAlignment = Alignment.CenterStart,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    ) {
                        Text(option.label, style = MaterialTheme.typography.headlineSmall)
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            OutlinedButton(onClick = onReplay) { Text("再放一次") }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onSkip) { Text("听不出来，跳过") }
        }

        Text(
            text = "摸底期间不告诉你对错，做完一起看。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 摸底结果。
 *
 * **不给一个总分。** 三组分别是「容易混」「还看不准」「目前没问题」，每组都带证据数——
 * 摸底一共才 12 题，每组两题，说「你的发音水平 73 分」是假精确。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Result(
    results: List<PronunciationProgress>,
    catalog: PhonemeCatalog,
    onPractice: (String) -> Unit,
    onBrowse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val extended = LazyDogTheme.extendedColors
    val confused = results.filter { it.perception.hasEvidence && it.perception.score < 0.6f }
        .sortedBy { it.perception.score }
    val unclear = results.filter { it.perception.hasEvidence && it.perception.score in 0.6f..0.85f }
    val fine = results.filter { it.perception.hasEvidence && it.perception.score > 0.85f }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(
            text = "基于刚才的 12 道听辨。样本还很少，练下去这张表会自己变准。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        ResultGroup("容易混淆", confused, catalog, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
        ResultGroup("还看不准", unclear, catalog, extended.attentionContainer, extended.onAttentionContainer)
        ResultGroup("目前没问题", fine, catalog, extended.correctContainer, extended.onCorrectContainer)

        if (results.none { it.perception.hasEvidence }) {
            Text(
                text = "这次没记到有效作答。回去随便挑一组练练也一样能长出画像来。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text(
            text = "每组两题，所以这只是个起点，不是结论——真正的判断要等样本攒够。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )

        val weakest = confused.firstOrNull() ?: unclear.firstOrNull()
        if (weakest != null) {
            Button(
                onClick = { onPractice(PronunciationTarget.rawId(weakest.targetId)) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("从最弱的一组开始练") }
        }
        TextButton(onClick = onBrowse, modifier = Modifier.fillMaxWidth()) { Text("先看看全部声音") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ResultGroup(
    title: String,
    items: List<PronunciationProgress>,
    catalog: PhonemeCatalog,
    container: androidx.compose.ui.graphics.Color,
    onContainer: androidx.compose.ui.graphics.Color,
) {
    if (items.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Surface(color = container, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items.forEach { progress ->
                    val raw = PronunciationTarget.rawId(progress.targetId)
                    val contrast = catalog.contrast(raw)
                    Row {
                        Text(
                            text = contrast?.let {
                                contrastLabel(
                                    catalog.phoneme(it.leftPhonemeId),
                                    catalog.phoneme(it.rightPhonemeId),
                                )
                            } ?: raw,
                            style = MaterialTheme.typography.bodyLarge,
                            color = onContainer,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "${progress.perception.sampleCount} 题",
                            style = MaterialTheme.typography.labelSmall,
                            color = onContainer,
                        )
                    }
                }
            }
        }
    }
}

/** 摸底还没做过时首页顶上的那一条。 */
@Composable
fun ScreeningPrompt(onStart: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onStart,
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("先摸个底", style = MaterialTheme.typography.titleSmall)
            Text(
                text = "十二道听辨，约三分钟。做完推荐就按你自己的弱项来，不用从第一个音标开始。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
