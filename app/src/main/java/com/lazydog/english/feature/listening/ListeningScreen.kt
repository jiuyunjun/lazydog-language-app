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
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Replay
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lazydog.english.LazyDogApplication
import com.lazydog.english.core.designsystem.InteractiveEnglishText
import com.lazydog.english.core.designsystem.LazyDogTheme
import com.lazydog.english.domain.generation.GenerationResult
import com.lazydog.english.domain.listening.ListeningAnswer
import com.lazydog.english.domain.listening.ListeningHintLevel
import com.lazydog.english.domain.listening.ListeningItem
import com.lazydog.english.domain.listening.ListeningSetRequest
import com.lazydog.english.domain.listening.audioFeatureLabelZh
import com.lazydog.english.domain.listening.maskKeyExpression
import com.lazydog.english.domain.listening.summarizeListening
import com.lazydog.english.domain.listening.wordsOf
import kotlin.random.Random
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private enum class ListeningPhase { Pick, Loading, Question, Reveal, Summary }

/** 一轮训练的句数（英语听力训练模块DESIGN.md §22：默认 10 句，约 5～10 分钟）。 */
private const val SET_SIZE = 10

/**
 * 听力训练（英语听力训练模块DESIGN.md）。核心循环是：
 * 只听声音 → 猜意思（中文三选一）→ 揭晓英文 → 再听一次。
 *
 * 所以答题阶段界面上不能出现英文原文——出现了就退化成"看英文选中文"，
 * 训练不到"声音 → 语义"这条通路。提示按级放出，越晚听懂扣分越多，见 listeningScore。
 *
 * MVP 范围按文档 §25：不做语音识别、自由输入评价和跨局的能力画像。
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
    var answers by remember { mutableStateOf<List<ListeningAnswer>>(emptyList()) }
    var lastAnswer by remember { mutableStateOf<ListeningAnswer?>(null) }
    var savedExpression by remember { mutableStateOf(false) }
    var progressChars by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmExit by remember { mutableStateOf(false) }

    val current = items.getOrNull(index)

    /** 选项顺序按题目下标定死：每次重组都换位置的话，点选就变成抽奖了。 */
    val options = remember(current, index) {
        current?.let { item ->
            (listOf(item.meaningZh) + item.wrongMeaningsZh).shuffled(Random(index * 31 + 7))
        }.orEmpty()
    }

    fun play() {
        if (playing) return
        val text = current?.textEn ?: return
        // 揭晓之后再听不算进评分：这一题的成绩在答题那一刻就定了。
        if (phase == ListeningPhase.Question) playCount += 1
        scope.launch {
            playing = true
            app.speechController.speak(text)
            playing = false
        }
    }

    fun startSet() {
        phase = ListeningPhase.Loading
        error = null
        progressChars = 0
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
                onProgress = { progressChars = it },
            )
            when (result) {
                is GenerationResult.Success -> {
                    items = result.data
                    index = 0
                    playCount = 0
                    hintLevel = ListeningHintLevel.None
                    answers = emptyList()
                    lastAnswer = null
                    phase = ListeningPhase.Question
                }
                is GenerationResult.Failure -> {
                    error = result.reason
                    phase = ListeningPhase.Pick
                }
            }
        }
    }

    fun answer(choice: String) {
        val item = current ?: return
        val record = ListeningAnswer(
            item = item,
            correct = choice == item.meaningZh,
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
            phase = ListeningPhase.Summary
        } else {
            index += 1
            playCount = 0
            hintLevel = ListeningHintLevel.None
            phase = ListeningPhase.Question
        }
    }

    val midSession = phase == ListeningPhase.Question || phase == ListeningPhase.Reveal
    BackHandler(enabled = midSession) { confirmExit = true }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (phase) {
                            ListeningPhase.Summary -> "这一轮的结果"
                            ListeningPhase.Pick, ListeningPhase.Loading -> "听力训练"
                            else -> "Listening ${index + 1} / ${items.size}"
                        },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { if (midSession) confirmExit = true else onExit() }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        // 设计稿要求主操作固定在底部 88dp 区域内、不随内容滚动，整个学习流单手可完成。
        bottomBar = {
            when (phase) {
                ListeningPhase.Pick -> BottomActions {
                    PrimaryAction("开始 $SET_SIZE 句训练", onClick = ::startSet)
                }
                ListeningPhase.Reveal -> BottomActions {
                    SecondaryAction("再听一次", Icons.Outlined.Replay, enabled = !playing, onClick = ::play)
                    PrimaryAction(if (index + 1 >= items.size) "看结果" else "下一句", onClick = ::next)
                }
                ListeningPhase.Summary -> BottomActions {
                    SecondaryAction("再来一轮", onClick = { phase = ListeningPhase.Pick })
                    PrimaryAction("完成", onClick = onExit)
                }
                ListeningPhase.Loading, ListeningPhase.Question -> Unit
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
            ListeningPhase.Loading -> Loading(modifier = content, chars = progressChars, scene = scene.nameZh)
            ListeningPhase.Question -> current?.let { item ->
                Question(
                    modifier = content,
                    item = item,
                    index = index,
                    total = items.size,
                    options = options,
                    playCount = playCount,
                    playing = playing,
                    hintLevel = hintLevel,
                    onPlay = ::play,
                    onHint = { hintLevel = hintLevel.next() },
                    onAnswer = ::answer,
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
        Text("你今天想听什么？", style = MaterialTheme.typography.headlineSmall)
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
                                .semantics { contentDescription = "${item.nameZh}，${item.noteZh}" },
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

/** 底部固定操作区。主操作 56dp 高、每屏只有一个，次操作放它左边（设计稿 M3 组件映射）。 */
@Composable
private fun BottomActions(content: @Composable RowScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
private fun RowScope.PrimaryAction(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .weight(1f)
            .height(56.dp),
    ) {
        Text(label)
    }
}

@Composable
private fun RowScope.SecondaryAction(
    label: String,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .weight(1f)
            .height(56.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
        }
        Text(label)
    }
}

@Composable
private fun Loading(modifier: Modifier, chars: Int, scene: String) {
    Column(
        modifier = modifier.padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Text("正在写 $scene 的 $SET_SIZE 句…", style = MaterialTheme.typography.titleMedium)
        Text(
            text = if (chars > 0) "已经写了 $chars 个字符" else "接通中，稍等一下",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Question(
    modifier: Modifier,
    item: ListeningItem,
    index: Int,
    total: Int,
    options: List<String>,
    playCount: Int,
    playing: Boolean,
    hintLevel: ListeningHintLevel,
    onPlay: () -> Unit,
    onHint: () -> Unit,
    onAnswer: (String) -> Unit,
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

        // 播放键是这一页唯一的主要动作：进来先听，别的都往后放。
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
            Text(
                text = if (playCount == 0) "点一下开始听" else "已播放 $playCount 次",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text("这句话是什么意思？", style = MaterialTheme.typography.titleMedium)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            options.forEach { option ->
                OutlinedCard(
                    onClick = { onAnswer(option) },
                    enabled = playCount > 0,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = option,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
        if (playCount == 0) {
            Text(
                text = "先听一遍再选。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Hints(item = item, level = hintLevel, onHint = onHint)
        Spacer(Modifier.size(24.dp))
    }
}

/**
 * 分级提示（§5）。一次只放一级，用过的留在页面上——
 * 没听懂的人需要把线索攒在一起看，收起来只会逼他再点一遍。
 */
@Composable
private fun Hints(item: ListeningItem, level: ListeningHintLevel, onHint: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (level >= ListeningHintLevel.Scene) HintCard("场景提示", item.sceneHintZh)
        if (level >= ListeningHintLevel.Keyword) HintCard("关键词提示", item.keywordHintZh)
        if (level >= ListeningHintLevel.PartialText) {
            HintCard("听到的大概是", maskKeyExpression(item.textEn, item.keyExpression.en))
        }
        if (level.hasNext) {
            TextButton(onClick = onHint) {
                Icon(
                    imageVector = Icons.Outlined.Lightbulb,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    when (level) {
                        ListeningHintLevel.None -> "听不出来，给点提示"
                        ListeningHintLevel.Scene -> "还是不行，提示关键词"
                        else -> "给我看部分英文"
                    },
                )
            }
        }
    }
}

@Composable
private fun HintCard(title: String, body: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * 揭晓页（§6、§7）。
 *
 * "再听一次"是这个模块最重要的一步：知道意思之后再听同一段音，才会有
 * "原来他说的是这个"的那一下，声音和含义的关联就是在那时候建立的。所以它和"下一句"
 * 并排放在底部，不藏进菜单里。
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
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val tint = if (answer.correct) {
                LazyDogTheme.extendedColors.correct
            } else {
                MaterialTheme.colorScheme.error
            }
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

        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "重点表达",
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
                        text = "难听出来是因为：" +
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

        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("Listening Score ${answer.score}", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = scoreReason(answer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Text(
            text = "知道意思以后再听一遍，很多时候会突然听清。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(8.dp))
    }
}

/** 分数要能解释清楚是怎么来的，否则用户只会觉得系统在随便打分。 */
private fun scoreReason(answer: ListeningAnswer): String = buildString {
    if (!answer.correct) {
        append("这次选错了，看到英文才明白，按 ${answer.score} 分记。")
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
    if (answer.hintLevel != ListeningHintLevel.None) append("，用了${answer.hintLevel.labelZh}")
    append("。")
}

/** 一轮结束的结果页（§22、§23）。 */
@Composable
private fun Summary(modifier: Modifier, answers: List<ListeningAnswer>) {
    val summary = remember(answers) { summarizeListening(answers) }
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("今日真实英语听力", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "${summary.totalScore}",
            style = MaterialTheme.typography.displayMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SummaryRow("第一遍就听懂", "${summary.firstListenCount} / ${summary.total}")
            SummaryRow("多听几遍听懂", "${summary.repeatListenCount}")
            SummaryRow("靠提示听懂", "${summary.afterHintCount}")
            SummaryRow("没听懂", "${summary.missedCount}")
            SummaryRow("平均播放", "%.1f 次".format(summary.averagePlays))
        }
        if (summary.weakestFeature != null) {
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("今天最容易绊住你的", style = MaterialTheme.typography.labelMedium)
                    Text(
                        text = audioFeatureLabelZh(summary.weakestFeature),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "没能一听就懂的句子里，这一类出现得最多。明天可以多听几句带它的。",
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
                            text = "${record.score} 分 · ${scoreReason(record)}",
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
