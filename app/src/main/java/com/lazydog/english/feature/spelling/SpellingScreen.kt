package com.lazydog.english.feature.spelling

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lazydog.english.LazyDogApplication
import com.lazydog.english.core.ask.ProvideAskContext
import com.lazydog.english.core.data.KnowledgeRepository
import com.lazydog.english.core.data.SpellingQueueEntry
import com.lazydog.english.core.designsystem.InteractiveEnglishText
import com.lazydog.english.core.designsystem.LazyDogTheme
import com.lazydog.english.domain.ask.AskContext
import com.lazydog.english.domain.ask.AskContextKind
import com.lazydog.english.domain.ask.AskDetail
import com.lazydog.english.domain.spelling.SpellingEngine
import com.lazydog.english.domain.spelling.SpellingEvaluation
import com.lazydog.english.domain.spelling.SpellingQuestionType
import com.lazydog.english.feature.ask.AskTopBarAction
import kotlinx.coroutines.launch

/**
 * 拼写练习（设计稿 59～61、63 屏）。
 *
 * 一轮 12 个词，每个词的题型由它自己的拼写阶段决定，不是全场同一种题。
 * 答错不直接给答案：先给最轻的一句反馈，用户主动要才升一级提示，
 * 每升一级本次得分下降（[SpellingEngine.masteryCredit]）。
 */
private sealed interface SpellingPhase {
    data object Loading : SpellingPhase
    data object Empty : SpellingPhase
    data class Question(
        val entries: List<SpellingQueueEntry>,
        val index: Int,
        /** 已经要到第几级提示；0 表示还没要过。 */
        val hintLevel: Int = 0,
        val typed: String = "",
        val selectedOption: Int = -1,
        /** 上一次提交的结果；null 表示这道题还没交过。 */
        val lastResult: SpellingEvaluation? = null,
        val startedAtMillis: Long = System.currentTimeMillis(),
        val showHintSheet: Boolean = false,
    ) : SpellingPhase {
        val entry: SpellingQueueEntry get() = entries[index]
        val questionType: SpellingQuestionType get() = SpellingEngine.questionType(entry.progress)
        /** 这道题已经翻篇了：要么写对了，要么提示已经拉到底、答案摆在脸上。 */
        val resolved: Boolean get() = lastResult?.correct == true || hintLevel >= MAX_HINT_LEVEL
    }
    data class Summary(val total: Int, val correctFirstTry: Int, val hintUsed: Int) : SpellingPhase
}

private const val MAX_HINT_LEVEL = 5

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpellingScreen(
    repository: KnowledgeRepository,
    onExit: () -> Unit,
    onOpenProfile: () -> Unit,
) {
    val context = LocalContext.current
    val app = remember { context.applicationContext as LazyDogApplication }
    val scope = rememberCoroutineScope()

    var phase by remember { mutableStateOf<SpellingPhase>(SpellingPhase.Loading) }
    var correctFirstTry by remember { mutableStateOf(0) }
    var hintUsed by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        val queue = repository.spellingQueue()
        phase = if (queue.isEmpty()) {
            SpellingPhase.Empty
        } else {
            SpellingPhase.Question(entries = queue, index = 0)
        }
    }

    // 练完或没题时把没放完的音掐掉，但留着已经热起来的蓝牙链路。
    // 只认"是不是还在答题"，不能拿整个 phase 做 key——那样每打一个字母都会重跑一次。
    val stillAnswering = phase is SpellingPhase.Question
    LaunchedEffect(stillAnswering) {
        if (!stillAnswering) app.speechController.stopSpeaking(keepLink = true)
    }

    fun advance(p: SpellingPhase.Question) {
        phase = if (p.index + 1 < p.entries.size) {
            SpellingPhase.Question(entries = p.entries, index = p.index + 1)
        } else {
            SpellingPhase.Summary(p.entries.size, correctFirstTry, hintUsed)
        }
    }

    fun answerOf(p: SpellingPhase.Question): String = when (p.questionType) {
        SpellingQuestionType.Recognition ->
            SpellingEngine.recognitionOptions(p.entry.term).getOrNull(p.selectedOption).orEmpty()
        SpellingQuestionType.PartialCompletion, SpellingQuestionType.ChunkRecall ->
            SpellingEngine.fillMasked(
                word = p.entry.term,
                typed = p.typed,
                weakSegments = p.entry.progress.weakSegments,
                chunk = p.questionType == SpellingQuestionType.ChunkRecall,
            )
        else -> p.typed
    }

    fun submit(p: SpellingPhase.Question) {
        val answer = answerOf(p)
        val elapsed = System.currentTimeMillis() - p.startedAtMillis
        scope.launch {
            val evaluation = repository.recordSpellingAttempt(
                itemId = p.entry.itemId,
                expected = p.entry.term,
                answer = answer,
                questionType = p.questionType,
                hintLevel = p.hintLevel,
                responseTimeMillis = elapsed,
                audioPrompted = p.questionType.isAudioPrompted(),
            )
            if (evaluation?.correct == true && p.hintLevel == 0) correctFirstTry += 1
            phase = p.copy(lastResult = evaluation)
        }
    }

    fun requestHint(p: SpellingPhase.Question) {
        // 要提示本身不是一次作答：不记 attempt、不动阶段，只是把提示往上抬一级。
        if (p.hintLevel == 0) hintUsed += 1
        val nextLevel = (p.hintLevel + 1).coerceAtMost(MAX_HINT_LEVEL)
        phase = p.copy(hintLevel = nextLevel, showHintSheet = true)
        if (nextLevel < MAX_HINT_LEVEL) return
        // 最后一级把答案直接摆出来了，这张卡就此翻篇。得记一次零分提交，
        // 否则"提示要到底然后翻页"会成为一条不留痕迹的绕路。
        scope.launch {
            repository.recordSpellingAttempt(
                itemId = p.entry.itemId,
                expected = p.entry.term,
                answer = answerOf(p),
                questionType = p.questionType,
                hintLevel = MAX_HINT_LEVEL,
                responseTimeMillis = System.currentTimeMillis() - p.startedAtMillis,
                audioPrompted = p.questionType.isAudioPrompted(),
            )
        }
    }

    ProvideAskContext((phase as? SpellingPhase.Question)?.toAskContext())

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val p = phase
                    Text(
                        text = if (p is SpellingPhase.Question) {
                            "拼写练习 · ${p.index + 1} / ${p.entries.size}"
                        } else {
                            "拼写练习"
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onExit) {
                        Icon(Icons.Outlined.Close, contentDescription = "退出")
                    }
                },
                actions = { AskTopBarAction() },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (val p = phase) {
                SpellingPhase.Loading -> CenterBox { CircularProgressIndicator() }
                SpellingPhase.Empty -> CenterBox {
                    Text(
                        text = "还没有能练拼写的词。先去学几个新词，或者复习几张卡，回来就有题了。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    TextButton(onClick = onExit) { Text("知道了") }
                }
                is SpellingPhase.Question -> {
                    LinearProgressIndicator(
                        progress = { (p.index + 1).toFloat() / p.entries.size },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                    )
                    QuestionView(
                        phase = p,
                        onTypedChange = { phase = p.copy(typed = it) },
                        onSelectOption = { phase = p.copy(selectedOption = it) },
                        onSubmit = { submit(p) },
                        onRequestHint = { requestHint(p) },
                        onNext = { advance(p) },
                        onDismissHintSheet = { phase = p.copy(showHintSheet = false) },
                    )
                }
                is SpellingPhase.Summary -> SummaryView(
                    phase = p,
                    onExit = onExit,
                    onOpenProfile = onOpenProfile,
                )
            }
        }
    }
}

/** 题面靠声音给的题型，命中「音形对应」这一维。 */
private fun SpellingQuestionType.isAudioPrompted(): Boolean =
    this == SpellingQuestionType.Recognition ||
        this == SpellingQuestionType.FreeRecall ||
        this == SpellingQuestionType.DelayedFreeRecall

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuestionView(
    phase: SpellingPhase.Question,
    onTypedChange: (String) -> Unit,
    onSelectOption: (Int) -> Unit,
    onSubmit: () -> Unit,
    onRequestHint: () -> Unit,
    onNext: () -> Unit,
    onDismissHintSheet: () -> Unit,
) {
    val context = LocalContext.current
    val app = remember { context.applicationContext as LazyDogApplication }
    val scope = rememberCoroutineScope()
    val entry = phase.entry
    val extended = LazyDogTheme.extendedColors

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            when (phase.questionType) {
                SpellingQuestionType.Recognition -> RecognitionBody(
                    phase = phase,
                    onSelectOption = onSelectOption,
                    onPlay = { scope.launch { app.speechController.speakWord(entry.term) } },
                )
                SpellingQuestionType.PartialCompletion -> PartialBody(phase = phase)
                SpellingQuestionType.ChunkRecall -> ChunkBody(phase = phase)
                SpellingQuestionType.GuidedRecall -> GuidedBody(phase = phase)
                SpellingQuestionType.FreeRecall, SpellingQuestionType.DelayedFreeRecall -> FreeRecallBody(
                    phase = phase,
                    onPlay = {
                        scope.launch {
                            val sentence = entry.exampleEn.ifBlank { entry.term }
                            app.speechController.speak(sentence)
                        }
                    },
                )
            }

            if (phase.questionType != SpellingQuestionType.Recognition) {
                OutlinedTextField(
                    value = phase.typed,
                    onValueChange = onTypedChange,
                    enabled = !phase.resolved,
                    singleLine = true,
                    label = { Text(inputLabel(phase.questionType)) },
                    isError = phase.lastResult?.correct == false,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            val result = phase.lastResult
            if (result != null && !result.correct) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = if (result.likelyTypo) {
                            "看着像手滑——这次不算退步，再打一次就好。"
                        } else {
                            SpellingEngine.hintText(
                                expected = entry.term,
                                answer = result.normalizedAnswer,
                                level = phase.hintLevel,
                                weakSegments = entry.progress.weakSegments,
                            )
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (result?.correct == true) {
                Text(
                    text = if (phase.hintLevel == 0) "写对了。" else "写对了，这次用了提示，算分打了折。",
                    style = MaterialTheme.typography.titleMedium,
                    color = extended.correct,
                )
            }
            if (phase.hintLevel >= MAX_HINT_LEVEL && result?.correct != true) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("答案：", style = MaterialTheme.typography.bodyMedium)
                    InteractiveEnglishText(
                        text = entry.term,
                        style = MaterialTheme.typography.headlineSmall,
                    )
                }
            }
        }

        Row(
            modifier = Modifier.padding(24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (phase.resolved) {
                Button(
                    onClick = onNext,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                ) {
                    Text("下一个")
                }
            } else {
                if (phase.questionType != SpellingQuestionType.Recognition) {
                    OutlinedButton(
                        onClick = onRequestHint,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Lightbulb,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 6.dp),
                        )
                        Text("再要一点提示")
                    }
                }
                Button(
                    onClick = onSubmit,
                    enabled = phase.questionType != SpellingQuestionType.Recognition || phase.selectedOption >= 0,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                ) {
                    Text("确认")
                }
            }
        }
    }

    if (phase.showHintSheet) {
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(onDismissRequest = onDismissHintSheet, sheetState = sheetState) {
            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = "提示 ${phase.hintLevel} 级 · ${hintLevelName(phase.hintLevel)}",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = SpellingEngine.hintText(
                        expected = entry.term,
                        answer = phase.lastResult?.normalizedAnswer.orEmpty(),
                        level = phase.hintLevel,
                        weakSegments = entry.progress.weakSegments,
                    ),
                    style = MaterialTheme.typography.headlineSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "每要一级提示，这道题的得分就降一档；提示只能一级一级往上要，不能直接跳到答案。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = onDismissHintSheet,
                    modifier = Modifier.padding(bottom = 24.dp),
                ) {
                    Text("知道了，再试一次")
                }
            }
        }
    }
}

private fun hintLevelName(level: Int): String = when (level) {
    1 -> "错在哪一类"
    2 -> "错误区域"
    3 -> "部分结构"
    4 -> "词块拆分"
    5 -> "完整答案"
    else -> "还没给提示"
}

private fun inputLabel(type: SpellingQuestionType): String = when (type) {
    SpellingQuestionType.PartialCompletion -> "补全缺失的字母"
    SpellingQuestionType.ChunkRecall -> "写出中间缺的那一块"
    SpellingQuestionType.GuidedRecall -> "写出完整单词"
    else -> "请输入完整单词"
}

@Composable
private fun RecognitionBody(
    phase: SpellingPhase.Question,
    onSelectOption: (Int) -> Unit,
    onPlay: () -> Unit,
) {
    val entry = phase.entry
    val options = remember(entry.term) { SpellingEngine.recognitionOptions(entry.term) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        IconButton(onClick = onPlay) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.VolumeUp,
                contentDescription = "读一遍",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            text = entry.ipa.ifBlank { entry.meaningZh },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Text("哪个拼写是正确的？", style = MaterialTheme.typography.titleMedium)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        options.forEachIndexed { index, option ->
            Surface(
                color = if (index == phase.selectedOption) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surface
                },
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = index == phase.selectedOption,
                        enabled = !phase.resolved,
                        role = Role.RadioButton,
                        onClick = { onSelectOption(index) },
                    ),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RadioButton(selected = index == phase.selectedOption, onClick = null)
                    Text(
                        text = option,
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

@Composable
private fun PartialBody(phase: SpellingPhase.Question) {
    val entry = phase.entry
    val weakest = entry.progress.weakSegments.maxByOrNull { it.errorCount }
    if (weakest != null && weakest.errorCount > 1) {
        Badge(text = "你在这里错过 ${weakest.errorCount} 次", attention = true)
    }
    Text(entry.meaningZh, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(
        text = SpellingEngine.maskedWord(entry.term, entry.progress.weakSegments, chunk = false),
        style = MaterialTheme.typography.headlineMedium,
        fontFamily = FontFamily.Monospace,
    )
    Text(
        text = "把下划线的部分补出来就行，不用重打整个词。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ChunkBody(phase: SpellingPhase.Question) {
    val entry = phase.entry
    Text(entry.meaningZh, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(
        text = SpellingEngine.maskedWord(entry.term, entry.progress.weakSegments, chunk = true)
            .replace(Regex("_+"), " _____ "),
        style = MaterialTheme.typography.headlineMedium,
        fontFamily = FontFamily.Monospace,
    )
    Text(
        text = "按词块想，不用一个字母一个字母地拼。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun GuidedBody(phase: SpellingPhase.Question) {
    val entry = phase.entry
    Text(entry.meaningZh, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(
        text = "${entry.term.take(1)}${"_".repeat((entry.term.length - 1).coerceAtLeast(0))}",
        style = MaterialTheme.typography.headlineMedium,
        fontFamily = FontFamily.Monospace,
    )
    Text(
        text = "${entry.term.length} 个字母" +
            if (entry.ipa.isNotBlank()) " · ${entry.ipa}" else "",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun FreeRecallBody(phase: SpellingPhase.Question, onPlay: () -> Unit) {
    val entry = phase.entry
    val intervalDays = entry.progress.longestSuccessfulIntervalDays
    Badge(
        text = if (intervalDays >= 7) "无提示 · 上次成功隔了 $intervalDays 天" else "无提示",
        attention = false,
    )
    val sentence = entry.exampleEn
    if (sentence.contains(entry.term, ignoreCase = true)) {
        // 语境默写：把词从例句里挖掉，剩下的句子照给，别把答案漏在句子里。
        Text(
            text = sentence.replace(entry.term, "________", ignoreCase = true),
            style = MaterialTheme.typography.titleMedium,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = onPlay) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.VolumeUp,
                    contentDescription = "播放整句",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = "播放整句",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        Text(entry.meaningZh, style = MaterialTheme.typography.headlineSmall)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = onPlay) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.VolumeUp,
                    contentDescription = "读一遍",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = "听一遍再写",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Badge(text: String, attention: Boolean) {
    val extended = LazyDogTheme.extendedColors
    Surface(
        color = if (attention) extended.attentionContainer else MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = if (attention) extended.attention else MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun SummaryView(
    phase: SpellingPhase.Summary,
    onExit: () -> Unit,
    onOpenProfile: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("这一轮练完了", style = MaterialTheme.typography.headlineSmall)
        Text(
            text = "${phase.total} 个词，${phase.correctFirstTry} 个一次就写对；${phase.hintUsed} 个用了提示。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "用过提示的词会自己排回来，不用记着回头找。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onExit, modifier = Modifier.fillMaxWidth()) { Text("完成") }
        TextButton(onClick = onOpenProfile) { Text("看看我的拼写弱点") }
    }
}

@Composable
private fun CenterBox(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        content()
    }
}

/**
 * 拼写题的提问上下文。还没作答就把词形交出去，摇一摇就成了作弊入口，
 * 所以未解决前只给中文和阶段，不给拼写。
 */
private fun SpellingPhase.Question.toAskContext(): AskContext {
    val entry = entry
    if (!resolved) {
        return AskContext(
            kind = AskContextKind.Word,
            title = "正在默写一个词 · ${entry.meaningZh}",
            details = listOf(
                AskDetail("中文释义", entry.meaningZh),
                AskDetail("词性", entry.pos.ifBlank { "未标注" }),
                AskDetail("题型", questionType.name),
                AskDetail(
                    "状态",
                    "学习者正在练这个词的拼写，绝对不能给出这个单词的拼写或任何字母，只能讲用法、场景或记忆方法",
                ),
            ),
            suggestions = listOf("这个意思一般用在什么场景？", "有什么不含拼写的记忆方法？"),
        )
    }
    return AskContext(
        kind = AskContextKind.Word,
        title = "${entry.term} · ${entry.meaningZh}",
        details = buildList {
            add(AskDetail("词条", entry.term))
            if (entry.ipa.isNotBlank()) add(AskDetail("音标", entry.ipa))
            add(AskDetail("词块拆分", SpellingEngine.chunkWord(entry.term).joinToString(" + ")))
            if (entry.exampleEn.isNotBlank()) add(AskDetail("例句", entry.exampleEn))
        },
        suggestions = listOf("这个词为什么这么拼？", "有哪些和它拼法容易混的词？"),
    )
}
