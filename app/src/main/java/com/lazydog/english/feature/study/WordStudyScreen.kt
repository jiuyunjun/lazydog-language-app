package com.lazydog.english.feature.study

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Lightbulb
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lazydog.english.LazyDogApplication
import com.lazydog.english.core.speech.PlaybackSource
import com.lazydog.english.core.designsystem.SpeakButton
import com.lazydog.english.core.data.KnowledgeRepository
import com.lazydog.english.core.ask.ProvideAskContext
import com.lazydog.english.core.data.VocabularyJson
import com.lazydog.english.core.data.spellingFacts
import com.lazydog.english.core.designsystem.InteractiveEnglishText
import com.lazydog.english.core.designsystem.InteractiveTextHint
import com.lazydog.english.domain.ask.AskContext
import com.lazydog.english.domain.ask.AskContextKind
import com.lazydog.english.domain.ask.AskDetail
import com.lazydog.english.feature.ask.AskTopBarAction
import com.lazydog.english.core.model.KnowledgeStage
import com.lazydog.english.core.model.ReviewGrade
import com.lazydog.english.core.designsystem.AiWaiting
import com.lazydog.english.domain.generation.Collocation
import com.lazydog.english.domain.generation.GeneratedWord
import com.lazydog.english.domain.generation.GenerationStage
import com.lazydog.english.domain.generation.GenerationResult
import com.lazydog.english.domain.generation.NewWordsRequest
import com.lazydog.english.domain.planning.DailyStep
import com.lazydog.english.domain.spelling.SpellingEngine
import com.lazydog.english.domain.spelling.SpellingFacts
import com.lazydog.english.domain.spelling.SpellingProgress
import com.lazydog.english.domain.spelling.SpellingStage
import com.lazydog.english.domain.vocabulary.posLabelZh
import com.lazydog.english.feature.spelling.SpellingCard
import com.lazydog.english.feature.vocabulary.CollocationChip
import com.lazydog.english.feature.vocabulary.MemoryHintPanel
import com.lazydog.english.feature.vocabulary.SpellingChunks
import com.lazydog.english.feature.spelling.SpellingCardView
import java.time.LocalDate
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 一张学习卡：复习卡带 itemId，新词卡带生成内容。 */
private data class StudyCard(
    val itemId: Long?,
    val term: String,
    val ipa: String,
    val meaningZh: String,
    val exampleEn: String,
    val exampleZh: String,
    val isNew: Boolean,
    val pos: String = "",
    val collocations: List<Collocation> = emptyList(),
    val stage: String = KnowledgeStage.Learning.name,
    val memoryHintZh: String = "",
    /** 例句里这个词实际出现的形态；空表示就是 term 本身。语境默写按它挖空。 */
    val seenAs: String = "",
    /** 不规则变形，随新词一起生成；入库后用于按词形查库。 */
    val forms: List<String> = emptyList(),
    /** 熟词产出卡按这个阶段出题；新词和生词用不到。 */
    val spelling: SpellingProgress? = null,
    val facts: SpellingFacts = SpellingFacts.None,
) {
    /**
     * 复习一个词就是考它的拼写：给中文，自己写出英文，程序判分。
     *
     * 不设"熟了才考"的门槛。原来卡在 Familiar 以上（stability ≥ 7，
     * 要连着答对四轮才够），结果实际上一次都没触发过，复习永远是看词显示意思。
     * 难度交给这个词自己的拼写阶段决定：刚学的词是四选一，不会一上来就让人默写。
     */
    val isSpellingCard: Boolean
        get() = !isNew &&
            // 整句和短语走不了字母级拼写训练，仍然用四档自评。
            term.isNotBlank() && term.none { it.isWhitespace() }

    /**
     * 没有拼写记录时按通用掌握阶段推一个起点，和仓储层的映射保持一致：
     * 通用阶段说明的是认不认得，所以只往低了猜。
     */
    private fun defaultSpellingStage(): SpellingStage = SpellingEngine.initialStageFor(
        KnowledgeStage.entries.firstOrNull { it.name == stage } ?: KnowledgeStage.Exposed,
    )

    /** 复习卡就是一张拼写卡。这里是"单词复习 = 拼写"这条的落点。 */
    fun toSpellingCard(): SpellingCard = SpellingCard(
        itemId = itemId!!,
        term = term,
        ipa = ipa,
        meaningZh = meaningZh,
        pos = pos,
        exampleEn = exampleEn,
        exampleZh = exampleZh,
        seenAs = seenAs,
        facts = facts,
        // 复习卡不出 S0 接触卡：这个词早在新词流里露过脸了，再"认个脸"是白走一趟，
        // 而且接触卡不判分，这一次复习就白复习了。最低从 S1 起考。
        progress = (spelling ?: SpellingProgress(stage = defaultSpellingStage())).let {
            if (it.stage == SpellingStage.Seen) it.copy(stage = SpellingStage.Recognition) else it
        },
        // 单词页只发到期的卡，所以这里一定推动复习时间。
        dueForReview = true,
    )
}

private sealed interface WordStudyPhase {
    data object Loading : WordStudyPhase
    data class Cards(
        val cards: List<StudyCard>,
        val index: Int,
        val revealed: Boolean,
    ) : WordStudyPhase
    data class OfferNew(val reviewedCount: Int) : WordStudyPhase
    data object Generating : WordStudyPhase
    data class GenerationFailed(val reason: String, val reviewedCount: Int) : WordStudyPhase
    data class Summary(val reviewedCount: Int, val newCount: Int) : WordStudyPhase
}


/** 到期复习到了这么多张，才值得提前把新词写好——复习几张就退出的话那一次生成就白花了。 */
private const val PREFETCH_AFTER_DUE_CARDS = 3

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WordStudyScreen(
    repository: KnowledgeRepository,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val app = remember { context.applicationContext as LazyDogApplication }
    val scope = rememberCoroutineScope()

    var phase by remember { mutableStateOf<WordStudyPhase>(WordStudyPhase.Loading) }
    var reviewedCount by remember { mutableStateOf(0) }
    var newLearnedCount by remember { mutableStateOf(0) }
    var stage by remember { mutableStateOf<GenerationStage>(GenerationStage.Connecting) }
    /** 生成中已经写出来的词，边写边铺。 */
    var preview by remember { mutableStateOf("") }
    val maxNewWords by app.userPreferences.maxNewWords.collectAsState(initial = 5)
    /**
     * 正在后台先生成的新词。复习到期卡片要花上一两分钟，而这段时间正好够把新词写完——
     * 等用户点"学新词"时内容已经在手上了，不用再对着进度条等一次。
     */
    var prefetch by remember { mutableStateOf<Deferred<GenerationResult<List<GeneratedWord>>>?>(null) }

    // 走到总结页就算完成了今日的单词步骤。
    LaunchedEffect(phase is WordStudyPhase.Summary) {
        if (phase is WordStudyPhase.Summary) {
            app.userPreferences.markTodayStepDone(LocalDate.now().toString(), DailyStep.Words.id)
        }
    }

    // 进来先取到期复习；没有就直接进入“要不要新词”。
    LaunchedEffect(Unit) {
        val now = System.currentTimeMillis()
        val spellingByItem = repository.spellingProgressByItem()
        val due = repository.vocabulary.first()
            .filter { (it.item.nextReviewAt ?: Long.MAX_VALUE) <= now }
            .take(20)
            .map {
                StudyCard(
                    itemId = it.item.id,
                    term = it.detail.term,
                    ipa = it.detail.ipa,
                    meaningZh = it.detail.meaningZh,
                    exampleEn = it.detail.exampleEn,
                    exampleZh = it.detail.exampleZh,
                    isNew = false,
                    pos = it.detail.pos,
                    collocations = VocabularyJson.decodeCollocations(it.detail.collocationsJson),
                    stage = it.item.stage,
                    memoryHintZh = it.detail.memoryHintZh,
                    seenAs = it.detail.seenAs,
                    spelling = spellingByItem[it.item.id],
                    facts = it.detail.spellingFacts(),
                )
            }
        phase = if (due.isEmpty()) WordStudyPhase.OfferNew(0) else WordStudyPhase.Cards(due, 0, revealed = false)
    }

    suspend fun requestNewWords(): GenerationResult<List<GeneratedWord>> {
        val prefs = app.userPreferences
        val known = repository.vocabulary.first().map { it.detail.term }.take(200)
        return app.contentGenerator.generateNewWords(
            NewWordsRequest(
                count = prefs.maxNewWords.first(),
                learnerLevel = prefs.vocabLevelDescription.first(),
                topics = prefs.topics.first().toList(),
                knownTerms = known,
            ),
            onStage = { stage = it },
            // 词一个个冒出来的时候，等待就不再是干等——他已经在看今天要学的东西了。
            onPartialText = { preview = it },
        )
    }

    fun generateNewWords() {
        phase = WordStudyPhase.Generating
        stage = GenerationStage.Connecting
        // 上一轮的词留在屏幕上会被当成这一轮的结果。
        if (prefetch == null) preview = ""
        scope.launch {
            // 已经在后台跑的那次直接等它，别再发一次重复的请求。
            val running = prefetch ?: scope.async { requestNewWords() }.also { prefetch = it }
            val result = running.await()
            prefetch = null
            phase = when (result) {
                is GenerationResult.Success -> WordStudyPhase.Cards(
                    cards = result.data.map { it.toCard() },
                    index = 0,
                    revealed = false,
                )
                is GenerationResult.Failure ->
                    WordStudyPhase.GenerationFailed(result.reason, reviewedCount)
            }
        }
    }

    fun onGrade(card: StudyCard, grade: ReviewGrade, cards: List<StudyCard>, index: Int) {
        scope.launch {
            if (card.isNew) {
                val id = repository.addVocabulary(
                    term = card.term,
                    meaningZh = card.meaningZh,
                    ipa = card.ipa,
                    exampleEn = card.exampleEn,
                    exampleZh = card.exampleZh,
                    pos = card.pos,
                    collocations = card.collocations,
                    memoryHintZh = card.memoryHintZh,
                    facts = card.facts,
                    forms = card.forms,
                )
                if (id != null) {
                    repository.recordReview(id, grade, source = "card")
                    newLearnedCount += 1
                }
            } else {
                repository.recordReview(card.itemId!!, grade, source = "card")
                reviewedCount += 1
            }
            phase = if (index + 1 < cards.size) {
                WordStudyPhase.Cards(cards, index + 1, revealed = false)
            } else if (!card.isNew) {
                WordStudyPhase.OfferNew(reviewedCount)
            } else {
                WordStudyPhase.Summary(reviewedCount, newLearnedCount)
            }
        }
    }

    /**
     * 复习卡片够多时，趁人还在复习就把新词先写好（"提前加载"）。
     *
     * 只在有一定量到期复习时才提前发：复习几张卡就走人的话，那一次生成就白花了。
     * 结果缓存在 [prefetch] 里，点"学新词"时直接取。
     */
    LaunchedEffect(phase is WordStudyPhase.Cards) {
        val cards = (phase as? WordStudyPhase.Cards)?.cards ?: return@LaunchedEffect
        if (cards.any { it.isNew }) return@LaunchedEffect
        if (cards.size < PREFETCH_AFTER_DUE_CARDS || prefetch != null) return@LaunchedEffect
        prefetch = scope.async { requestNewWords() }
    }

    // 只有正翻着卡片时才能提问；生成中、总结页摇了也不弹。
    ProvideAskContext((phase as? WordStudyPhase.Cards)?.let { it.cards[it.index].toAskContext(it.revealed) })

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("单词") },
                navigationIcon = {
                    IconButton(onClick = onExit) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    val p = phase
                    if (p is WordStudyPhase.Cards) {
                        Text(
                            text = "${if (p.cards.first().isNew) "新词" else "复习"} ${p.index + 1} / ${p.cards.size}",
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
                .padding(padding),
        ) {
            when (val p = phase) {
                WordStudyPhase.Loading -> CenterHint { CircularProgressIndicator() }
                WordStudyPhase.Generating -> AiWaiting("AI 正在挑词…", stage, preview = preview)
                is WordStudyPhase.Cards -> {
                    val card = p.cards[p.index]
                    if (card.isSpellingCard) {
                        // 复习就是拼写：出题、提示梯度、判分、写复习时间全在这张共用卡里，
                        // 所以这里不再调 onGrade——再调一次会把同一次复习记成两次。
                        SpellingCardView(
                            card = card.toSpellingCard(),
                            repository = repository,
                            onResolved = {
                                reviewedCount += 1
                                phase = if (p.index + 1 < p.cards.size) {
                                    WordStudyPhase.Cards(p.cards, p.index + 1, revealed = false)
                                } else {
                                    WordStudyPhase.OfferNew(reviewedCount)
                                }
                            },
                        )
                    } else {
                        StudyCardView(
                            card = card,
                            revealed = p.revealed,
                            onReveal = { phase = p.copy(revealed = true) },
                            onGrade = { grade -> onGrade(card, grade, p.cards, p.index) },
                        )
                    }
                }
                is WordStudyPhase.OfferNew -> OfferNewView(
                    reviewedCount = p.reviewedCount,
                    newWordCount = maxNewWords,
                    onGenerate = ::generateNewWords,
                    onDone = {
                        if (p.reviewedCount > 0) {
                            phase = WordStudyPhase.Summary(p.reviewedCount, 0)
                        } else {
                            onExit()
                        }
                    },
                )
                is WordStudyPhase.GenerationFailed -> CenterHint {
                    Text(
                        text = "新词没拿到：${p.reason}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                    Button(onClick = ::generateNewWords) { Text("再试一次") }
                    TextButton(onClick = onExit) { Text("先退出") }
                }
                is WordStudyPhase.Summary -> SummaryView(
                    reviewedCount = p.reviewedCount,
                    newCount = p.newCount,
                    onExit = onExit,
                )
            }
        }
    }
}

/**
 * 词卡的结构化提问上下文。没揭示答案前不把释义和例句交出去，
 * 免得一句"这词什么意思"直接把自评环节绕过去。
 */
private fun StudyCard.toAskContext(revealed: Boolean): AskContext {
    // 产出卡是反过来的：中文在明面，英文才是答案，所以没作答前不能把词交出去。
    if (isSpellingCard && !revealed) {
        return AskContext(
            kind = AskContextKind.Word,
            title = "正在回想一个词 · $meaningZh",
            details = listOf(
                AskDetail("中文释义", meaningZh),
                AskDetail("词性", pos.ifBlank { "未标注" }),
                AskDetail("状态", "学习者要自己写出这个英文词，绝对不能直接告诉他是哪个词，只能给用法或场景上的提示"),
            ),
            suggestions = listOf("这个意思一般用在什么场景？", "给我一个不含答案的提示"),
        )
    }
    return AskContext(
        kind = AskContextKind.Word,
        title = if (revealed && meaningZh.isNotBlank()) "$term · $meaningZh" else term,
        details = buildList {
            add(AskDetail("词条", term))
            if (ipa.isNotBlank()) add(AskDetail("音标", ipa))
            if (revealed) {
                if (pos.isNotBlank()) add(AskDetail("词性", pos))
                if (meaningZh.isNotBlank()) add(AskDetail("释义", meaningZh))
                if (collocations.isNotEmpty()) add(AskDetail("搭配", collocations.joinToString("、") { listOf(it.en, it.zh).filter { part -> part.isNotBlank() }.joinToString(" ") }))
                if (exampleEn.isNotBlank()) add(AskDetail("例句", exampleEn))
            } else {
                add(AskDetail("状态", "学习者还没看答案，别直接把中文释义说出来"))
            }
            add(AskDetail("卡片类型", if (isNew) "AI 新给的词" else "到期复习的词"))
        },
        suggestions = if (revealed) {
            listOf("这个词平时说话会用吗？", "有哪些容易混的近义词？", "再给我两个例句")
        } else {
            listOf("这个词大概什么场景会出现？", "它和哪些词长得像？")
        },
    )
}

private fun GeneratedWord.toCard() = StudyCard(
    itemId = null,
    term = term,
    ipa = ipa,
    meaningZh = meaningZh,
    exampleEn = exampleEn,
    exampleZh = exampleZh,
    isNew = true,
    pos = pos,
    collocations = collocations,
    memoryHintZh = memoryHintZh,
    forms = forms,
    facts = SpellingFacts(chunks = chunks, trickyPart = trickyPart, misspellings = misspellings),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StudyCardView(
    card: StudyCard,
    revealed: Boolean,
    onReveal: () -> Unit,
    onGrade: (ReviewGrade) -> Unit,
) {
    val context = LocalContext.current
    val app = remember { context.applicationContext as LazyDogApplication }
    val scope = rememberCoroutineScope()
    val speech = app.speechController

    // 卡片出现时自动朗读（设置里可关）。
    LaunchedEffect(card.term) {
        if (app.userPreferences.autoReadWords.first()) speech.play(PlaybackSource.word(card.term))
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, if (revealed) Alignment.Top else Alignment.CenterVertically),
            horizontalAlignment = if (revealed) Alignment.Start else Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InteractiveEnglishText(
                    text = card.term,
                    style = if (revealed) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.displayMedium,
                )
                SpeakButton(PlaybackSource.word(card.term), contentDescription = "再读一遍")
            }
            if (card.ipa.isNotBlank()) {
                Text(
                    text = card.ipa,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (card.isNew && !revealed) {
                Text(
                    text = "AI 给你的新词 · 先猜猜意思",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (revealed) {
                Text(
                    text = if (card.pos.isNotBlank()) "${posLabelZh(card.pos)} ${card.meaningZh}" else card.meaningZh,
                    style = MaterialTheme.typography.titleMedium,
                )
                if (card.collocations.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        card.collocations.forEach { collocation -> CollocationChip(collocation) }
                    }
                }
                // S0 接触（设计稿 62 屏）：新词第一次露面就把词块拆开摆着。
                // 拼写练习后面所有阶段都按这套词块出题，第一眼见到的结构和后面练的是同一套。
                if (card.isNew) SpellingChunks(card.term, card.facts)
                // 已经入库的词才给记忆提示面板：那条提示要挂在 itemId 上存起来，
                // 也要用到这个词的薄弱片段。新词卡还没有 id，先显示生成时带出来的那一句。
                if (card.itemId != null) {
                    MemoryHintPanel(itemId = card.itemId, fallbackHintZh = card.memoryHintZh)
                } else if (card.memoryHintZh.isNotBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Lightbulb,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                                Text(
                                    text = "怎么记",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                            }
                            Text(
                                text = card.memoryHintZh,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }
                if (card.exampleEn.isNotBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                InteractiveEnglishText(
                                    text = card.exampleEn,
                                    style = MaterialTheme.typography.bodyLarge,
                                    speakOnSingleTap = true,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                SpeakButton(
                                    source = PlaybackSource.sentence(card.exampleEn),
                                    contentDescription = "朗读例句",
                                )
                            }
                            if (card.exampleZh.isNotBlank()) {
                                Text(
                                    text = card.exampleZh,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            // 例句里的生词一样能双击查、三击讲，但双击三击是看不见的交互——
                            // 不写这一行，这块英文和一张图片没区别。
                            InteractiveTextHint(speakOnSingleTap = true)
                        }
                    }
                }
            }
        }
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (!revealed) {
                Button(
                    onClick = onReveal,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                ) {
                    Text("看答案", style = MaterialTheme.typography.titleMedium)
                }
            } else {
                Text(
                    text = if (card.isNew) "这个词你原来认识吗？照实点，算法才准" else "刚才想起来了吗？照实点，算法才准",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    ReviewGrade.entries.forEach { grade ->
                        OutlinedButton(
                            onClick = { onGrade(grade) },
                            modifier = Modifier.weight(1f),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 10.dp),
                        ) {
                            Text(
                                text = if (card.isNew) grade.newLabel else grade.label,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OfferNewView(
    reviewedCount: Int,
    newWordCount: Int,
    onGenerate: () -> Unit,
    onDone: () -> Unit,
) {
    CenterHint {
        if (reviewedCount > 0) {
            Icon(
                imageVector = Icons.Outlined.TaskAlt,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Text("到期的 $reviewedCount 个词复习完了", style = MaterialTheme.typography.titleMedium)
        } else {
            Text("现在没有到期要复习的词", style = MaterialTheme.typography.titleMedium)
        }
        Button(onClick = onGenerate) {
            Icon(Icons.Outlined.AutoAwesome, contentDescription = null)
            Text("让 AI 来 $newWordCount 个新词", modifier = Modifier.padding(start = 8.dp))
        }
        TextButton(onClick = onDone) { Text("今天到这") }
    }
}

@Composable
private fun SummaryView(reviewedCount: Int, newCount: Int, onExit: () -> Unit) {
    CenterHint {
        Icon(
            imageVector = Icons.Outlined.TaskAlt,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Text("这轮搞定", style = MaterialTheme.typography.headlineSmall)
        Text(
            text = buildString {
                if (reviewedCount > 0) append("复习了 $reviewedCount 个词")
                if (reviewedCount > 0 && newCount > 0) append(" · ")
                if (newCount > 0) append("新学了 $newCount 个词")
            }.ifBlank { "什么也没学，也挺好" },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "都已经记进复习计划，到期会在记录页出现。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onExit) { Text("收工") }
    }
}

@Composable
private fun CenterHint(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        content()
    }
}
