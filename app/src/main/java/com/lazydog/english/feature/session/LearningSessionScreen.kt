package com.lazydog.english.feature.session

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.QuestionMark
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.lazydog.english.LazyDogApplication
import com.lazydog.english.core.designsystem.LazyDogTheme
import com.lazydog.english.core.model.ReviewGrade
import com.lazydog.english.core.model.SampleData
import com.lazydog.english.core.speech.SpeechController
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 第一版学习流：新词（看答案 + 四档自评）→ 语法讲解 → 小测 → 总结。
 * 内容为写死的示例数据；评分结果暂不落库，复习调度在 M1 接入。
 */
private enum class SessionStep(val label: String) {
    Words("单词"),
    Grammar("语法"),
    Quiz("小测"),
    Summary("总结"),
}

@Composable
fun LearningSessionScreen(onExit: () -> Unit) {
    var step by rememberSaveable { mutableStateOf(SessionStep.Words) }
    var wordIndex by rememberSaveable { mutableIntStateOf(0) }
    var revealed by rememberSaveable { mutableStateOf(false) }
    var quizIndex by rememberSaveable { mutableIntStateOf(0) }
    // -1 表示当前题还没作答（rememberSaveable 不适合存可空 Int）
    var selectedOption by rememberSaveable { mutableIntStateOf(-1) }
    var correctCount by rememberSaveable { mutableIntStateOf(0) }

    val words = SampleData.newWords
    val questions = SampleData.quizQuestions

    Scaffold(
        topBar = {
            SessionTopBar(
                step = step,
                counter = when (step) {
                    SessionStep.Words -> "新词 ${wordIndex + 1} / ${words.size}"
                    SessionStep.Grammar -> "语法 1 / 1"
                    SessionStep.Quiz -> "小测 ${quizIndex + 1} / ${questions.size}"
                    SessionStep.Summary -> ""
                },
                onExit = onExit,
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (step) {
                SessionStep.Words -> WordStep(
                    wordIndex = wordIndex,
                    revealed = revealed,
                    onReveal = { revealed = true },
                    onGrade = {
                        if (wordIndex + 1 < words.size) {
                            wordIndex += 1
                            revealed = false
                        } else {
                            step = SessionStep.Grammar
                        }
                    },
                )
                SessionStep.Grammar -> GrammarStep(onDone = { step = SessionStep.Quiz })
                SessionStep.Quiz -> QuizStep(
                    quizIndex = quizIndex,
                    selectedOption = selectedOption,
                    onSelect = { index ->
                        if (selectedOption < 0) {
                            selectedOption = index
                            if (index == questions[quizIndex].answerIndex) correctCount += 1
                        }
                    },
                    onNext = {
                        if (quizIndex + 1 < questions.size) {
                            quizIndex += 1
                            selectedOption = -1
                        } else {
                            step = SessionStep.Summary
                        }
                    },
                )
                SessionStep.Summary -> SummaryStep(
                    wordCount = words.size,
                    correctCount = correctCount,
                    totalQuestions = questions.size,
                    onExit = onExit,
                )
            }
        }
    }
}

@Composable
private fun SessionTopBar(step: SessionStep, counter: String, onExit: () -> Unit) {
    val stepNumber = step.ordinal + 1
    val totalSteps = SessionStep.entries.size
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onExit) {
                Icon(Icons.Outlined.Close, contentDescription = "保存并退出")
            }
            Text(
                text = counter,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp),
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .semantics { contentDescription = "第 $stepNumber 步，共 $totalSteps 步" },
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            LinearProgressIndicator(
                progress = { stepNumber.toFloat() / totalSteps },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp),
            )
            Text(
                text = "第 $stepNumber / $totalSteps 步 · ${step.label}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun WordStep(
    wordIndex: Int,
    revealed: Boolean,
    onReveal: () -> Unit,
    onGrade: (ReviewGrade) -> Unit,
) {
    val word = SampleData.newWords[wordIndex]
    val context = LocalContext.current
    val app = remember { context.applicationContext as LazyDogApplication }
    val speech: SpeechController = app.speechController
    val scope = rememberCoroutineScope()

    // 新单词出现时自动读一遍（设置里可关）。
    LaunchedEffect(wordIndex) {
        if (app.userPreferences.autoReadWords.first()) speech.speak(word.word)
    }

    val speakWord: () -> Unit = { scope.launch { speech.speak(word.word) } }
    if (!revealed) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(word.word, style = MaterialTheme.typography.displayMedium)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = word.ipa,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    IconButton(onClick = speakWord) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.VolumeUp,
                            contentDescription = "再读一遍",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        text = word.encounterNote,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    )
                }
            }
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onReveal,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                ) {
                    Text("看答案", style = MaterialTheme.typography.titleMedium)
                }
                TextButton(onClick = onReveal, modifier = Modifier.fillMaxWidth()) {
                    Text("这词又忘了？正常，直接看")
                }
            }
        }
    } else {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(word.word, style = MaterialTheme.typography.headlineLarge)
                    Text(
                        text = word.ipa,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    IconButton(onClick = speakWord) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.VolumeUp,
                            contentDescription = "再读一遍",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Text(word.meaningZh, style = MaterialTheme.typography.titleMedium)
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = word.exampleEn,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            IconButton(onClick = { scope.launch { speech.speak(word.exampleEn) } }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Outlined.VolumeUp,
                                    contentDescription = "朗读例句",
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        Text(
                            text = word.exampleZh,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "刚才想起来了吗？照实点，算法才准",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                GradeRow(onGrade = onGrade)
            }
        }
    }
}

/** 四档自评：固定顺序，图标 + 文字，不只靠颜色。 */
@Composable
private fun GradeRow(onGrade: (ReviewGrade) -> Unit) {
    val extended = LazyDogTheme.extendedColors
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ReviewGrade.entries.forEach { grade ->
            val (bg, fg, icon) = when (grade) {
                ReviewGrade.Forgot -> Triple(
                    MaterialTheme.colorScheme.errorContainer,
                    MaterialTheme.colorScheme.error,
                    Icons.Outlined.Close,
                )
                ReviewGrade.Hard -> Triple(
                    extended.attentionContainer,
                    extended.onAttentionContainer,
                    Icons.Outlined.QuestionMark,
                )
                ReviewGrade.Good -> Triple(
                    MaterialTheme.colorScheme.primaryContainer,
                    MaterialTheme.colorScheme.onPrimaryContainer,
                    Icons.Outlined.Check,
                )
                ReviewGrade.Easy -> Triple(
                    extended.correctContainer,
                    extended.onCorrectContainer,
                    Icons.Outlined.DoneAll,
                )
            }
            GradeCard(
                grade = grade,
                background = bg,
                foreground = fg,
                icon = icon,
                onClick = { onGrade(grade) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun GradeCard(
    grade: ReviewGrade,
    background: Color,
    foreground: Color,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        color = background,
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, foreground),
        modifier = modifier.heightIn(min = 72.dp),
    ) {
        Column(
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = foreground,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = grade.label,
                style = MaterialTheme.typography.labelMedium,
                color = foreground,
                textAlign = TextAlign.Center,
            )
            Text(
                text = grade.nextHint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun GrammarStep(onDone: () -> Unit) {
    val extended = LazyDogTheme.extendedColors
    val context = LocalContext.current
    val app = remember { context.applicationContext as LazyDogApplication }
    val scope = rememberCoroutineScope()
    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("现在完成进行时", style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = "have / has been + doing",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "一件事从过去开始，到现在还在做，或刚停下但影响还在。重点是「一直在」，不是「做完了」。",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ExampleCard(
                icon = Icons.Outlined.CheckCircle,
                iconTint = extended.correct,
                label = "这样说",
                labelColor = extended.correct,
                sentence = buildAnnotatedString {
                    append("I ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)) {
                        append("have been waiting")
                    }
                    append(" for the bus for 20 minutes.")
                },
                note = "还在等，而且已经等了 20 分钟。",
                onSpeak = {
                    scope.launch {
                        app.speechController.speak("I have been waiting for the bus for 20 minutes.")
                    }
                },
            )
            ExampleCard(
                icon = Icons.Outlined.Cancel,
                iconTint = MaterialTheme.colorScheme.error,
                label = "容易说错",
                labelColor = MaterialTheme.colorScheme.error,
                sentence = buildAnnotatedString {
                    append("I ")
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough, color = MaterialTheme.colorScheme.error)) {
                        append("am waiting")
                    }
                    append(" for the bus for 20 minutes.")
                },
                note = "现在进行时带不了「持续 20 分钟」这个时间段。",
            )
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "和现在完成时的差别：have waited 说结果，have been waiting 说过程。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(14.dp),
                )
            }
        }
        Button(
            onClick = onDone,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .height(56.dp),
        ) {
            Text("练两句", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun ExampleCard(
    icon: ImageVector,
    iconTint: Color,
    label: String,
    labelColor: Color,
    sentence: androidx.compose.ui.text.AnnotatedString,
    note: String,
    onSpeak: (() -> Unit)? = null,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(imageVector = icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(18.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = labelColor,
                    modifier = Modifier.weight(1f),
                )
                if (onSpeak != null) {
                    IconButton(onClick = onSpeak) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.VolumeUp,
                            contentDescription = "朗读例句",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
            Text(text = sentence, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun QuizStep(
    quizIndex: Int,
    selectedOption: Int,
    onSelect: (Int) -> Unit,
    onNext: () -> Unit,
) {
    val extended = LazyDogTheme.extendedColors
    val question = SampleData.quizQuestions[quizIndex]
    val answered = selectedOption >= 0
    val isLast = quizIndex == SampleData.quizQuestions.size - 1

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    text = question.tag,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
            Text(question.prompt, style = MaterialTheme.typography.titleLarge)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                question.options.forEachIndexed { index, option ->
                    val isAnswer = index == question.answerIndex
                    val isSelected = index == selectedOption
                    val border: Color
                    val container: Color
                    val content: Color
                    when {
                        answered && isAnswer -> {
                            border = extended.correct
                            container = extended.correctContainer
                            content = extended.onCorrectContainer
                        }
                        answered && isSelected -> {
                            border = MaterialTheme.colorScheme.error
                            container = MaterialTheme.colorScheme.errorContainer
                            content = MaterialTheme.colorScheme.onErrorContainer
                        }
                        else -> {
                            border = MaterialTheme.colorScheme.outlineVariant
                            container = MaterialTheme.colorScheme.surface
                            content = MaterialTheme.colorScheme.onSurface
                        }
                    }
                    Surface(
                        onClick = { onSelect(index) },
                        enabled = !answered,
                        shape = MaterialTheme.shapes.medium,
                        color = container,
                        border = BorderStroke(if (answered && (isAnswer || isSelected)) 2.dp else 1.dp, border),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            if (answered && isAnswer) {
                                Icon(
                                    imageVector = Icons.Outlined.CheckCircle,
                                    contentDescription = null,
                                    tint = extended.correct,
                                    modifier = Modifier.size(20.dp),
                                )
                            } else if (answered && isSelected) {
                                Icon(
                                    imageVector = Icons.Outlined.Cancel,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            Text(
                                text = option,
                                style = MaterialTheme.typography.bodyLarge,
                                color = content,
                                modifier = Modifier.weight(1f),
                            )
                            if (answered && isAnswer) {
                                Text(
                                    text = if (isSelected) "对了" else "正确答案",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = extended.correct,
                                )
                            } else if (answered && isSelected) {
                                Text(
                                    text = "你选的",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
            if (answered) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = question.explanationZh,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(14.dp),
                    )
                }
            }
        }
        if (answered) {
            Button(
                onClick = onNext,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .height(56.dp),
            ) {
                Text(if (isLast) "看总结" else "下一题", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun SummaryStep(
    wordCount: Int,
    correctCount: Int,
    totalQuestions: Int,
    onExit: () -> Unit,
) {
    val extended = LazyDogTheme.extendedColors
    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(top = 24.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.TaskAlt,
                    contentDescription = null,
                    tint = extended.correct,
                    modifier = Modifier.size(40.dp),
                )
                Text("今天的洋屁放完了", style = MaterialTheme.typography.headlineMedium)
                Text(
                    text = "第一版流程走完了：新词、语法、小测都过了一遍。",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SummaryStatCard("$wordCount", "新词见过", Modifier.weight(1f))
                SummaryStatCard("1", "语法点", Modifier.weight(1f))
                SummaryStatCard("$correctCount/$totalQuestions", "小测答对", Modifier.weight(1f))
            }
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "复习调度还没接入：这次的自评暂时不会安排下次复习，知识库和间隔复习在下个版本上线。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(18.dp),
                )
            }
        }
        Button(
            onClick = onExit,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .height(56.dp),
        ) {
            Text("收工", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun SummaryStatCard(number: String, label: String, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = MaterialTheme.shapes.large,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = number,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
