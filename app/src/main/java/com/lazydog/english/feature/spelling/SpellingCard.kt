package com.lazydog.english.feature.spelling

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.lazydog.english.LazyDogApplication
import com.lazydog.english.core.data.KnowledgeRepository
import com.lazydog.english.core.data.SpellingQueueEntry
import com.lazydog.english.core.designsystem.InteractiveEnglishText
import com.lazydog.english.core.designsystem.LazyDogTheme
import com.lazydog.english.domain.ask.AskContext
import com.lazydog.english.domain.ask.AskContextKind
import com.lazydog.english.domain.ask.AskDetail
import com.lazydog.english.domain.spelling.SpellingEngine
import com.lazydog.english.domain.spelling.SpellingEvaluation
import com.lazydog.english.domain.spelling.SpellingProgress
import com.lazydog.english.domain.spelling.SpellingQuestionType
import kotlinx.coroutines.launch

internal const val MAX_HINT_LEVEL = SpellingEngine.MAX_HINT_LEVEL

/**
 * 一张拼写卡要考的全部内容。
 *
 * 拼写练习入口和单词复习里熟词的产出卡共用它：两处考的是同一件事，
 * 就该走同一台机器（`DECISIONS.md` D-029）。谁把卡片递进来不影响判分。
 */
data class SpellingCard(
    val itemId: Long,
    val term: String,
    val ipa: String,
    val meaningZh: String,
    val pos: String,
    val exampleEn: String,
    val exampleZh: String,
    val progress: SpellingProgress,
    /**
     * 这个词现在到期了没有。没到期的属于额外练习：照样记进拼写画像，
     * 但不推动通用复习时间——加练不该把复习计划搅乱。
     */
    val dueNow: Boolean,
) {
    val questionType: SpellingQuestionType get() = SpellingEngine.questionType(progress)
}

fun SpellingQueueEntry.toSpellingCard() = SpellingCard(
    itemId = itemId,
    term = term,
    ipa = ipa,
    meaningZh = meaningZh,
    pos = pos,
    exampleEn = exampleEn,
    exampleZh = exampleZh,
    progress = progress,
    dueNow = dueNow,
)

/** 用户在这张卡上的作答过程。卡片翻篇后就丢掉，不跨卡保留。 */
data class SpellingAnswer(
    val hintLevel: Int = 0,
    val typed: String = "",
    val selectedOption: Int = -1,
    val lastResult: SpellingEvaluation? = null,
    val startedAtMillis: Long = System.currentTimeMillis(),
    val showHintSheet: Boolean = false,
) {
    /** 这张卡已经翻篇了：要么写对了，要么提示已经拉到底、答案摆在脸上。 */
    val resolved: Boolean get() = lastResult?.correct == true || hintLevel >= MAX_HINT_LEVEL
}

/**
 * 一张拼写卡的完整生命周期：出题、逐级提示、判分入库、按「下一个」交还控制权。
 *
 * 状态由自己持有并按 itemId 重置，所以调用方只要把卡递进来、
 * 在 [onResolved] 里往下翻就行，不用复制一遍提示梯度的逻辑。
 */
@Composable
fun SpellingCardView(
    card: SpellingCard,
    repository: KnowledgeRepository,
    onResolved: (correct: Boolean, usedHint: Boolean) -> Unit,
    onAnswerChange: (SpellingAnswer) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var answer by remember(card.itemId) { mutableStateOf(SpellingAnswer()) }

    LaunchedEffect(answer) { onAnswerChange(answer) }

    fun submittedAnswer(): String = when (card.questionType) {
        SpellingQuestionType.Recognition ->
            SpellingEngine.recognitionOptions(card.term).getOrNull(answer.selectedOption).orEmpty()
        SpellingQuestionType.PartialCompletion, SpellingQuestionType.ChunkRecall ->
            SpellingEngine.fillMasked(
                word = card.term,
                typed = answer.typed,
                weakSegments = card.progress.weakSegments,
                chunk = card.questionType == SpellingQuestionType.ChunkRecall,
            )
        else -> answer.typed
    }

    fun record(hintLevel: Int, onDone: (SpellingEvaluation?) -> Unit) {
        val submitted = submittedAnswer()
        val elapsed = System.currentTimeMillis() - answer.startedAtMillis
        scope.launch {
            val evaluation = repository.recordSpellingAttempt(
                itemId = card.itemId,
                expected = card.term,
                answer = submitted,
                questionType = card.questionType,
                hintLevel = hintLevel,
                responseTimeMillis = elapsed,
                audioPrompted = card.questionType.isAudioPrompted(),
                advanceReviewSchedule = card.dueNow,
            )
            onDone(evaluation)
        }
    }

    QuestionView(
        card = card,
        answer = answer,
        onTypedChange = { answer = answer.copy(typed = it) },
        onSelectOption = { answer = answer.copy(selectedOption = it) },
        onSubmit = { record(answer.hintLevel) { answer = answer.copy(lastResult = it) } },
        onRequestHint = {
            // 要提示本身不是一次作答：不记 attempt、不动阶段，只是把提示往上抬一级。
            val nextLevel = (answer.hintLevel + 1).coerceAtMost(MAX_HINT_LEVEL)
            answer = answer.copy(hintLevel = nextLevel, showHintSheet = true)
            // 最后一级把答案直接摆出来了，这张卡就此翻篇。得记一次零分提交，
            // 否则"提示要到底然后翻页"会成为一条不留痕迹的绕路。
            if (nextLevel >= MAX_HINT_LEVEL) record(MAX_HINT_LEVEL) {}
        },
        onNext = { onResolved(answer.lastResult?.correct == true, answer.hintLevel > 0) },
        onDismissHintSheet = { answer = answer.copy(showHintSheet = false) },
    )
}

/**
 * 这些题型底下才摆一个普通输入框。局部补全和引导回忆用的是逐字母格子，
 * 格子本身就是输入控件，再加一个框等于让人在两个地方之间来回看。
 */
private fun SpellingQuestionType.needsPlainTextField(): Boolean =
    this == SpellingQuestionType.FreeRecall ||
        this == SpellingQuestionType.DelayedFreeRecall

/**
 * 四选一之外的题型都能逐级要提示。选择题没有提示梯度可言——
 * 答案就在四个选项里，再给提示等于直接指出来。
 */
private fun SpellingQuestionType.hasHintLadder(): Boolean =
    this != SpellingQuestionType.Recognition

/** 题面靠声音给的题型，命中「音形对应」这一维。 */
private fun SpellingQuestionType.isAudioPrompted(): Boolean =
    this == SpellingQuestionType.Recognition ||
        this == SpellingQuestionType.FreeRecall ||
        this == SpellingQuestionType.DelayedFreeRecall

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuestionView(
    card: SpellingCard,
    answer: SpellingAnswer,
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
    val entry = card
    val extended = LazyDogTheme.extendedColors

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            when (card.questionType) {
                SpellingQuestionType.Recognition -> RecognitionBody(
                    card = card,
                    answer = answer,
                    onSelectOption = onSelectOption,
                    onPlay = { scope.launch { app.speechController.speakWord(entry.term) } },
                )
                SpellingQuestionType.PartialCompletion -> PartialBody(card, answer, onTypedChange)
                SpellingQuestionType.ChunkRecall -> ChunkBody(card, answer, onTypedChange)
                SpellingQuestionType.GuidedRecall -> GuidedBody(card, answer, onTypedChange)
                SpellingQuestionType.FreeRecall, SpellingQuestionType.DelayedFreeRecall -> FreeRecallBody(
                    card = card,
                    answer = answer,
                    onPlay = {
                        scope.launch {
                            val sentence = entry.exampleEn.ifBlank { entry.term }
                            app.speechController.speak(sentence)
                        }
                    },
                )
            }

            if (card.questionType.needsPlainTextField()) {
                OutlinedTextField(
                    value = answer.typed,
                    onValueChange = onTypedChange,
                    enabled = !answer.resolved,
                    singleLine = true,
                    label = { Text(inputLabel(card.questionType)) },
                    isError = answer.lastResult?.correct == false,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            val result = answer.lastResult
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
                                level = answer.hintLevel,
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
                    text = if (answer.hintLevel == 0) "写对了。" else "写对了，这次用了提示，算分打了折。",
                    style = MaterialTheme.typography.titleMedium,
                    color = extended.correct,
                )
            }
            if (answer.hintLevel >= MAX_HINT_LEVEL && result?.correct != true) {
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
            if (answer.resolved) {
                Button(
                    onClick = onNext,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                ) {
                    Text("下一个")
                }
            } else {
                if (card.questionType.hasHintLadder()) {
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
                    enabled = card.questionType != SpellingQuestionType.Recognition || answer.selectedOption >= 0,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                ) {
                    Text("确认")
                }
            }
        }
    }

    if (answer.showHintSheet) {
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(onDismissRequest = onDismissHintSheet, sheetState = sheetState) {
            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = "提示 ${answer.hintLevel} 级 · ${hintLevelName(answer.hintLevel)}",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = SpellingEngine.hintText(
                        expected = entry.term,
                        answer = answer.lastResult?.normalizedAnswer.orEmpty(),
                        level = answer.hintLevel,
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
    card: SpellingCard,
    answer: SpellingAnswer,
    onSelectOption: (Int) -> Unit,
    onPlay: () -> Unit,
) {
    val entry = card
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
                color = if (index == answer.selectedOption) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surface
                },
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = index == answer.selectedOption,
                        enabled = !answer.resolved,
                        role = Role.RadioButton,
                        onClick = { onSelectOption(index) },
                    ),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RadioButton(selected = index == answer.selectedOption, onClick = null)
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
private fun PartialBody(
    card: SpellingCard,
    answer: SpellingAnswer,
    onTypedChange: (String) -> Unit,
) {
    val entry = card
    val weakest = entry.progress.weakSegments.maxByOrNull { it.errorCount }
    if (weakest != null && weakest.errorCount > 1) {
        Badge(text = "你在这里错过 ${weakest.errorCount} 次", attention = true)
    }
    Text(entry.meaningZh, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    LetterSlots(
        masked = SpellingEngine.maskedWord(entry.term, entry.progress.weakSegments, chunk = false),
        typed = answer.typed,
        onTypedChange = onTypedChange,
        enabled = !answer.resolved,
    )
    Text(
        text = "补全缺失的字母",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ChunkBody(
    card: SpellingCard,
    answer: SpellingAnswer,
    onTypedChange: (String) -> Unit,
) {
    val entry = card
    Text(entry.meaningZh, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    LetterSlots(
        masked = SpellingEngine.maskedWord(entry.term, entry.progress.weakSegments, chunk = true),
        typed = answer.typed,
        onTypedChange = onTypedChange,
        enabled = !answer.resolved,
    )
    Text(
        text = "按词块想，不用一个字母一个字母地拼。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun GuidedBody(
    card: SpellingCard,
    answer: SpellingAnswer,
    onTypedChange: (String) -> Unit,
) {
    val entry = card
    Text(entry.meaningZh, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    // S4 只给首字母和长度，剩下的自己填；长度本身就是这一阶段允许的提示。
    LetterSlots(
        masked = entry.term.take(1) + "_".repeat((entry.term.length - 1).coerceAtLeast(0)),
        typed = answer.typed,
        onTypedChange = onTypedChange,
        enabled = !answer.resolved,
    )
    Text(
        text = "${entry.term.length} 个字母" +
            if (entry.ipa.isNotBlank()) " · ${entry.ipa}" else "",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * 逐字母下划线（设计稿 63 屏）：已给出的字母是普通文字，缺的每个字母各占一格，
 * 一格一条下划线，中间有间隔——不是一条通长的横线。打进去的字母按顺序落进空格里。
 *
 * 输入用一个透明的 [BasicTextField] 接管，界面上看到的就是这排格子：
 * 底下再摆一个输入框的话，用户得在两个地方之间来回看。
 */
@Composable
private fun LetterSlots(
    masked: String,
    typed: String,
    onTypedChange: (String) -> Unit,
    enabled: Boolean,
) {
    val blanks = masked.count { it == '_' }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(masked) { runCatching { focusRequester.requestFocus() } }

    BasicTextField(
        value = typed,
        onValueChange = { onTypedChange(it.filter { char -> !char.isWhitespace() }.take(blanks)) },
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Ascii,
        ),
        cursorBrush = SolidColor(Color.Transparent),
        modifier = Modifier.focusRequester(focusRequester),
        decorationBox = { innerTextField ->
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.semantics {
                    contentDescription = "补全 $masked，已填 ${typed.length} / $blanks 个字母"
                },
            ) {
                var typedIndex = 0
                masked.forEach { char ->
                    if (char != '_') {
                        Text(
                            text = char.toString(),
                            style = MaterialTheme.typography.headlineSmall,
                            fontFamily = FontFamily.Monospace,
                        )
                        return@forEach
                    }
                    val filled = typed.getOrNull(typedIndex)
                    val isNext = typedIndex == typed.length
                    typedIndex += 1
                    val lineColor = when {
                        filled != null -> MaterialTheme.colorScheme.primary
                        isNext && enabled -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.outline
                    }
                    Box(
                        contentAlignment = Alignment.BottomCenter,
                        modifier = Modifier
                            .width(24.dp)
                            .height(38.dp)
                            .drawBehind {
                                val stroke = if (filled != null || isNext) 2.dp.toPx() else 1.dp.toPx()
                                drawLine(
                                    color = lineColor,
                                    start = Offset(0f, size.height - stroke / 2),
                                    end = Offset(size.width, size.height - stroke / 2),
                                    strokeWidth = stroke,
                                )
                            },
                    ) {
                        Text(
                            text = filled?.toString().orEmpty(),
                            style = MaterialTheme.typography.headlineSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                }
                // 真正的输入框收进零宽的角落：字母由上面的格子画，
                // 但这个调用不能省——省了这个框就收不到键盘输入。
                Box(Modifier.width(0.dp)) { innerTextField() }
            }
        },
    )
}

/**
 * 语境默写的挖空句（设计稿 61 屏）：空位是一条画出来的下划线，
 * 不是一串下划线字符。这一级不给字符级提示，所以横线宽度固定，不透露字母数。
 */
@Composable
private fun ClozeSentence(sentence: String, blankFor: String) {
    val index = sentence.indexOf(blankFor, ignoreCase = true)
    if (index < 0) {
        Text(sentence, style = MaterialTheme.typography.titleMedium)
        return
    }
    val ruleColor = MaterialTheme.colorScheme.outline
    Row(verticalAlignment = Alignment.Bottom) {
        Text(sentence.take(index), style = MaterialTheme.typography.titleMedium)
        Box(
            modifier = Modifier
                .padding(horizontal = 4.dp)
                .width(88.dp)
                .height(24.dp)
                .drawBehind {
                    val stroke = 2.dp.toPx()
                    drawLine(
                        color = ruleColor,
                        start = Offset(0f, size.height - stroke / 2),
                        end = Offset(size.width, size.height - stroke / 2),
                        strokeWidth = stroke,
                    )
                },
        )
        Text(sentence.drop(index + blankFor.length), style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun FreeRecallBody(card: SpellingCard, answer: SpellingAnswer, onPlay: () -> Unit) {
    val entry = card
    val intervalDays = entry.progress.longestSuccessfulIntervalDays
    Badge(
        text = if (intervalDays >= 7) "无提示 · 上次成功隔了 $intervalDays 天" else "无提示",
        attention = false,
    )
    val sentence = entry.exampleEn
    if (sentence.contains(entry.term, ignoreCase = true)) {
        // 语境默写：把词从例句里挖掉，剩下的句子照给，别把答案漏在句子里。
        ClozeSentence(sentence = sentence, blankFor = entry.term)
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

/**
 * 拼写题的提问上下文。还没作答就把词形交出去，摇一摇就成了作弊入口，
 * 所以未解决前只给中文和阶段，不给拼写。
 */
internal fun spellingAskContext(card: SpellingCard, answer: SpellingAnswer): AskContext {
    val entry = card
    if (!answer.resolved) {
        return AskContext(
            kind = AskContextKind.Word,
            title = "正在默写一个词 · ${entry.meaningZh}",
            details = listOf(
                AskDetail("中文释义", entry.meaningZh),
                AskDetail("词性", entry.pos.ifBlank { "未标注" }),
                AskDetail("题型", card.questionType.name),
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
