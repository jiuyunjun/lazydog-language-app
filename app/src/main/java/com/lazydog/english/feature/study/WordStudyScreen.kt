package com.lazydog.english.feature.study

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lazydog.english.LazyDogApplication
import com.lazydog.english.core.data.KnowledgeRepository
import com.lazydog.english.core.model.ReviewGrade
import com.lazydog.english.domain.generation.GeneratedWord
import com.lazydog.english.domain.generation.GenerationResult
import com.lazydog.english.domain.generation.NewWordsRequest
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
)

private sealed interface WordStudyPhase {
    data object Loading : WordStudyPhase
    data class Cards(val cards: List<StudyCard>, val index: Int, val revealed: Boolean) : WordStudyPhase
    data class OfferNew(val reviewedCount: Int) : WordStudyPhase
    data object Generating : WordStudyPhase
    data class GenerationFailed(val reason: String, val reviewedCount: Int) : WordStudyPhase
    data class Summary(val reviewedCount: Int, val newCount: Int) : WordStudyPhase
}

private const val NEW_WORDS_PER_BATCH = 5
private const val DEFAULT_LEVEL = "A2-B1（能力测试上线前的默认估计）"

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

    // 进来先取到期复习；没有就直接进入“要不要新词”。
    LaunchedEffect(Unit) {
        val now = System.currentTimeMillis()
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
                )
            }
        phase = if (due.isEmpty()) WordStudyPhase.OfferNew(0) else WordStudyPhase.Cards(due, 0, revealed = false)
    }

    fun generateNewWords() {
        phase = WordStudyPhase.Generating
        scope.launch {
            val prefs = app.userPreferences
            val known = repository.vocabulary.first().map { it.detail.term }.take(200)
            val result = app.contentGenerator.generateNewWords(
                NewWordsRequest(
                    count = NEW_WORDS_PER_BATCH,
                    learnerLevel = DEFAULT_LEVEL,
                    topics = prefs.topics.first().toList(),
                    knownTerms = known,
                ),
            )
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
                            modifier = Modifier.padding(end = 16.dp),
                        )
                    }
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
                WordStudyPhase.Generating -> CenterHint {
                    CircularProgressIndicator()
                    Text("AI 正在挑词…", style = MaterialTheme.typography.bodyMedium)
                }
                is WordStudyPhase.Cards -> {
                    val card = p.cards[p.index]
                    StudyCardView(
                        card = card,
                        revealed = p.revealed,
                        onReveal = { phase = p.copy(revealed = true) },
                        onGrade = { grade -> onGrade(card, grade, p.cards, p.index) },
                    )
                }
                is WordStudyPhase.OfferNew -> OfferNewView(
                    reviewedCount = p.reviewedCount,
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

private fun GeneratedWord.toCard() = StudyCard(
    itemId = null,
    term = term,
    ipa = ipa,
    meaningZh = meaningZh,
    exampleEn = exampleEn,
    exampleZh = exampleZh,
    isNew = true,
)

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
        if (app.userPreferences.autoReadWords.first()) speech.speak(card.term)
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
                Text(
                    text = card.term,
                    style = if (revealed) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.displayMedium,
                )
                IconButton(onClick = { scope.launch { speech.speak(card.term) } }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.VolumeUp,
                        contentDescription = "再读一遍",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
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
                Text(card.meaningZh, style = MaterialTheme.typography.titleMedium)
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
                                Text(
                                    text = card.exampleEn,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                IconButton(onClick = { scope.launch { speech.speak(card.exampleEn) } }) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Outlined.VolumeUp,
                                        contentDescription = "朗读例句",
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                            if (card.exampleZh.isNotBlank()) {
                                Text(
                                    text = card.exampleZh,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
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
                    text = if (card.isNew) "感觉这个词对你来说：" else "刚才想起来了吗？照实点，算法才准",
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
                            Text(grade.label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OfferNewView(reviewedCount: Int, onGenerate: () -> Unit, onDone: () -> Unit) {
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
            Text("让 AI 来 $NEW_WORDS_PER_BATCH 个新词", modifier = Modifier.padding(start = 8.dp))
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
