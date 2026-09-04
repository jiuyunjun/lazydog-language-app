package com.lazydog.english.feature.reading

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Casino
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lazydog.english.LazyDogApplication
import com.lazydog.english.core.speech.PlaybackSource
import com.lazydog.english.core.designsystem.SpeakButton
import com.lazydog.english.core.ask.ProvideAskContext
import com.lazydog.english.core.data.ReadingJson
import com.lazydog.english.core.data.ReadingRepository
import com.lazydog.english.core.data.displayPattern
import com.lazydog.english.core.designsystem.InteractiveEnglishText
import com.lazydog.english.core.designsystem.InteractiveTextHint
import com.lazydog.english.core.model.KnowledgeStage
import com.lazydog.english.core.model.TopicCatalog
import com.lazydog.english.domain.ask.AskContext
import com.lazydog.english.domain.ask.AskContextKind
import com.lazydog.english.domain.ask.AskDetail
import com.lazydog.english.feature.ask.AskTopBarAction
import com.lazydog.english.core.designsystem.AiWaiting
import com.lazydog.english.domain.generation.GenerationResult
import com.lazydog.english.domain.generation.GenerationStage
import com.lazydog.english.domain.generation.ReadingArchetype
import com.lazydog.english.domain.generation.ReadingGenerationRequest
import com.lazydog.english.domain.generation.ReadingQuestion
import com.lazydog.english.domain.generation.ReadingQuestionKind
import com.lazydog.english.domain.generation.ReadingTargetWord
import com.lazydog.english.domain.planning.DailyStep
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 打开方式：AI 生成 / 粘贴导入 / 打开已存材料。 */
sealed interface ReadingMode {
    data object Generate : ReadingMode
    data object Paste : ReadingMode
    data class Open(val materialId: Long) : ReadingMode
}

private data class MaterialView(
    val id: Long,
    val title: String,
    val body: String,
    /** 这篇要留给读者的那一个收获（§4）。粘贴导入的材料没有。 */
    val readerPayoff: String,
    val cefr: String,
    val source: String,
    val targetWords: List<ReadingTargetWord>,
    val questions: List<ReadingQuestion>,
)

private sealed interface ReadingPhase {
    data object Setup : ReadingPhase
    data object PasteInput : ReadingPhase
    data object Generating : ReadingPhase
    data object Loading : ReadingPhase
    data class Failed(val reason: String) : ReadingPhase
    data class Viewing(val material: MaterialView) : ReadingPhase
}

/**
 * 目标词数。§8 要求 250～700 词、5～9 段——160 词装不下
 * Hook → 好奇缺口 → 逐步揭示 → 兑现这一套，只够写成一段说明文。
 * 取 300 是因为 §7.1 的配比（300～500 词配 4～7 个新词）正好对上现有的新词上限。
 */
private const val TARGET_LENGTH = 300
private const val MAX_NEW_WORDS = 4

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingScreen(
    mode: ReadingMode,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val app = remember { context.applicationContext as LazyDogApplication }
    val scope = rememberCoroutineScope()
    val readingRepo = app.readingRepository

    var phase by remember {
        mutableStateOf<ReadingPhase>(
            when (mode) {
                ReadingMode.Generate -> ReadingPhase.Setup
                ReadingMode.Paste -> ReadingPhase.PasteInput
                is ReadingMode.Open -> ReadingPhase.Loading
            },
        )
    }
    var stage by remember { mutableStateOf<GenerationStage>(GenerationStage.Connecting) }
    /** 生成中已经写出来的正文，边写边铺。 */
    var preview by remember { mutableStateOf("") }

    LaunchedEffect(mode) {
        if (mode is ReadingMode.Open) {
            val entity = readingRepo.get(mode.materialId)
            phase = if (entity == null) {
                ReadingPhase.Failed("找不到这篇材料，可能已被删除")
            } else {
                ReadingPhase.Viewing(
                    MaterialView(
                        id = entity.id,
                        title = entity.title,
                        body = entity.body,
                        readerPayoff = entity.readerPayoff,
                        cefr = entity.estimatedCefr,
                        source = entity.source,
                        targetWords = ReadingJson.decodeWords(entity.targetWordsJson),
                        questions = ReadingJson.decodeQuestions(entity.questionsJson),
                    ),
                )
            }
        }
    }

    fun generate(topic: String) {
        phase = ReadingPhase.Generating
        stage = GenerationStage.Connecting
        preview = ""
        scope.launch {
            val now = System.currentTimeMillis()
            val vocab = app.knowledgeRepository.vocabulary.first()
            val due = vocab.filter { (it.item.nextReviewAt ?: Long.MAX_VALUE) <= now }
                .map { it.detail.term }
                .take(6)
            val known = vocab
                .filter { it.item.stage in setOf(KnowledgeStage.Familiar.name, KnowledgeStage.Mastered.name) }
                .map { it.detail.term }
                .take(30)
            val dueGrammar = app.knowledgeRepository.grammar.first()
                .filter { (it.item.nextReviewAt ?: Long.MAX_VALUE) <= now }
                .map { it.detail.displayPattern() }
                .take(2)

            // 写法轮换 + 最近读过的标题一起进提示词：主题去重挡不住"十篇一个样"，
            // 真正让人腻的是结构和句式（`引人入胜的阅读材料DESIGN.md` §20）。
            val recent = readingRepo.recentShape()
            val archetype = ReadingArchetype.pick(recent.archetypes)
            val request = ReadingGenerationRequest(
                learnerLevel = app.userPreferences.readingLevelDescription.first(),
                topic = topic,
                targetLength = TARGET_LENGTH,
                reviewVocabulary = due,
                knownVocabulary = known,
                reviewGrammar = dueGrammar,
                maxNewWords = MAX_NEW_WORDS,
                archetype = archetype,
                recentTitles = recent.titles,
            )
            val generated = app.contentGenerator.generateReading(
                request,
                onStage = { stage = it },
                // 正文一到就铺出来：这篇文章本来就是给他读的，早读一分钟没有坏处。
                onPartialText = { preview = it },
            )
            when (val result = generated) {
                is GenerationResult.Failure -> phase = ReadingPhase.Failed(result.reason)
                is GenerationResult.Success -> {
                    val id = readingRepo.saveGenerated(
                        reading = result.data,
                        topic = topic,
                        archetype = archetype.wire,
                        model = result.model,
                        promptVersion = result.promptVersion,
                        schemaVersion = 1,
                        validationNotes = result.droppedNotes,
                    )
                    phase = ReadingPhase.Viewing(
                        MaterialView(
                            id = id,
                            title = result.data.title,
                            body = result.data.body,
                            readerPayoff = result.data.readerPayoff,
                            cefr = result.data.estimatedCefr,
                            source = ReadingRepository.SOURCE_AI,
                            targetWords = result.data.targetVocabulary,
                            questions = result.data.comprehensionQuestions,
                        ),
                    )
                }
            }
        }
    }

    fun savePasted(title: String, body: String) {
        scope.launch {
            val finalTitle = title.trim().ifBlank {
                body.trim().split(Regex("\\s+")).take(6).joinToString(" ")
            }
            val id = readingRepo.savePasted(finalTitle, body)
            phase = ReadingPhase.Viewing(
                MaterialView(
                    id = id,
                    title = finalTitle,
                    body = body.trim(),
                    // 粘贴进来的材料没有收获陈述：那是生成时声明的，不该由本地编一个。
                    readerPayoff = "",
                    cefr = "",
                    source = ReadingRepository.SOURCE_PASTED,
                    targetWords = emptyList(),
                    questions = emptyList(),
                ),
            )
        }
    }

    // 默认拿整篇材料当上下文；答过题之后换成最近这道题（含你选了什么）。
    var askQuestionContext by remember { mutableStateOf<AskContext?>(null) }
    val material = (phase as? ReadingPhase.Viewing)?.material
    ProvideAskContext(material?.let { askQuestionContext ?: it.toAskContext() })

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("阅读") },
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
            when (val p = phase) {
                ReadingPhase.Setup -> SetupView(onGenerate = ::generate)
                ReadingPhase.PasteInput -> PasteView(onSave = ::savePasted)
                ReadingPhase.Generating ->
                    AiWaiting("AI 正在写文章，会把到期复习词编进去…", stage, preview = preview)
                ReadingPhase.Loading -> CenterColumn { CircularProgressIndicator() }
                is ReadingPhase.Failed -> CenterColumn {
                    Text(
                        text = p.reason,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                    if (mode == ReadingMode.Generate) {
                        Button(onClick = { phase = ReadingPhase.Setup }) { Text("回去重试") }
                    }
                    TextButton(onClick = onExit) { Text("退出") }
                }
                is ReadingPhase.Viewing -> MaterialContent(
                    material = p.material,
                    onQuestionAnswered = { question, selectedIndex ->
                        askQuestionContext = question.toAskContext(selectedIndex)
                    },
                    onQuestionsCompleted = {
                        scope.launch {
                            // 读完并答完题：今日阅读步骤完成，复习词记一次“语境里遇见”。
                            app.userPreferences.markTodayStepDone(
                                LocalDate.now().toString(),
                                DailyStep.Reading.id,
                            )
                            val vocab = app.knowledgeRepository.vocabulary.first()
                            p.material.targetWords.filter { it.role == "review" }.forEach { target ->
                                vocab.firstOrNull { it.detail.term.equals(target.term, ignoreCase = true) }
                                    ?.let { app.knowledgeRepository.recordExposure(it.item.id, "reading") }
                            }
                        }
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SetupView(onGenerate: (String) -> Unit) {
    val context = LocalContext.current
    val app = remember { context.applicationContext as LazyDogApplication }
    val savedTopics by app.userPreferences.topics.collectAsState(initial = emptySet())
    var selectedTopic by rememberSaveable { mutableStateOf("") }
    var customTopic by rememberSaveable { mutableStateOf("") }
    // 勾过的兴趣排最前，后面接全集：今天想读什么和当初勾了什么兴趣不是一回事，
    // 只摆那四个勾过的等于每天在同一小撮词里挑。
    val topics = remember(savedTopics) { TopicCatalog.withPreferred(savedTopics) }

    fun pick(topic: String) {
        selectedTopic = topic
        customTopic = ""
    }

    // 标签多到要滚了，输入框和生成按钮就不能跟着滚：选完标签还得往下翻一屏才能开始，
    // 而且选的是哪个已经看不见了。滚动区只放标签，操作区钉在底下。
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
                text = "挑个主题，AI 会写一篇约 $TARGET_LENGTH 词的短文，把你到期的复习词编进去，再带最多 $MAX_NEW_WORDS 个新词。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 挑不出来的时候比挑得出来的时候多：给一颗骰子，省得对着几十个词发呆。
            AssistChip(
                onClick = { pick(TopicCatalog.random(exclude = selectedTopic)) },
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
                        onClick = { if (selectedTopic == topic) selectedTopic = "" else pick(topic) },
                        label = { Text(topic) },
                    )
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
                // 选了标签也在这儿回显一次：滚上去看不见了，按钮旁边得说清这次要生成什么。
                val chosen = customTopic.trim().ifBlank { selectedTopic }
                OutlinedTextField(
                    value = customTopic,
                    onValueChange = {
                        customTopic = it
                        if (it.isNotBlank()) selectedTopic = ""
                    },
                    label = { Text("或者自己写一个主题") },
                    placeholder = { Text(selectedTopic.ifBlank { "比如：一次搞砸的露营" }) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { onGenerate(chosen) },
                    enabled = chosen.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                ) {
                    Icon(Icons.Outlined.AutoAwesome, contentDescription = null)
                    Text(
                        text = if (chosen.isBlank()) "生成短文" else "生成「$chosen」的短文",
                        modifier = Modifier.padding(start = 8.dp),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun PasteView(onSave: (title: String, body: String) -> Unit) {
    var title by rememberSaveable { mutableStateOf("") }
    var body by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "粘贴一段英文。快速双击查词、快速三击讲句，生词可以顺手记进知识库。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text("标题（可不填）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = body,
            onValueChange = { body = it },
            label = { Text("正文") },
            minLines = 8,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { onSave(title, body) },
            enabled = body.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("开始读")
        }
    }
}

private fun MaterialView.toAskContext(): AskContext = AskContext(
    kind = AskContextKind.Reading,
    title = title,
    details = buildList {
        add(AskDetail("标题", title))
        if (cefr.isNotBlank()) add(AskDetail("难度", cefr))
        // 正文按段落截断：只发学习者真的在读的这一篇，不发整个知识库。
        add(AskDetail("正文", body.take(1200)))
        if (targetWords.isNotEmpty()) {
            add(AskDetail("这篇的目标词", targetWords.joinToString("、") { "${it.term}（${it.meaningZh}）" }))
        }
    },
    suggestions = listOf("这段在讲什么？", "有哪句话结构比较难？", "挑两个值得记的表达"),
)

private fun ReadingQuestion.toAskContext(selectedIndex: Int): AskContext = AskContext(
    kind = AskContextKind.Question,
    title = "这道题 · 你选了${optionAt(selectedIndex)}",
    details = buildList {
        add(AskDetail("题干", promptZh))
        add(AskDetail("选项", options.joinToString(" / ")))
        add(AskDetail("你选了", optionAt(selectedIndex)))
        add(AskDetail("正确答案", optionAt(answerIndex)))
        add(AskDetail("题型", ReadingQuestionKind.labelZh(kind)))
        if (evidenceFromText.isNotBlank()) add(AskDetail("原文依据", evidenceFromText))
        if (explanationZh.isNotBlank()) add(AskDetail("解析", explanationZh))
    },
    suggestions = listOf("我选的为什么不对？", "怎么在原文里找到依据？", "这类题该看什么线索？"),
)

private fun ReadingQuestion.optionAt(index: Int): String = options.getOrNull(index) ?: "（没选）"

@Composable
private fun MaterialContent(
    material: MaterialView,
    onQuestionAnswered: (ReadingQuestion, Int) -> Unit,
    onQuestionsCompleted: () -> Unit,
) {
    val context = LocalContext.current
    val app = remember { context.applicationContext as LazyDogApplication }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(material.title, style = MaterialTheme.typography.headlineSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (material.cefr.isNotBlank()) Tag("难度 ${material.cefr}")
            Tag(if (material.source == ReadingRepository.SOURCE_AI) "AI 定制" else "粘贴导入")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SpeakButton(PlaybackSource.sentence(material.body), contentDescription = "朗读全文")
            InteractiveTextHint(modifier = Modifier.padding(top = 12.dp))
        }
        InteractiveEnglishText(
            text = material.body,
            highlightWords = material.targetWords.map { it.term }.toSet(),
            style = MaterialTheme.typography.bodyLarge.copy(lineHeight = MaterialTheme.typography.bodyLarge.lineHeight * 1.3),
        )

        if (material.targetWords.isNotEmpty()) {
            Text("这篇的目标词 · 点开看讲解", style = MaterialTheme.typography.titleMedium)
            material.targetWords.forEach { target ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                InteractiveEnglishText(text = target.term, style = MaterialTheme.typography.titleSmall)
                                Tag(if (target.role == "new") "新词" else "复习")
                            }
                            InteractiveEnglishText(
                                text = target.meaningZh,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (target.exampleFromText.isNotBlank()) {
                                InteractiveEnglishText(
                                    text = target.exampleFromText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                        }
                        SpeakButton(
                            source = PlaybackSource.word(target.term),
                            contentDescription = "朗读 ${target.term}",
                        )
                    }
                }
            }
        }

        if (material.questions.isNotEmpty()) {
            Text("读懂了吗", style = MaterialTheme.typography.titleMedium)
            QuestionList(
                questions = material.questions,
                onAnswered = onQuestionAnswered,
                onAllAnswered = onQuestionsCompleted,
            )
        }

        if (material.readerPayoff.isNotBlank()) {
            ReaderPayoffCard(material.readerPayoff)
        }
    }
}

/**
 * 「值得记住的一件事」（`引人入胜的阅读材料DESIGN.md` §4）。
 *
 * 它回答的是"你刚刚到底学到了什么"，所以只有一句，而且必须是文章真正兑现过的那一句——
 * 写成"本文总结"就白做了：总结是把读过的重说一遍，收获是读之前不知道、读完带得走的东西。
 */
@Composable
private fun ReaderPayoffCard(payoff: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "值得记住的一件事",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            InteractiveEnglishText(
                text = payoff,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun QuestionList(
    questions: List<ReadingQuestion>,
    onAnswered: (ReadingQuestion, Int) -> Unit,
    onAllAnswered: () -> Unit,
) {
    val answers = remember { mutableStateMapOf<Int, Int>() }
    val allAnswered = answers.size == questions.size && questions.isNotEmpty()
    LaunchedEffect(allAnswered) {
        if (allAnswered) onAllAnswered()
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        questions.forEachIndexed { qIndex, question ->
            val selected = answers[qIndex]
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = ReadingQuestionKind.labelZh(question.kind),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                InteractiveEnglishText(
                    text = "${qIndex + 1}. ${question.promptZh}",
                    style = MaterialTheme.typography.bodyLarge,
                )
                question.options.forEachIndexed { oIndex, option ->
                    val answered = selected != null
                    val isAnswer = oIndex == question.answerIndex
                    val isSelected = oIndex == selected
                    val container = when {
                        answered && isAnswer -> MaterialTheme.colorScheme.primaryContainer
                        answered && isSelected -> MaterialTheme.colorScheme.errorContainer
                        else -> MaterialTheme.colorScheme.surfaceContainer
                    }
                    fun choose() {
                        if (answered) return
                        answers[qIndex] = oIndex
                        onAnswered(question, oIndex)
                    }
                    Surface(
                        onClick = ::choose,
                        enabled = !answered,
                        color = container,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        InteractiveEnglishText(
                            text = option,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(12.dp),
                            onSingleTap = ::choose,
                        )
                    }
                }
                if (selected != null && question.explanationZh.isNotBlank()) {
                    InteractiveEnglishText(
                        text = (if (selected == question.answerIndex) "对了。" else "不对。") + question.explanationZh,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // 形式题和指代题答完把原文那句摆出来，逼一次"回原文核对"。
                if (selected != null && question.evidenceFromText.isNotBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = "原文这句",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            InteractiveEnglishText(
                                text = question.evidenceFromText,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
        if (answers.size == questions.size && questions.isNotEmpty()) {
            val correct = questions.indices.count { answers[it] == questions[it].answerIndex }
            Text(
                text = "答对 $correct / ${questions.size}。读完了，今天的阅读算数。",
                style = MaterialTheme.typography.titleSmall,
            )
        }
    }
}

@Composable
private fun Tag(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun CenterColumn(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, androidx.compose.ui.Alignment.CenterVertically),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
    ) {
        content()
    }
}
