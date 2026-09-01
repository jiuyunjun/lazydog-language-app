package com.lazydog.english.feature.listening

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Hearing
import androidx.compose.material.icons.outlined.HearingDisabled
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.SlowMotionVideo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lazydog.english.LazyDogApplication
import com.lazydog.english.core.designsystem.AiWaiting
import com.lazydog.english.core.designsystem.InteractiveEnglishText
import com.lazydog.english.core.designsystem.LazyDogTheme
import com.lazydog.english.domain.generation.GenerationResult
import com.lazydog.english.domain.generation.GenerationStage
import com.lazydog.english.domain.listening.ListeningAnswer
import com.lazydog.english.domain.listening.ListeningHintLevel
import com.lazydog.english.domain.listening.ListeningItem
import com.lazydog.english.domain.listening.ListeningSetRequest
import com.lazydog.english.domain.listening.analyzeSoundChanges
import com.lazydog.english.domain.listening.audioFeatureLabelZh
import com.lazydog.english.domain.listening.baseScore
import com.lazydog.english.domain.listening.maskKeyExpression
import com.lazydog.english.domain.listening.summarizeListening
import com.lazydog.english.domain.listening.wordsOf
import com.lazydog.english.domain.speaking.SpeechRate
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private enum class ListeningPhase { Pick, Loading, Question, Reveal, Waiting, Summary }

/** 一轮训练的句数（设计稿屏 50「开始 10 句训练 · 约 6 分钟」）。 */
private const val SET_SIZE = 10

/**
 * 攒够这么多句就开练，剩下的边听边补。
 *
 * 十句一次性等下来是几十秒白屏，而第一句到手时训练就已经能开始了；用户听前几句的
 * 这段时间，后面的句子还在源源不断补进来。留三句余量是因为前几句答得快——真追上了
 * 会停在"下一句还在写"上，但那已经是最坏情况。
 */
private const val MIN_ITEMS_TO_START = 3

/**
 * 听力训练（设计稿屏 50～58、英语听力训练模块DESIGN.md）。核心循环是：
 * 选场景 → 裸听 → 中文四选一 → 揭晓 → 再听一次 → 下一句 → 10 句总结。
 *
 * 两条不能破的规矩：
 * 1. **英文永远最后出现**。答题前只有播放键和播放次数，没有字幕、没有英文。提示四级递进，
 *    前两级不给拼写，用户仍在听音辨义；只有挖空英文起才落到文字，分数上限也从这里压。
 * 2. **揭晓页的主按钮是"再听一次"，不是"下一句"**。知道意思之后重听同一条音频，
 *    是把声音和含义焊在一起的那一下，不能降级成收尾动作。
 *
 * MVP 范围按文档 §25：不做语音识别、Shadowing 评分、多口音、自由输入判定；
 * 设计稿屏 50 的智能混合和屏 57 的听力能力档案要跨轮次历史，见 D-020。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListeningScreen(onExit: () -> Unit) {
    val context = LocalContext.current
    val app = remember { context.applicationContext as LazyDogApplication }
    val scope = rememberCoroutineScope()

    var phase by remember { mutableStateOf(ListeningPhase.Pick) }
    var scene by remember { mutableStateOf(listeningScenes.first()) }
    var items by remember { mutableStateOf<List<ListeningItem>>(emptyList()) }
    var index by remember { mutableStateOf(0) }
    var playCount by remember { mutableStateOf(0) }
    var playing by remember { mutableStateOf(false) }
    var hintLevel by remember { mutableStateOf(ListeningHintLevel.None) }
    /** 裸听阶段选项藏着，点"听完了，看选项"才出现（设计稿屏 51 → 屏 52）。 */
    var optionsShown by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<String?>(null) }
    var answers by remember { mutableStateOf<List<ListeningAnswer>>(emptyList()) }
    var lastAnswer by remember { mutableStateOf<ListeningAnswer?>(null) }
    var savedExpression by remember { mutableStateOf(false) }
    /** 生成走到哪一步了：没接通 / 模型在想 / 正文在写。 */
    var stage by remember { mutableStateOf<GenerationStage>(GenerationStage.Connecting) }
    /** 这一轮的句子还在生成中：总数按 [SET_SIZE] 显示，答到头也先等一下而不是收工。 */
    var generating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmExit by remember { mutableStateOf(false) }

    val current = items.getOrNull(index)

    /** 选项顺序按题目下标定死：每次重组都换位置的话，点选就变成抽奖了。 */
    val options = remember(current, index) {
        current?.allOptionsZh?.shuffled(Random(index * 31 + 7)).orEmpty()
    }

    fun play(rate: SpeechRate? = null) {
        if (playing) return
        val text = current?.textEn ?: return
        // 揭晓之后再听不算进评分：这一题的成绩在答题那一刻就定了。
        if (phase == ListeningPhase.Question) playCount += 1
        scope.launch {
            playing = true
            app.speechController.speak(text, rate)
            playing = false
        }
    }

    fun resetQuestion() {
        playCount = 0
        hintLevel = ListeningHintLevel.None
        optionsShown = false
        selected = null
    }

    fun startRound(newItems: List<ListeningItem>) {
        generating = false
        items = newItems
        index = 0
        answers = emptyList()
        lastAnswer = null
        resetQuestion()
        phase = ListeningPhase.Question
    }

    fun startSet() {
        phase = ListeningPhase.Loading
        error = null
        stage = GenerationStage.Connecting
        items = emptyList()
        index = 0
        answers = emptyList()
        lastAnswer = null
        resetQuestion()
        generating = true
        scope.launch {
            val prefs = app.userPreferences
            val result = app.contentGenerator.generateListeningSet(
                request = ListeningSetRequest(
                    sceneZh = scene.nameZh,
                    subScenesZh = scene.subScenes,
                    count = SET_SIZE,
                    learnerLevel = prefs.learnerLevelDescription.first(),
                    topics = prefs.topics.first().toList(),
                ),
                onStage = { stage = it },
                // 一句一句接：攒够开局的句数就进答题页，别让人对着进度条等完整批。
                onItem = { item ->
                    items = items + item
                    if (phase == ListeningPhase.Loading && items.size >= MIN_ITEMS_TO_START) {
                        phase = ListeningPhase.Question
                    }
                },
            )
            generating = false
            when (result) {
                // 服务端没走流式时一句都没增量回调过，这里补上；已经开局的话也是同一批内容。
                is GenerationResult.Success -> {
                    items = result.data
                    if (phase == ListeningPhase.Loading) startRound(result.data)
                }
                is GenerationResult.Failure -> {
                    error = result.reason
                    // 已经开练了就把手上的句子听完，不把人踢回选场景页。
                    if (phase == ListeningPhase.Loading) phase = ListeningPhase.Pick
                }
            }
        }
    }

    fun confirmAnswer() {
        val item = current ?: return
        val choice = selected ?: return
        val record = ListeningAnswer(
            item = item,
            chosenZh = choice,
            playCount = playCount,
            hintLevel = hintLevel,
        )
        answers = answers + record
        lastAnswer = record
        savedExpression = false
        phase = ListeningPhase.Reveal
    }

    fun next() {
        // 人没走，下一句马上就要放——把揭晓页的重听掐掉，但别放掉已经热起来的蓝牙链路。
        app.speechController.stopSpeaking(keepLink = true)
        if (index + 1 >= items.size) {
            // 答得比写得快：等下一句，而不是把这一轮当成十句都做完了。
            phase = if (generating) ListeningPhase.Waiting else ListeningPhase.Summary
        } else {
            index += 1
            resetQuestion()
            phase = ListeningPhase.Question
        }
    }

    // 等下一句期间生成还在跑：新句子一到就接着练，生成结束还没有就说明这一轮到此为止。
    LaunchedEffect(phase, items.size, generating) {
        if (phase != ListeningPhase.Waiting) return@LaunchedEffect
        when {
            index + 1 < items.size -> {
                index += 1
                resetQuestion()
                phase = ListeningPhase.Question
            }
            !generating -> phase = ListeningPhase.Summary
        }
    }

    /** 生成还没结束时按整轮的句数显示，免得进度条从「1 / 3」跳成「1 / 7」。 */
    val total = if (generating) maxOf(SET_SIZE, items.size) else items.size

    // 等下一句也算在局内：这时候退出，已经听完的几句一样会丢，得先问一声。
    val midSession = phase == ListeningPhase.Question ||
        phase == ListeningPhase.Reveal ||
        phase == ListeningPhase.Waiting
    BackHandler(enabled = midSession) { confirmExit = true }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    when (phase) {
                        ListeningPhase.Summary -> Text("这一轮的结果")
                        ListeningPhase.Pick, ListeningPhase.Loading, ListeningPhase.Waiting ->
                            Text("听力训练")
                        else -> QuestionTitle(index = index, total = total, item = current)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { if (midSession) confirmExit = true else onExit() }) {
                        // 学习流内用 close 而不是返回箭头：这一轮退出就没了，语义是"结束"不是"上一页"。
                        Icon(Icons.Outlined.Close, contentDescription = "退出")
                    }
                },
            )
        },
        // 设计稿要求主操作固定在底部 88dp 区域内、不随内容滚动，整个学习流单手可完成。
        bottomBar = {
            when (phase) {
                ListeningPhase.Pick -> BottomActions(note = "约 6 分钟 · 戴耳机效果更好") {
                    PrimaryAction("开始 $SET_SIZE 句训练", onClick = ::startSet)
                }
                ListeningPhase.Question -> if (optionsShown) {
                    BottomActions {
                        PrimaryAction("确认", enabled = selected != null, onClick = ::confirmAnswer)
                    }
                } else {
                    BottomActions {
                        PrimaryAction(
                            label = "听完了，看选项",
                            enabled = playCount > 0,
                            onClick = { optionsShown = true },
                        )
                    }
                }
                // 主按钮是"再听一次"：知道意思之后重听同一条音频才是这个模块的核心动作。
                ListeningPhase.Reveal -> BottomActions {
                    PrimaryAction(
                        label = if (lastAnswer?.correct == true) "知道意思了，再听一次" else "带着意思再听一次",
                        icon = Icons.Outlined.Hearing,
                        enabled = !playing,
                        weight = 1.8f,
                        onClick = { play() },
                    )
                    SecondaryAction(
                        label = if (index + 1 >= items.size && !generating) "看结果" else "下一句",
                        onClick = ::next,
                    )
                }
                ListeningPhase.Summary -> BottomActions {
                    val replay = remember(answers) { answers.filterNot { it.firstListen } }
                    if (replay.isNotEmpty()) {
                        SecondaryAction(
                            label = "再过一遍那 ${replay.size} 句",
                            weight = 1.5f,
                            onClick = { startRound(replay.map { it.item }) },
                        )
                    }
                    PrimaryAction("收工", onClick = onExit)
                }
                ListeningPhase.Loading, ListeningPhase.Waiting -> Unit
            }
        },
    ) { padding ->
        val content = Modifier
            .fillMaxSize()
            .padding(padding)
        when (phase) {
            ListeningPhase.Pick -> PickScene(
                modifier = content,
                selected = scene,
                error = error,
                onSelect = { scene = it },
            )
            ListeningPhase.Loading -> Loading(
                modifier = content,
                stage = stage,
                ready = items.size,
                scene = scene.nameZh,
            )
            // 答得比写得快，停在这儿等下一句写完。
            ListeningPhase.Waiting -> Loading(
                modifier = content,
                stage = stage,
                ready = items.size,
                scene = scene.nameZh,
                waitingForNext = true,
            )
            ListeningPhase.Question -> current?.let { item ->
                Question(
                    modifier = content,
                    item = item,
                    index = index,
                    total = total,
                    options = options,
                    optionsShown = optionsShown,
                    selected = selected,
                    playCount = playCount,
                    playing = playing,
                    hintLevel = hintLevel,
                    onPlay = { play() },
                    onPlaySlow = { play(SpeechRate.Slow) },
                    onSelect = { selected = it },
                    onHint = { hintLevel = hintLevel.next() },
                )
            }
            ListeningPhase.Reveal -> lastAnswer?.let { record ->
                Reveal(
                    modifier = content,
                    answer = record,
                    saved = savedExpression,
                    onSaveExpression = {
                        savedExpression = true
                        scope.launch {
                            app.knowledgeRepository.addExpression(
                                expressionEn = record.item.keyExpression.en,
                                meaningZh = record.item.keyExpression.meaningZh,
                            )
                        }
                    },
                )
            }
            ListeningPhase.Summary -> Summary(modifier = content, answers = answers)
        }
    }

    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text("退出这一轮？") },
            text = { Text("这一轮的句子是刚生成的，退出就没了，已经听完的 ${answers.size} 句也不会留下记录。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmExit = false
                        onExit()
                    },
                ) { Text("退出") }
            },
            dismissButton = {
                TextButton(onClick = { confirmExit = false }) { Text("继续听") }
            },
        )
    }
}

/** 顶栏只写「3 / 10」，副标题给场景，不在这儿泄露任何英文（设计稿屏 51）。 */
@Composable
private fun QuestionTitle(index: Int, total: Int, item: ListeningItem?) {
    Column {
        Text("${index + 1} / $total", style = MaterialTheme.typography.titleMedium)
        val where = item?.let {
            listOfNotNull(it.sceneZh.ifBlank { null }, it.subSceneZh.ifBlank { null }).joinToString(" · ")
        }.orEmpty()
        if (where.isNotBlank()) {
            Text(
                text = where,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---- 底部固定操作区 ----

/** 主操作 56dp 高、每屏只有一个，次操作放它旁边（设计稿 M3 组件映射）。 */
@Composable
private fun BottomActions(note: String? = null, content: @Composable RowScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
            if (note != null) {
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun RowScope.PrimaryAction(
    label: String,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    weight: Float = 1f,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .weight(weight)
            .height(56.dp),
    ) {
        ActionLabel(label, icon)
    }
}

@Composable
private fun RowScope.SecondaryAction(
    label: String,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    weight: Float = 1f,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .weight(weight)
            .height(56.dp),
    ) {
        ActionLabel(label, icon)
    }
}

@Composable
private fun ActionLabel(label: String, icon: ImageVector?) {
    if (icon != null) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(8.dp))
    }
    Text(label, maxLines = 1)
}

// ---- 各阶段页面 ----

@Composable
private fun PickScene(
    modifier: Modifier,
    selected: ListeningScene,
    error: String?,
    onSelect: (ListeningScene) -> Unit,
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("今天想听什么", style = MaterialTheme.typography.headlineSmall)
        Text(
            text = "先听声音，再猜意思。听不出来可以要提示，也可以多听几遍。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (error != null) {
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            listeningScenes.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { item ->
                        val active = item == selected
                        Surface(
                            color = if (active) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainer
                            },
                            shape = MaterialTheme.shapes.large,
                            onClick = { onSelect(item) },
                            modifier = Modifier
                                .weight(1f)
                                .semantics {
                                    contentDescription = "${item.nameZh}，${item.noteZh}"
                                    stateDescription = if (active) "已选中" else "未选中"
                                },
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Icon(
                                    imageVector = item.icon,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp),
                                )
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(item.nameZh, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        text = item.noteZh,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        Spacer(Modifier.size(8.dp))
    }
}

@Composable
private fun Loading(
    modifier: Modifier,
    stage: GenerationStage,
    ready: Int,
    scene: String,
    waitingForNext: Boolean = false,
) {
    AiWaiting(
        modifier = modifier,
        title = if (waitingForNext) "下一句还在写…" else "正在写 $scene 的 $SET_SIZE 句…",
        stage = stage,
        // 写好一句就往这边送，攒够就开练——这时候"攒了几句"比字符数和阶段都有用。
        detail = if (ready > 0) "已经写好 $ready 句，攒够 $MIN_ITEMS_TO_START 句就开始" else null,
    )
}

/**
 * 揭晓页的"为什么听起来不是这样"（设计稿屏 55 的讲解区）。
 *
 * 光把英文原文摆出来，用户只会觉得"这几个词我都认识啊"——真正没听出来的是
 * 这些词连起来之后的样子。所以原文旁边要指出这句里发生了哪几处连读、弱读、浊化，
 * 每处都说清"听着像什么"，并附上这一类的通则，下次换个句子也认得出来。
 *
 * 内容由 [analyzeSoundChanges] 本地算，不额外发请求——用户正等着看讲解，不能再等一次网络。
 */
@Composable
private fun SoundChangesCard(item: ListeningItem) {
    val changes = remember(item.textEn) {
        analyzeSoundChanges(item.textEn, focusEn = item.keyExpression.en)
    }
    if (changes.isEmpty()) return
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.VolumeUp,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = "为什么听起来不是这样",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            changes.forEach { change ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "${change.spanEn} · ${change.rule.labelZh}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(change.noteZh, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = change.rule.ruleZh,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun Question(
    modifier: Modifier,
    item: ListeningItem,
    index: Int,
    total: Int,
    options: List<String>,
    optionsShown: Boolean,
    selected: String?,
    playCount: Int,
    playing: Boolean,
    hintLevel: ListeningHintLevel,
    onPlay: () -> Unit,
    onPlaySlow: () -> Unit,
    onSelect: (String) -> Unit,
    onHint: () -> Unit,
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        LinearProgressIndicator(
            progress = { (index + 1f) / total },
            // 进度不能只靠视觉，TalkBack 要能念出第几题（设计稿无障碍清单）。
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "第 ${index + 1} 题，共 $total 题" },
        )

        if (optionsShown) {
            CompactPlayer(playCount, playing, onPlay, onPlaySlow)
            Text("他在说什么？", style = MaterialTheme.typography.titleMedium)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                options.forEach { option ->
                    OptionRow(
                        text = option,
                        checked = option == selected,
                        onClick = { onSelect(option) },
                    )
                }
            }
        } else {
            BigPlayButton(playCount, playing, onPlay)
            Text("这句话是什么意思？", style = MaterialTheme.typography.titleMedium)
        }

        Hints(item = item, level = hintLevel, onHint = onHint)
        Spacer(Modifier.size(24.dp))
    }
}

/** 裸听阶段：整页只有一个播放键，别的什么都不给（设计稿屏 51）。 */
@Composable
private fun BigPlayButton(playCount: Int, playing: Boolean, onPlay: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            onClick = onPlay,
            enabled = !playing,
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier
                .size(128.dp)
                .semantics {
                    contentDescription = if (playCount == 0) "播放语音" else "再播放一次"
                    // 音频按钮的状态变化要播报出来（设计稿无障碍清单）。
                    stateDescription = if (playing) "正在播放" else "已停止"
                },
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (playing) {
                    CircularProgressIndicator(modifier = Modifier.size(36.dp))
                } else {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.VolumeUp,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(48.dp),
                    )
                }
            }
        }
        PlayCountLine(playCount)
    }
}

/** 看到选项之后播放键让位给选项，缩成一行（设计稿屏 52）。 */
@Composable
private fun CompactPlayer(
    playCount: Int,
    playing: Boolean,
    onPlay: () -> Unit,
    onPlaySlow: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = onPlay,
                enabled = !playing,
                modifier = Modifier
                    .weight(1f)
                    .semantics { stateDescription = if (playing) "正在播放" else "已停止" },
            ) {
                Icon(Icons.Outlined.Replay, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("再听一遍")
            }
            OutlinedButton(
                onClick = onPlaySlow,
                enabled = !playing,
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    imageVector = Icons.Outlined.SlowMotionVideo,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(8.dp))
                Text("慢速")
            }
        }
        PlayCountLine(playCount)
    }
}

/**
 * 播放次数 + 这一题现在还能拿多少分。
 *
 * 预告分数上限是为了让"要不要再听一遍"变成一个能算清的选择，而不是猜——
 * 设计稿屏 51「第一遍就听懂，分数最高」、屏 52「再听一遍这题最高 70 分」。
 */
@Composable
private fun PlayCountLine(playCount: Int) {
    val text = if (playCount == 0) {
        "点一下开始听 · 第一遍就听懂，分数最高"
    } else {
        "已播放 $playCount 次 · 再听一遍这题最高 ${baseScore(playCount + 1)} 分"
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun OptionRow(text: String, checked: Boolean, onClick: () -> Unit) {
    // 先选后确认：四个选项挨得近，点一下就判定太容易误触（设计稿屏 52 的"确认"）。
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = if (checked) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = checked, onClick = null)
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(vertical = 14.dp, horizontal = 4.dp),
            )
        }
    }
}

/**
 * 分级提示（设计稿屏 53「英文放在最后一级」）。一次只放一级，用过的留在页面上——
 * 没听懂的人需要把线索攒在一起看，收起来只会逼他再点一遍。
 *
 * 每级都标出代价：不标的话用户不知道点下去要付什么，只能凭感觉犹豫。
 */
@Composable
private fun Hints(item: ListeningItem, level: ListeningHintLevel, onHint: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (level >= ListeningHintLevel.Scene) {
            HintCard("场景提示", "扣 15 分", item.sceneHintZh)
        }
        if (level >= ListeningHintLevel.Keyword) {
            HintCard("关键词提示", "扣 30 分", item.keywordHintZh)
        }
        if (level >= ListeningHintLevel.PartialText) {
            HintCard(
                title = "挖空英文",
                cost = "最高 50 分",
                body = maskKeyExpression(item.textEn, item.keyExpression.en),
            )
        }
        if (level >= ListeningHintLevel.FullText) {
            HintCard("完整英文", "最高 20 分", item.textEn)
        }
        if (level.hasNext) {
            TextButton(onClick = onHint) {
                Icon(Icons.Outlined.Lightbulb, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(
                    when (level) {
                        ListeningHintLevel.None -> "要点提示"
                        ListeningHintLevel.Scene -> "还是没听清，提示关键词"
                        ListeningHintLevel.Keyword -> "给我看挖空的英文"
                        else -> "直接给我完整英文"
                    },
                )
            }
        }
        if (!level.showsText) {
            Text(
                text = "看到英文之前，你都还在练听力。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HintCard(title: String, cost: String, body: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = cost,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * 揭晓页（设计稿屏 54 答对 / 屏 55 答错）。
 *
 * 答错时不能只把正确答案摆出来就完事——要指出**你听成了什么、为什么**，
 * 而且要落到具体的音（哪个词弱读成了什么、少了它意思怎么变）。用户需要知道自己栽在哪，
 * 而不只是知道自己错了。
 */
@Composable
private fun Reveal(
    modifier: Modifier,
    answer: ListeningAnswer,
    saved: Boolean,
    onSaveExpression: () -> Unit,
) {
    val item = answer.item
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // 对错同时给图标和文字，不单靠红绿区分（设计稿无障碍清单）。
        val tint = if (answer.correct) {
            LazyDogTheme.extendedColors.correct
        } else {
            MaterialTheme.colorScheme.error
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = if (answer.correct) Icons.Outlined.CheckCircle else Icons.Outlined.Cancel,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = if (answer.correct) "听懂了" else "这次没听出来",
                style = MaterialTheme.typography.titleMedium,
                color = tint,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "${answer.score} 分",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            InteractiveEnglishText(
                text = item.textEn,
                style = MaterialTheme.typography.headlineSmall,
                highlightWords = wordsOf(item.keyExpression.en).toSet(),
            )
            Text(
                text = item.meaningZh,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        answer.mishear?.let { mishear ->
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.HearingDisabled,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = "你听成了「${mishear.meaningZh}」",
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                    Text(mishear.whyZh, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "这一类叫「${mishear.mishearType.labelZh}」",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "这句的重点",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = item.keyExpression.en,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(item.keyExpression.meaningZh, style = MaterialTheme.typography.bodyMedium)
                if (item.audioFeatures.isNotEmpty()) {
                    Text(
                        text = "刚才难在这儿：" +
                            item.audioFeatures.joinToString("、") { audioFeatureLabelZh(it) },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onSaveExpression, enabled = !saved) {
                    Icon(
                        imageVector = if (saved) Icons.Outlined.Check else Icons.Outlined.Bookmark,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(if (saved) "已加入复习计划" else "加入复习计划")
                }
            }
        }

        SoundChangesCard(item)

        Text(
            text = scoreReason(answer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(8.dp))
    }
}

/** 分数要能解释清楚是怎么来的，否则用户只会觉得系统在随便打分。 */
private fun scoreReason(answer: ListeningAnswer): String = buildString {
    append("${answer.score} 分：")
    if (!answer.correct) {
        append("这次选错了，看到英文才明白。")
        return@buildString
    }
    append(
        when {
            answer.playCount <= 1 -> "第一遍就听懂"
            answer.playCount == 2 -> "第二遍听懂"
            answer.playCount == 3 -> "第三遍听懂"
            else -> "听了 ${answer.playCount} 遍听懂"
        },
    )
    if (answer.hintLevel == ListeningHintLevel.None) {
        append("，没用提示。")
    } else {
        append("，用了${answer.hintLevel.labelZh}。")
    }
}

/** 一轮结束的结果页（设计稿屏 56：首听率是主指标）。 */
@Composable
private fun Summary(modifier: Modifier, answers: List<ListeningAnswer>) {
    val summary = remember(answers) { summarizeListening(answers) }
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("${summary.total} 句听完了", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "${summary.totalScore}",
            style = MaterialTheme.typography.displayMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = "今日听力分",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SummaryRow("第一遍就听懂", "${summary.firstListenCount} / ${summary.total}")
            SummaryRow("多听几遍听懂", "${summary.repeatListenCount}")
            SummaryRow("靠提示听懂", "${summary.afterHintCount}")
            SummaryRow("没听懂", "${summary.missedCount}")
            SummaryRow("平均播放", "%.1f 次".format(summary.averagePlays))
        }
        if (summary.weakestFeature != null || summary.weakestMishear != null) {
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (summary.weakestFeature != null) {
                        Text("最容易漏的", style = MaterialTheme.typography.labelMedium)
                        Text(
                            text = audioFeatureLabelZh(summary.weakestFeature),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    if (summary.weakestMishear != null) {
                        Text(
                            text = "答错时最常栽在「${summary.weakestMishear.labelZh}」",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Text(
                        text = "听不出来不是笨，是这玩意儿本来就没发全。明天多给你几句带它的。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Text("这一轮的句子", style = MaterialTheme.typography.titleSmall)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            summary.answers.forEach { record ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        InteractiveEnglishText(
                            text = record.item.textEn,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = record.item.meaningZh,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = scoreReason(record),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.size(8.dp))
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
