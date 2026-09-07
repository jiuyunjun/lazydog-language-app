package com.lazydog.english.feature.reading

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Casino
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.lazydog.english.LazyDogApplication
import com.lazydog.english.core.ask.ProvideAskContext
import com.lazydog.english.core.data.NativeReadingJson
import com.lazydog.english.core.data.ReadingRepository
import com.lazydog.english.core.data.VocabularyImageRepository
import com.lazydog.english.domain.vocabulary.SenseKey
import com.lazydog.english.feature.vocabulary.CachedSenseImage
import com.lazydog.english.core.designsystem.AiWaiting
import com.lazydog.english.core.designsystem.InteractiveEnglishText
import com.lazydog.english.core.designsystem.SpeakButton
import com.lazydog.english.core.model.KnowledgeStage
import com.lazydog.english.core.model.TopicCatalog
import com.lazydog.english.core.speech.PlaybackSource
import com.lazydog.english.domain.ask.AskContext
import com.lazydog.english.domain.ask.AskContextKind
import com.lazydog.english.domain.ask.AskDetail
import com.lazydog.english.domain.generation.CanonicalParagraph
import com.lazydog.english.domain.generation.EnglishAmount
import com.lazydog.english.domain.generation.GenerationResult
import com.lazydog.english.domain.generation.GenerationStage
import com.lazydog.english.domain.generation.LearningSpan
import com.lazydog.english.domain.generation.MasteryClass
import com.lazydog.english.domain.generation.NativeCanonicalArticle
import com.lazydog.english.domain.generation.NativeCanonicalRequest
import com.lazydog.english.domain.generation.NativeComprehensionQuestion
import com.lazydog.english.domain.generation.NativeReadingDocument
import com.lazydog.english.domain.generation.NativeReadingValidation
import com.lazydog.english.domain.generation.NativeSpanPlanRequest
import com.lazydog.english.domain.generation.NewWordAmount
import com.lazydog.english.domain.generation.ReadingSegment
import com.lazydog.english.domain.generation.SpanKind
import com.lazydog.english.domain.generation.WebSearchProvider
import com.lazydog.english.domain.generation.factPackLines
import com.lazydog.english.domain.planning.DailyStep
import com.lazydog.english.feature.ask.AskTopBarAction
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 母语阅读（`母语阅读DESIGN.md`）。
 *
 * 和 [ReadingScreen] 并列而不是合并：那一屏读的是英文短文，这一屏读的是中文母版加局部英语，
 * 手势、状态和结束流程都不一样。共用的是数据层（`reading_materials`）和朗读、提问这些能力。
 *
 * 这一版实现的是设计文档 §39 的 MVP v1：三档英语量、词/短语/最多一个语法 span、
 * 点开面板、长按看原文、段落救援、读完一道理解题加几个表达回忆。
 * 段内实时动态难度（§14）和热点 Fact Pack 的多轮检索（§6.2）留到后面。
 */
sealed interface NativeReadingMode {
    data object New : NativeReadingMode
    data class Open(val materialId: Long) : NativeReadingMode
}

private data class NativeView(
    val id: Long,
    val topic: String,
    val article: NativeCanonicalArticle,
    val document: NativeReadingDocument,
) {
    val spans: Map<String, LearningSpan> = document.spans.associateBy { it.id }
    val rendered = NativeReadingValidation.render(document.paragraphs, document.spans)
    val amount: EnglishAmount get() = EnglishAmount.fromWire(document.englishMode)
}

private sealed interface NativePhase {
    data object Setup : NativePhase
    data object Loading : NativePhase
    data class Working(val label: String) : NativePhase
    data class Failed(val reason: String) : NativePhase
    data class Reading(val view: NativeView) : NativePhase
    data class WrapUp(val view: NativeView) : NativePhase
}

/** 连着点开这么多次释义，就该问一句是不是太难了（设计文档 §13.3）。 */
private const val RESCUE_THRESHOLD = 3

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NativeReadingScreen(
    mode: NativeReadingMode,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val app = remember { context.applicationContext as LazyDogApplication }
    val scope = rememberCoroutineScope()
    val repo = app.readingRepository

    var phase by remember {
        mutableStateOf<NativePhase>(
            if (mode is NativeReadingMode.Open) NativePhase.Loading else NativePhase.Setup,
        )
    }
    var stage by remember { mutableStateOf<GenerationStage>(GenerationStage.Connecting) }
    var preview by remember { mutableStateOf("") }
    /** 本篇里被长按翻回中文的片段。不落库：它是"这一下我没看懂"，不是设置。 */
    val revealed = remember { mutableStateListOf<String>() }
    var openSpan by remember { mutableStateOf<LearningSpan?>(null) }
    var revealCount by remember { mutableStateOf(0) }
    var rescueDismissed by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }

    suspend fun learnerState(): Triple<String, List<String>, List<String>> {
        val now = System.currentTimeMillis()
        val vocab = app.knowledgeRepository.vocabulary.first()
        val due = vocab.filter { (it.item.nextReviewAt ?: Long.MAX_VALUE) <= now }
            .map { it.detail.term }
            .take(10)
        val known = vocab
            .filter { it.item.stage in setOf(KnowledgeStage.Familiar.name, KnowledgeStage.Mastered.name) }
            .map { it.detail.term }
            .take(40)
        return Triple(app.userPreferences.readingLevelDescription.first(), due, known)
    }

    /** 只重跑替换方案，文章一个字不动（§38）。换档位和「这篇先轻松点」共用这条路。 */
    fun replan(view: NativeView, amount: EnglishAmount) {
        phase = NativePhase.Working("正在按「${amount.labelZh}」重排英语")
        stage = GenerationStage.Connecting
        scope.launch {
            val (level, due, known) = learnerState()
            val newWords = NewWordAmount.fromWire(app.userPreferences.nativeNewWordAmount.first())
            val request = NativeSpanPlanRequest(
                learnerLevel = level,
                paragraphs = view.document.paragraphs,
                englishAmount = amount,
                newWordAmount = newWords,
                allowGrammar = amount != EnglishAmount.Light,
                reviewVocabulary = due,
                knownVocabulary = known,
            )
            when (val planned = app.contentGenerator.planNativeReadingSpans(request, onStage = { stage = it })) {
                is GenerationResult.Failure -> {
                    // 换档失败不该把已经能读的这一篇弄丢：留在原来的档位，说一声就行。
                    notice = "换档没成功：${planned.reason}"
                    phase = NativePhase.Reading(view)
                }
                is GenerationResult.Success -> {
                    val document = view.document.copy(
                        englishMode = amount.wire,
                        newWordMode = newWords.wire,
                        spans = planned.data,
                        englishSurfaceActual = surfaceRatio(view.document.paragraphs, planned.data),
                        notes = planned.droppedNotes,
                    )
                    repo.updateNativeDocument(view.id, document)
                    revealed.clear()
                    revealCount = 0
                    rescueDismissed = false
                    phase = NativePhase.Reading(view.copy(document = document))
                }
            }
        }
    }

    fun generate(topic: String, amount: EnglishAmount, searchFirst: Boolean) {
        phase = NativePhase.Working("正在写这篇中文")
        stage = GenerationStage.Connecting
        preview = ""
        scope.launch {
            val (level, due, known) = learnerState()
            val newWords = NewWordAmount.fromWire(app.userPreferences.nativeNewWordAmount.first())
            app.userPreferences.saveNativeEnglishAmount(amount.wire)

            // 热点先检索再写（§36.7）。搜不到就照常写，只是不带时效事实——
            // 因为搜索失败读不到文章，对用户来说是纯粹的倒退。
            val facts = if (searchFirst) {
                // 用户点的是「先搜一下最新消息」，那就真的收窗口到最近一个月；
                // 不点这个开关的主题走的是常识路线，根本不检索。
                val result = app.webSearch.search(
                    query = topic,
                    freshness = WebSearchProvider.FRESH_MONTH,
                )
                if (result.failure != null) notice = "没搜到最新消息，这篇按常识写：${result.failure}"
                factPackLines(result.hits)
            } else {
                emptyList()
            }

            val recent = repo.recentShape()
            val canonical = app.contentGenerator.generateNativeCanonical(
                NativeCanonicalRequest(
                    topic = topic,
                    learnerLevel = level,
                    factPack = facts,
                    recentTitles = recent.titles,
                ),
                onStage = { stage = it },
                onPartialText = { preview = it },
            )
            val article = when (canonical) {
                is GenerationResult.Failure -> {
                    phase = NativePhase.Failed(canonical.reason)
                    return@launch
                }
                is GenerationResult.Success -> canonical.data
            }

            phase = NativePhase.Working("正在挑哪些地方换成英语")
            stage = GenerationStage.Connecting
            preview = ""
            val planned = app.contentGenerator.planNativeReadingSpans(
                NativeSpanPlanRequest(
                    learnerLevel = level,
                    paragraphs = article.paragraphs,
                    englishAmount = amount,
                    newWordAmount = newWords,
                    allowGrammar = amount != EnglishAmount.Light,
                    reviewVocabulary = due,
                    knownVocabulary = known,
                ),
                onStage = { stage = it },
            )
            val spans = when (planned) {
                // 英语部分挂了不代表这篇没用：中文母版是完整的，让他先读中文。
                is GenerationResult.Failure -> {
                    notice = "英语部分没生成成功，先读中文版：${planned.reason}"
                    emptyList()
                }
                is GenerationResult.Success -> planned.data
            }
            val document = NativeReadingDocument(
                englishMode = amount.wire,
                newWordMode = newWords.wire,
                paragraphs = article.paragraphs,
                spans = spans,
                comprehension = article.comprehension,
                englishSurfaceActual = surfaceRatio(article.paragraphs, spans),
                notes = canonical.droppedNotes +
                    (planned as? GenerationResult.Success)?.droppedNotes.orEmpty(),
            )
            val id = repo.saveNativeReading(
                article = article,
                document = document,
                topic = topic,
                learnerLevel = level,
                model = canonical.model,
                promptVersion = canonical.promptVersion,
                validationNotes = document.notes,
            )
            phase = NativePhase.Reading(NativeView(id, topic, article, document))
        }
    }

    LaunchedEffect(mode) {
        if (mode is NativeReadingMode.Open) {
            val entity = repo.get(mode.materialId)
            val document = entity?.let { NativeReadingJson.decode(it.nativeJson) }
            phase = when {
                entity == null -> NativePhase.Failed("找不到这篇材料，可能已被删除")
                document == null -> NativePhase.Failed("这篇不是母语阅读材料")
                else -> NativePhase.Reading(
                    NativeView(
                        id = entity.id,
                        topic = entity.topic,
                        article = NativeCanonicalArticle(
                            title = entity.title,
                            teaser = entity.teaser,
                            category = entity.category,
                            readerPayoff = entity.readerPayoff,
                            paragraphs = document.paragraphs,
                            comprehension = document.comprehension,
                        ),
                        document = document,
                    ),
                )
            }
        }
    }

    val current = (phase as? NativePhase.Reading)?.view ?: (phase as? NativePhase.WrapUp)?.view
    ProvideAskContext(current?.toAskContext())

    // 文章有了就把本篇目标词的图预取掉（`单词视觉记忆图片DESIGN.md` §49）。
    // 只处理这一篇的新目标词：给文章里所有熟词都预取一遍，是拿配额换没人会看的图。
    // 点开 span 那一刻不该再发请求，那时候只读缓存。
    LaunchedEffect(current?.id) {
        val view = current ?: return@LaunchedEffect
        app.vocabularyImageRepository.prefetch(
            view.document.spans
                .filter { it.isTarget && !it.isGrammar && it.meaningZh.isNotBlank() }
                .distinctBy { it.renderedEn.lowercase() }
                .map { span ->
                    VocabularyImageRepository.PrefetchTarget(
                        senseKey = SenseKey.ofDraft(span.renderedEn, "", span.meaningZh),
                        term = span.renderedEn,
                        meaningZh = span.meaningZh,
                    )
                },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("母语阅读") },
                navigationIcon = {
                    IconButton(onClick = onExit) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
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
            if (notice.isNotBlank()) {
                NoticeBar(notice) { notice = "" }
            }
            when (val p = phase) {
                NativePhase.Setup -> NativeSetupView(onGenerate = ::generate)
                NativePhase.Loading -> CenterColumn { CircularProgressIndicator() }
                is NativePhase.Working -> AiWaiting(p.label, stage, preview = preview)
                is NativePhase.Failed -> CenterColumn {
                    Text(
                        text = p.reason,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                    if (mode is NativeReadingMode.New) {
                        Button(onClick = { phase = NativePhase.Setup }) { Text("回去重试") }
                    }
                    TextButton(onClick = onExit) { Text("退出") }
                }
                is NativePhase.Reading -> NativeArticleView(
                    view = p.view,
                    revealed = revealed,
                    showRescue = revealCount >= RESCUE_THRESHOLD &&
                        !rescueDismissed &&
                        p.view.amount != EnglishAmount.Light,
                    onSpanTap = { span ->
                        openSpan = span
                        revealCount++
                    },
                    onSpanLongPress = { span ->
                        if (revealed.contains(span.id)) {
                            revealed.remove(span.id)
                        } else {
                            revealed.add(span.id)
                            revealCount++
                        }
                    },
                    onAmountChange = { replan(p.view, it) },
                    onRescueAccept = {
                        rescueDismissed = true
                        replan(p.view, EnglishAmount.Light)
                    },
                    onRescueDismiss = { rescueDismissed = true },
                    onFinish = { phase = NativePhase.WrapUp(p.view) },
                )
                is NativePhase.WrapUp -> NativeWrapUpView(
                    view = p.view,
                    onDone = {
                        scope.launch {
                            repo.markCompleted(p.view.id)
                            app.userPreferences.markTodayStepDone(
                                LocalDate.now().toString(),
                                DailyStep.Reading.id,
                            )
                            // 复习类 span 记一次「在语境里遇见」；新表达要用户明说才入库。
                            val vocab = app.knowledgeRepository.vocabulary.first()
                            p.view.document.spans
                                .filter { MasteryClass.normalize(it.masteryClass) == MasteryClass.Review }
                                .forEach { span ->
                                    vocab.firstOrNull { it.detail.term.equals(span.renderedEn, true) }
                                        ?.let { app.knowledgeRepository.recordExposure(it.item.id, "native_reading") }
                                }
                            onExit()
                        }
                    },
                    onFeedback = { feedback ->
                        scope.launch {
                            val next = when (feedback) {
                                Feedback.TooEasy -> harder(p.view.amount)
                                Feedback.TooHard -> easier(p.view.amount)
                                Feedback.Good -> p.view.amount
                            }
                            app.userPreferences.saveNativeEnglishAmount(next.wire)
                            notice = if (next == p.view.amount) {
                                "记下了，下一篇还按「${next.labelZh}」来"
                            } else {
                                "记下了，下一篇按「${next.labelZh}」来"
                            }
                        }
                    },
                )
            }
        }
    }

    openSpan?.let { span ->
        SpanSheet(
            span = span,
            onDismiss = { openSpan = null },
            onAdd = {
                scope.launch {
                    val added = if (SpanKind.normalize(span.kind) == SpanKind.Vocabulary) {
                        app.knowledgeRepository.addVocabulary(
                            term = span.renderedEn,
                            meaningZh = span.meaningZh,
                            ipa = span.pronunciation,
                            exampleZh = span.sourceZh,
                        )?.also { id ->
                            // 读文章时已经为这个词义预取过图（§49），挂在草稿键上。
                            // 加进复习后搬到 itemId 上，词卡直接就有图（D-076）。
                            app.vocabularyImageRepository.adoptDraft(
                                SenseKey.ofDraft(span.renderedEn, "", span.meaningZh),
                                id,
                            )
                        }
                    } else {
                        app.knowledgeRepository.addExpression(span.renderedEn, span.meaningZh)
                    }
                    notice = if (added == null) "这个已经在你的记录里了" else "加进复习了"
                    openSpan = null
                }
            },
        )
    }
}

private enum class Feedback { TooEasy, Good, TooHard }

private fun harder(amount: EnglishAmount) = when (amount) {
    EnglishAmount.Light -> EnglishAmount.Balanced
    else -> EnglishAmount.Strong
}

private fun easier(amount: EnglishAmount) = when (amount) {
    EnglishAmount.Strong -> EnglishAmount.Balanced
    else -> EnglishAmount.Light
}

private fun surfaceRatio(paragraphs: List<CanonicalParagraph>, spans: List<LearningSpan>): Double {
    val total = paragraphs.sumOf { it.textZh.length }
    if (total == 0) return 0.0
    return spans.sumOf { it.sourceZh.length }.toDouble() / total
}

private fun NativeView.toAskContext(): AskContext = AskContext(
    kind = AskContextKind.Reading,
    title = article.title,
    details = listOf(
        AskDetail("阅读原文", article.bodyZh),
        AskDetail(
            "文中的英语",
            document.spans.joinToString("\n") { "${it.renderedEn} = ${it.meaningZh}" },
        ),
    ),
    suggestions = listOf("这篇里的英语哪个最值得记？", "这句英语为什么这么说？"),
)

@Composable
private fun NoticeBar(text: String, onDismiss: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) { Text("知道了") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NativeSetupView(onGenerate: (String, EnglishAmount, Boolean) -> Unit) {
    val context = LocalContext.current
    val app = remember { context.applicationContext as LazyDogApplication }
    val savedTopics by app.userPreferences.topics.collectAsState(initial = emptySet())
    val storedAmount by app.userPreferences.nativeEnglishAmount.collectAsState(initial = "")
    var braveReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { braveReady = app.webSearch.isConfigured() }

    var selectedTopic by rememberSaveable { mutableStateOf("") }
    var customTopic by rememberSaveable { mutableStateOf("") }
    var amountWire by rememberSaveable { mutableStateOf("") }
    var searchFirst by rememberSaveable { mutableStateOf(false) }
    val amount = EnglishAmount.fromWire(amountWire.ifBlank { storedAmount })
    val topics = remember(savedTopics) { TopicCatalog.withPreferred(savedTopics) }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "中文照读，英语一点点长出来：文章用中文写，其中一部分说法换成英语。" +
                    "读不动的地方长按就能看回中文。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AssistChip(
                onClick = {
                    selectedTopic = TopicCatalog.random(exclude = selectedTopic)
                    customTopic = ""
                },
                label = { Text("随便给我一个") },
                leadingIcon = { Icon(Icons.Outlined.Casino, contentDescription = null) },
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                topics.forEach { topic ->
                    FilterChip(
                        selected = selectedTopic == topic,
                        onClick = {
                            selectedTopic = if (selectedTopic == topic) "" else topic
                            if (selectedTopic.isNotBlank()) customTopic = ""
                        },
                        label = { Text(topic) },
                    )
                }
            }

            Text("英语量", style = MaterialTheme.typography.titleSmall)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                EnglishAmount.entries.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = amount == option,
                        onClick = { amountWire = option.wire },
                        shape = SegmentedButtonDefaults.itemShape(index, EnglishAmount.entries.size),
                    ) { Text(option.labelZh) }
                }
            }
            Text(
                text = "「${amount.labelZh}」大约把 ${amount.percent}% 的内容显示成英语。读着不顺随时能改，改的只是这一篇。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // 搜索是可选增强：没配密钥就不摆这个开关，免得点了才发现用不了。
            if (braveReady) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("先搜一下最新消息", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = "写时事类主题时打开：先检索再写，不让模型凭记忆编新闻。会慢几秒。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = searchFirst, onCheckedChange = { searchFirst = it })
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val chosen = customTopic.trim().ifBlank { selectedTopic }
                OutlinedTextField(
                    value = customTopic,
                    onValueChange = {
                        customTopic = it
                        if (it.isNotBlank()) selectedTopic = ""
                    },
                    label = { Text("想读点什么？中文说就行") },
                    placeholder = { Text(selectedTopic.ifBlank { "比如：为什么日本便利店很少缺货" }) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { onGenerate(chosen, amount, searchFirst) },
                    enabled = chosen.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                ) {
                    Icon(Icons.Outlined.AutoAwesome, contentDescription = null)
                    Text(
                        text = if (chosen.isBlank()) "给我写一篇" else "写一篇「$chosen」",
                        modifier = Modifier.padding(start = 8.dp),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun NativeArticleView(
    view: NativeView,
    revealed: List<String>,
    showRescue: Boolean,
    onSpanTap: (LearningSpan) -> Unit,
    onSpanLongPress: (LearningSpan) -> Unit,
    onAmountChange: (EnglishAmount) -> Unit,
    onRescueAccept: () -> Unit,
    onRescueDismiss: () -> Unit,
    onFinish: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            Text(view.article.title, style = MaterialTheme.typography.headlineSmall)
            Text(
                text = listOfNotNull(
                    view.article.category.ifBlank { null },
                    "英语约 ${(view.document.englishSurfaceActual * 100).toInt()}%",
                    "${view.document.spans.count { it.isTarget }} 个新表达",
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                EnglishAmount.entries.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = view.amount == option,
                        onClick = { if (view.amount != option) onAmountChange(option) },
                        shape = SegmentedButtonDefaults.itemShape(index, EnglishAmount.entries.size),
                    ) { Text(option.labelZh) }
                }
            }

            view.rendered.forEachIndexed { index, paragraph ->
                MixedParagraph(
                    paragraph = paragraph.segments,
                    spans = view.spans,
                    revealed = revealed,
                    onTap = onSpanTap,
                    onLongPress = onSpanLongPress,
                )
                // 救援卡插在正文里（不是弹窗）：它是这一段读不动时的一个选项，不是一次打断。
                if (showRescue && index == 1) {
                    RescueCard(onAccept = onRescueAccept, onDismiss = onRescueDismiss)
                }
            }

            if (view.article.readerPayoff.isNotBlank()) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "值得记住的一件事",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = view.article.readerPayoff,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }
            Text(
                text = "长按文中的英语看回原来的中文，再长按一下换回英语。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
        }
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
            Button(
                onClick = onFinish,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .height(56.dp),
            ) {
                Icon(Icons.Outlined.Check, contentDescription = null)
                Text("读完了", modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

/**
 * 一段中英混排。
 *
 * span 的视觉按 masteryClass 分（§20.1）：已掌握的不加任何标记，该复习的一条极淡的下划线，
 * 新表达才给明显一点的提示。把所有英语都画成高亮，等于告诉用户"这些都是任务"。
 */
@Composable
private fun MixedParagraph(
    paragraph: List<ReadingSegment>,
    spans: Map<String, LearningSpan>,
    revealed: List<String>,
    onTap: (LearningSpan) -> Unit,
    onLongPress: (LearningSpan) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val annotated = remember(paragraph, revealed.toList(), colors) {
        buildAnnotatedString {
            paragraph.forEach { segment ->
                when (segment) {
                    is ReadingSegment.Zh -> append(segment.text)
                    is ReadingSegment.Learning -> {
                        val span = spans[segment.spanId]
                        val isRevealed = revealed.contains(segment.spanId)
                        val text = if (isRevealed && span != null) span.sourceZh else segment.text
                        val mastery = MasteryClass.normalize(span?.masteryClass ?: MasteryClass.Target)
                        val style = when {
                            isRevealed -> SpanStyle(color = colors.onSurfaceVariant)
                            mastery == MasteryClass.Mastered || mastery == MasteryClass.Incidental ->
                                SpanStyle()
                            mastery == MasteryClass.Review ->
                                SpanStyle(textDecoration = TextDecoration.Underline, color = colors.onSurface)
                            else -> SpanStyle(
                                textDecoration = TextDecoration.Underline,
                                color = colors.onSurface,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                        pushStringAnnotation(SPAN_TAG, segment.spanId)
                        withStyle(style) { append(text) }
                        pop()
                    }
                }
            }
        }
    }

    Text(
        text = annotated,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(annotated) {
                detectTapGestures(
                    onTap = { position ->
                        val offset = layout?.getOffsetForPosition(position) ?: return@detectTapGestures
                        annotated.getStringAnnotations(SPAN_TAG, offset, offset)
                            .firstOrNull()
                            ?.let { spans[it.item] }
                            ?.let(onTap)
                    },
                    onLongPress = { position ->
                        val offset = layout?.getOffsetForPosition(position) ?: return@detectTapGestures
                        annotated.getStringAnnotations(SPAN_TAG, offset, offset)
                            .firstOrNull()
                            ?.let { spans[it.item] }
                            ?.let(onLongPress)
                    },
                )
            },
        onTextLayout = { layout = it },
    )
}

private const val SPAN_TAG = "native_span"

@Composable
private fun RescueCard(onAccept: () -> Unit, onDismiss: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("这一段英语有点密", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                text = "这篇先切回轻松模式？只改这一篇，设置不动。",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onAccept) { Text("这篇先轻松点") }
                TextButton(onClick = onDismiss) { Text("不用，继续读") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpanSheet(
    span: LearningSpan,
    onDismiss: () -> Unit,
    onAdd: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                InteractiveEnglishText(
                    text = span.renderedEn,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f),
                )
                SpeakButton(source = PlaybackSource.sentence(span.renderedEn))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (span.pronunciation.isNotBlank()) {
                    Text(
                        text = span.pronunciation,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = "${SpanKind.labelZh(span.kind)} · ${MasteryClass.labelZh(span.masteryClass)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(span.meaningZh, style = MaterialTheme.typography.titleMedium)
            // 只读缓存，这里绝不现搜（`单词视觉记忆图片DESIGN.md` §48）：
            // 点开等三秒会把阅读节奏毁掉。图是文章生成完那次预取顺手找好的。
            if (!span.isGrammar) {
                CachedSenseImage(SenseKey.ofDraft(span.renderedEn, "", span.meaningZh))
            }
            if (span.patternEn.isNotBlank()) {
                Text(
                    text = span.patternEn,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(12.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "原文这里说的是",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(span.sourceZh, style = MaterialTheme.typography.bodyMedium)
                    if (span.noteZh.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = span.noteZh,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            OutlinedButton(onClick = onAdd, modifier = Modifier.fillMaxWidth()) {
                Text("加进复习")
            }
        }
    }
}

/**
 * 读完之后（§21）：一道理解题 + 几个表达回忆 + 本文英语回顾。
 * 不做十道题——读完这一刻人是想接着读的，不是想考试的。
 */
@Composable
private fun NativeWrapUpView(
    view: NativeView,
    onDone: () -> Unit,
    onFeedback: (Feedback) -> Unit,
) {
    val question = view.document.comprehension
    var answered by remember { mutableStateOf(-1) }
    val recall = remember(view) { NativeReadingValidation.recallCandidates(view.document.spans) }
    val shown = remember { mutableStateListOf<String>() }
    var feedback by remember { mutableStateOf<Feedback?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            Text("这篇读完了", style = MaterialTheme.typography.headlineSmall)

            if (question != null) {
                QuestionBlock(question, answered) { answered = it }
            }

            if (recall.isNotEmpty()) {
                Text("再回忆几个说法", style = MaterialTheme.typography.titleMedium)
                recall.forEach { span ->
                    RecallCard(
                        span = span,
                        revealed = shown.contains(span.id),
                        onReveal = { if (!shown.contains(span.id)) shown.add(span.id) },
                    )
                }
            }

            Text("这篇的难度怎么样？", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FeedbackChip("太简单", feedback == Feedback.TooEasy) {
                    feedback = Feedback.TooEasy
                    onFeedback(Feedback.TooEasy)
                }
                FeedbackChip("正好", feedback == Feedback.Good) {
                    feedback = Feedback.Good
                    onFeedback(Feedback.Good)
                }
                FeedbackChip("有点难", feedback == Feedback.TooHard) {
                    feedback = Feedback.TooHard
                    onFeedback(Feedback.TooHard)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
            Button(
                onClick = onDone,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .height(56.dp),
            ) { Text("今天到这里") }
        }
    }
}

@Composable
private fun FeedbackChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}

@Composable
private fun QuestionBlock(
    question: NativeComprehensionQuestion,
    answered: Int,
    onAnswer: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(question.promptZh, style = MaterialTheme.typography.titleMedium)
        question.options.forEachIndexed { index, option ->
            val correct = index == question.answerIndex
            val container = when {
                answered < 0 -> MaterialTheme.colorScheme.surface
                correct -> MaterialTheme.colorScheme.secondaryContainer
                index == answered -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.surface
            }
            Surface(
                color = container,
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = answered < 0) { onAnswer(index) },
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(option, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    // 对错不只靠颜色：TalkBack 和色觉障碍都要看得懂。
                    if (answered >= 0 && correct) {
                        Text("正确答案", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        if (answered >= 0 && question.explanationZh.isNotBlank()) {
            Text(
                text = question.explanationZh,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 先给中文，让人自己想英语怎么说（§21.2）。不判分：这一步是回忆，不是考试。 */
@Composable
private fun RecallCard(span: LearningSpan, revealed: Boolean, onReveal: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(span.meaningZh, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            if (revealed) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    InteractiveEnglishText(
                        text = span.renderedEn,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    SpeakButton(source = PlaybackSource.sentence(span.renderedEn))
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "文章里是：${span.sourceZh}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                TextButton(onClick = onReveal) { Text("英语怎么说来着？") }
            }
        }
    }
}

@Composable
private fun CenterColumn(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) { content() }
}
