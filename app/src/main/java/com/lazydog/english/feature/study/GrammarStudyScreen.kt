package com.lazydog.english.feature.study

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.LibraryAdd
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lazydog.english.LazyDogApplication
import com.lazydog.english.core.ask.ProvideAskContext
import com.lazydog.english.core.data.KnowledgeRepository
import com.lazydog.english.core.data.displayPattern
import com.lazydog.english.core.data.displaySummary
import com.lazydog.english.core.database.GrammarRecord
import com.lazydog.english.core.designsystem.LazyDogTheme
import com.lazydog.english.core.designsystem.InteractiveEnglishText
import com.lazydog.english.domain.ask.AskContext
import com.lazydog.english.domain.ask.AskContextKind
import com.lazydog.english.domain.ask.AskDetail
import com.lazydog.english.domain.generation.GeneratedGrammarLesson
import com.lazydog.english.domain.generation.GenerationResult
import com.lazydog.english.domain.generation.GrammarDrillGrading
import com.lazydog.english.domain.generation.GrammarDrillItem
import com.lazydog.english.domain.generation.GrammarDrillRequest
import com.lazydog.english.domain.generation.GrammarLessonRequest
import com.lazydog.english.domain.planning.DailyStep
import com.lazydog.english.domain.practice.MistakeSummary
import com.lazydog.english.feature.ask.AskTopBarAction
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 一次练习的来源：复习时是已有的知识项，新学时是刚存下的那条。 */
private data class PendingDrill(
    val itemId: Long?,
    val request: GrammarDrillRequest,
    val remainingDue: List<GrammarRecord>,
    val isReview: Boolean,
)

private data class DrillSession(
    val pending: PendingDrill,
    val items: List<GrammarDrillItem>,
    val index: Int = 0,
    val selected: Int? = null,
    val correctCount: Int = 0,
) {
    val current: GrammarDrillItem get() = items[index]
    val answered: Boolean get() = selected != null
    val isLast: Boolean get() = index == items.lastIndex
}

private sealed interface GrammarPhase {
    data object Loading : GrammarPhase
    data class DueOffer(val due: List<GrammarRecord>) : GrammarPhase
    data object Idle : GrammarPhase
    data object Generating : GrammarPhase
    data class Showing(val lesson: GeneratedGrammarLesson, val saving: Boolean) : GrammarPhase
    data class DrillLoading(val title: String) : GrammarPhase
    data class Drilling(val session: DrillSession) : GrammarPhase
    data class DrillFailed(val reason: String, val pending: PendingDrill) : GrammarPhase
    data class Failed(val reason: String) : GrammarPhase
    data class Summary(val practiced: List<String>) : GrammarPhase
}

private const val DUE_LIMIT = 3
private const val DRILL_COUNT = 4

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GrammarStudyScreen(
    repository: KnowledgeRepository,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val app = remember { context.applicationContext as LazyDogApplication }
    val scope = rememberCoroutineScope()

    var phase by remember { mutableStateOf<GrammarPhase>(GrammarPhase.Loading) }
    var focus by rememberSaveable { mutableStateOf("") }
    var progressChars by remember { mutableStateOf(0) }
    var practiced by remember { mutableStateOf<List<String>>(emptyList()) }
    var weakSpots by remember { mutableStateOf<List<MistakeSummary>>(emptyList()) }

    // 进来先看有没有到期的语法：到期的复习出的是题，不是再读一遍讲解。
    LaunchedEffect(Unit) {
        val now = System.currentTimeMillis()
        weakSpots = app.mistakeRepository.weakSpots(now)
        val due = repository.grammar.first()
            .filter { (it.item.nextReviewAt ?: Long.MAX_VALUE) <= now }
            .take(DUE_LIMIT)
        phase = if (due.isEmpty()) GrammarPhase.Idle else GrammarPhase.DueOffer(due)
    }

    fun startDrill(pending: PendingDrill) {
        phase = GrammarPhase.DrillLoading(pending.request.patternEn)
        progressChars = 0
        scope.launch {
            val result = app.contentGenerator.generateGrammarDrill(
                pending.request,
                onProgress = { chars -> progressChars = chars },
            )
            phase = when (result) {
                is GenerationResult.Success ->
                    GrammarPhase.Drilling(DrillSession(pending, result.data))
                is GenerationResult.Failure ->
                    GrammarPhase.DrillFailed(result.reason, pending)
            }
        }
    }

    fun drillFor(record: GrammarRecord, remaining: List<GrammarRecord>): PendingDrill = PendingDrill(
        itemId = record.item.id,
        request = GrammarDrillRequest(
            patternEn = record.detail.displayPattern(),
            labelZh = record.detail.labelZh,
            summaryZh = record.detail.displaySummary(),
            learnerLevel = "",
            count = DRILL_COUNT,
        ),
        remainingDue = remaining,
        isReview = true,
    )

    fun startDue(due: List<GrammarRecord>) {
        val first = due.firstOrNull() ?: return
        scope.launch {
            val level = app.userPreferences.grammarLevelDescription.first()
            val pending = drillFor(first, due.drop(1))
            startDrill(pending.copy(request = pending.request.copy(learnerLevel = level)))
        }
    }

    /** 一组题做完：按客观正确率给复习评分，再接着下一条到期的。 */
    fun finishDrill(session: DrillSession) {
        val total = session.items.size
        val correct = session.correctCount
        practiced = practiced + "${session.pending.request.patternEn} · 答对 $correct / $total"
        scope.launch {
            session.pending.itemId?.let { itemId ->
                repository.recordReview(
                    itemId = itemId,
                    grade = GrammarDrillGrading.gradeFor(correct, total),
                    source = "grammar_drill",
                )
            }
            app.userPreferences.markTodayStepDone(LocalDate.now().toString(), DailyStep.Grammar.id)
            val next = session.pending.remainingDue
            if (next.isEmpty()) {
                phase = GrammarPhase.Summary(practiced)
            } else {
                val level = app.userPreferences.grammarLevelDescription.first()
                val pending = drillFor(next.first(), next.drop(1))
                startDrill(pending.copy(request = pending.request.copy(learnerLevel = level)))
            }
        }
    }

    fun generate() {
        phase = GrammarPhase.Generating
        progressChars = 0
        scope.launch {
            val known = repository.grammar.first().map { it.detail.displayPattern() }.take(100)
            val result = app.contentGenerator.generateGrammarLesson(
                GrammarLessonRequest(
                    learnerLevel = app.userPreferences.grammarLevelDescription.first(),
                    focus = focus.trim().ifBlank { null },
                    knownGrammar = known,
                    // 没指定学什么时，让最近的错题决定讲哪一条。
                    weakSpots = weakSpots,
                ),
                onProgress = { chars -> progressChars = chars },
            )
            phase = when (result) {
                is GenerationResult.Success -> GrammarPhase.Showing(result.data, saving = false)
                is GenerationResult.Failure -> GrammarPhase.Failed(result.reason)
            }
        }
    }

    /** 记入知识库后直接进练习：讲解看懂了不等于形式写得对。 */
    fun saveAndPractice(lesson: GeneratedGrammarLesson) {
        phase = GrammarPhase.Showing(lesson, saving = true)
        scope.launch {
            val id = repository.addGrammar(
                patternEn = lesson.patternEn,
                labelZh = lesson.labelZh,
                summaryZh = lesson.summaryZh,
                explanationZh = lesson.explanationZh,
                exampleEn = lesson.goodExampleEn,
                exampleZh = lesson.goodExampleZh,
                badExampleEn = lesson.badExampleEn,
                badExampleNoteZh = lesson.badExampleNoteZh,
                tipZh = lesson.tipZh,
            )
            app.userPreferences.markTodayStepDone(LocalDate.now().toString(), DailyStep.Grammar.id)
            startDrill(
                PendingDrill(
                    itemId = id,
                    request = GrammarDrillRequest(
                        patternEn = lesson.patternEn,
                        labelZh = lesson.labelZh,
                        summaryZh = lesson.summaryZh,
                        learnerLevel = app.userPreferences.grammarLevelDescription.first(),
                        count = DRILL_COUNT,
                    ),
                    remainingDue = emptyList(),
                    isReview = false,
                ),
            )
        }
    }

    fun backToIdle() {
        focus = ""
        practiced = emptyList()
        phase = GrammarPhase.Idle
    }

    ProvideAskContext(
        when (val p = phase) {
            is GrammarPhase.Showing -> p.lesson.toAskContext()
            is GrammarPhase.Drilling -> p.session.toAskContext()
            else -> null
        },
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("语法") },
                navigationIcon = {
                    IconButton(onClick = onExit) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    (phase as? GrammarPhase.Drilling)?.let { p ->
                        Text(
                            text = "第 ${p.session.index + 1} / ${p.session.items.size} 题",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 12.dp),
                        )
                    }
                    AskTopBarAction()
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
            when (val p = phase) {
                GrammarPhase.Loading -> CenterBlock { CircularProgressIndicator() }
                is GrammarPhase.DueOffer -> DueOfferView(
                    due = p.due,
                    onPractice = { startDue(p.due) },
                    onLearnNew = { phase = GrammarPhase.Idle },
                )
                GrammarPhase.Idle -> {
                    WeakSpotCard(weakSpots)
                    Text(
                        text = if (weakSpots.isEmpty()) {
                            "想学哪个语法点？留空就让 AI 按你的语法水平挑一个。看完会当场出几道填空题。"
                        } else {
                            "想学哪个语法点？留空就挑一个能治上面这些错的。看完会当场出几道填空题。"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    OutlinedTextField(
                        value = focus,
                        onValueChange = { focus = it },
                        label = { Text("语法点（可不填）") },
                        placeholder = { Text("比如：现在完成进行时、第三人称单数") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(onClick = ::generate, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.AutoAwesome, contentDescription = null)
                        Text("让 AI 讲一讲", modifier = Modifier.padding(start = 8.dp))
                    }
                }
                GrammarPhase.Generating -> CenterBlock {
                    CircularProgressIndicator()
                    Text(
                        text = if (progressChars > 0) "AI 正在备课… 已生成 $progressChars 字" else "AI 正在备课…",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                is GrammarPhase.DrillLoading -> CenterBlock {
                    CircularProgressIndicator()
                    Text(
                        text = if (progressChars > 0) "在给「${p.title}」出题… 已生成 $progressChars 字"
                        else "在给「${p.title}」出几道填空题…",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                }
                is GrammarPhase.Failed -> CenterBlock {
                    Text(
                        text = "没拿到讲解：${p.reason}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                    Button(onClick = ::generate) { Text("再试一次") }
                    TextButton(onClick = ::backToIdle) { Text("换个想法") }
                }
                is GrammarPhase.DrillFailed -> CenterBlock {
                    Text(
                        text = "题没出来：${p.reason}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = "讲解已经记进知识库了，一个字没丢。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = { startDrill(p.pending) }) { Text("再出一次题") }
                    TextButton(onClick = { phase = GrammarPhase.Summary(practiced) }) { Text("这次先算了") }
                }
                is GrammarPhase.Showing -> LessonView(
                    lesson = p.lesson,
                    saving = p.saving,
                    onSave = { saveAndPractice(p.lesson) },
                    onAnother = ::backToIdle,
                )
                is GrammarPhase.Drilling -> DrillView(
                    session = p.session,
                    onSelect = { option ->
                        if (!p.session.answered) {
                            val correct = option == p.session.current.answerIndex
                            phase = GrammarPhase.Drilling(
                                p.session.copy(
                                    selected = option,
                                    correctCount = p.session.correctCount + if (correct) 1 else 0,
                                ),
                            )
                            // 错题按形式类别记下来，决定下次讲什么语法点。
                            if (!correct) {
                                scope.launch {
                                    app.mistakeRepository.recordGrammarMistake(
                                        itemId = p.session.pending.itemId,
                                        patternEn = p.session.pending.request.patternEn,
                                        item = p.session.current,
                                        chosenIndex = option,
                                    )
                                }
                            }
                        }
                    },
                    onNext = {
                        if (p.session.isLast) {
                            finishDrill(p.session)
                        } else {
                            phase = GrammarPhase.Drilling(
                                p.session.copy(index = p.session.index + 1, selected = null),
                            )
                        }
                    },
                )
                is GrammarPhase.Summary -> SummaryView(
                    practiced = p.practiced,
                    onLearnNew = ::backToIdle,
                    onExit = onExit,
                )
            }
        }
    }
}

private fun GeneratedGrammarLesson.toAskContext(): AskContext = AskContext(
    kind = AskContextKind.Grammar,
    title = if (labelZh.isNotBlank()) "$patternEn · $labelZh" else patternEn,
    details = buildList {
        add(AskDetail("结构", patternEn))
        if (labelZh.isNotBlank()) add(AskDetail("语法点", labelZh))
        if (summaryZh.isNotBlank()) add(AskDetail("用途", summaryZh))
        if (explanationZh.isNotBlank()) add(AskDetail("讲解要点", explanationZh))
        if (goodExampleEn.isNotBlank()) add(AskDetail("正确例句", goodExampleEn))
        if (badExampleEn.isNotBlank()) add(AskDetail("易错例句", badExampleEn))
        if (tipZh.isNotBlank()) add(AskDetail("易混提醒", tipZh))
    },
    suggestions = listOf("和哪个结构最容易混？", "口语里常这么说吗？", "再给我两个例句"),
)

/** 还没作答时不把正确答案交出去，免得摇一摇变成看答案。 */
private fun DrillSession.toAskContext(): AskContext {
    val item = current
    return AskContext(
        kind = AskContextKind.Question,
        title = if (answered) "这道题 · 你选了 ${item.options[selected!!]}" else "这道填空题",
        details = buildList {
            add(AskDetail("语法点", pending.request.patternEn))
            add(AskDetail("题干", item.sentenceEn))
            add(AskDetail("选项", item.options.joinToString(" / ")))
            if (answered) {
                add(AskDetail("你选了", item.options[selected!!]))
                add(AskDetail("正确答案", item.answer))
                add(AskDetail("解析", item.explanationZh))
            } else {
                add(AskDetail("状态", "学习者还没选，别直接说出正确答案，只能给思路"))
            }
        },
        suggestions = if (answered) {
            listOf("我选的为什么不对？", "这两个形式平时怎么区分？", "再给我一句类似的")
        } else {
            listOf("这句该看哪个线索？", "这几个形式分别什么时候用？")
        },
    )
}

/** 最近错得最多的形式：让用户看见"接下来讲的东西是从我的错里来的"。 */
@Composable
private fun WeakSpotCard(weakSpots: List<MistakeSummary>) {
    if (weakSpots.isEmpty()) return
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "你最近老错这些",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            weakSpots.forEach { spot ->
                Text(
                    text = "${spot.labelZh} · 错过 ${spot.count} 次" +
                        if (spot.patterns.isNotEmpty()) "（${spot.patterns.first()}）" else "",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(
                text = "留空让 AI 挑时，会优先挑能治这些错的语法点。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DueOfferView(
    due: List<GrammarRecord>,
    onPractice: () -> Unit,
    onLearnNew: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("有 ${due.size} 条语法到期了", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "复习不是再读一遍讲解，是直接做几道填空题，做对做错程序说了算。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        due.forEach { record ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    InteractiveEnglishText(
                        text = record.detail.displayPattern(),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = record.detail.displaySummary(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Button(onClick = onPractice, modifier = Modifier.fillMaxWidth()) { Text("练这几条") }
        TextButton(onClick = onLearnNew, modifier = Modifier.fillMaxWidth()) { Text("跳过，学个新的") }
    }
}

@Composable
private fun DrillView(
    session: DrillSession,
    onSelect: (Int) -> Unit,
    onNext: () -> Unit,
) {
    val item = session.current
    val extended = LazyDogTheme.extendedColors

    Column(
        modifier = Modifier.padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = session.pending.request.patternEn,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = if (session.answered) {
                item.filledWith(item.options[session.selected!!])
            } else {
                item.sentenceEn
            },
            style = MaterialTheme.typography.headlineSmall,
        )
        item.options.forEachIndexed { index, option ->
            val isAnswer = index == item.answerIndex
            val isSelected = index == session.selected
            val container = when {
                !session.answered -> MaterialTheme.colorScheme.surfaceContainer
                isAnswer -> extended.correctContainer
                isSelected -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.surfaceContainer
            }
            Surface(
                onClick = { onSelect(index) },
                enabled = !session.answered,
                color = container,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = option,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    if (session.answered && isAnswer) {
                        Icon(
                            imageVector = Icons.Outlined.CheckCircle,
                            contentDescription = "正确答案",
                            tint = extended.correct,
                            modifier = Modifier.size(20.dp),
                        )
                    } else if (session.answered && isSelected) {
                        Icon(
                            imageVector = Icons.Outlined.Cancel,
                            contentDescription = "你选错了",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
        if (session.answered) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = if (session.selected == item.answerIndex) "对了" else "不对，正确的是 ${item.answer}",
                        style = MaterialTheme.typography.titleSmall,
                        color = if (session.selected == item.answerIndex) extended.correct
                        else MaterialTheme.colorScheme.error,
                    )
                    Text(
                        text = item.explanationZh,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) {
                Text(if (session.isLast) "这组做完了" else "下一题")
            }
        } else {
            Text(
                text = "选一个填进空里。写不对形式很正常，选错了会告诉你为什么。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SummaryView(
    practiced: List<String>,
    onLearnNew: () -> Unit,
    onExit: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(top = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Outlined.TaskAlt,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Text("这轮语法搞定", style = MaterialTheme.typography.headlineSmall)
        practiced.forEach { line ->
            Text(
                text = line,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        Text(
            text = "答对率已经算进复习计划，错得多的会更早再来找你。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onExit, modifier = Modifier.fillMaxWidth()) { Text("收工") }
        TextButton(onClick = onLearnNew, modifier = Modifier.fillMaxWidth()) { Text("再学一个新的") }
    }
}

@Composable
private fun LessonView(
    lesson: GeneratedGrammarLesson,
    saving: Boolean,
    onSave: () -> Unit,
    onAnother: () -> Unit,
) {
    val context = LocalContext.current
    val app = remember { context.applicationContext as LazyDogApplication }
    val scope = rememberCoroutineScope()
    val extended = LazyDogTheme.extendedColors

    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
        InteractiveEnglishText(
            text = lesson.patternEn,
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = lesson.labelZh,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    Text(
        text = lesson.summaryZh,
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.Medium,
    )
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("怎么用", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text(
            text = lesson.explanationZh,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    ExampleBlock(
        icon = Icons.Outlined.CheckCircle,
        tint = extended.correct,
        label = "这样说",
        sentence = lesson.goodExampleEn,
        note = lesson.goodExampleZh,
        onSpeak = { scope.launch { app.speechController.speak(lesson.goodExampleEn) } },
    )
    if (lesson.badExampleEn.isNotBlank()) {
        ExampleBlock(
            icon = Icons.Outlined.Cancel,
            tint = MaterialTheme.colorScheme.error,
            label = "容易说错",
            sentence = lesson.badExampleEn,
            note = lesson.badExampleNoteZh,
            onSpeak = null,
        )
    }
    if (lesson.tipZh.isNotBlank()) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("易混提醒", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(
                    text = lesson.tipZh,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    Button(
        onClick = onSave,
        enabled = !saving,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(Icons.Outlined.LibraryAdd, contentDescription = null)
        Text(
            text = if (saving) "记下了，正在出题…" else "记进知识库，练几句",
            modifier = Modifier.padding(start = 8.dp),
        )
    }
    TextButton(onClick = onAnother, modifier = Modifier.fillMaxWidth()) {
        Text("换一个语法点")
    }
}

@Composable
private fun CenterBlock(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        content()
    }
}

@Composable
private fun ExampleBlock(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: androidx.compose.ui.graphics.Color,
    label: String,
    sentence: String,
    note: String,
    onSpeak: (() -> Unit)?,
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
                Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = tint,
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
            InteractiveEnglishText(text = sentence, style = MaterialTheme.typography.bodyLarge)
            if (note.isNotBlank()) {
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
