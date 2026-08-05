package com.lazydog.english.feature.reading

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.AutoAwesome
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
import com.lazydog.english.core.data.ReadingJson
import com.lazydog.english.core.data.ReadingRepository
import com.lazydog.english.core.designsystem.InteractiveEnglishText
import com.lazydog.english.core.model.KnowledgeStage
import com.lazydog.english.domain.generation.GenerationResult
import com.lazydog.english.domain.generation.ReadingGenerationRequest
import com.lazydog.english.domain.generation.ReadingQuestion
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

private const val TARGET_LENGTH = 160
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
    var progressChars by remember { mutableStateOf(0) }

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
        progressChars = 0
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
                .map { it.detail.name }
                .take(2)

            val request = ReadingGenerationRequest(
                learnerLevel = app.userPreferences.learnerLevelDescription.first(),
                topic = topic,
                targetLength = TARGET_LENGTH,
                reviewVocabulary = due,
                knownVocabulary = known,
                reviewGrammar = dueGrammar,
                maxNewWords = MAX_NEW_WORDS,
            )
            when (val result = app.contentGenerator.generateReading(request, onProgress = { progressChars = it })) {
                is GenerationResult.Failure -> phase = ReadingPhase.Failed(result.reason)
                is GenerationResult.Success -> {
                    val id = readingRepo.saveGenerated(
                        reading = result.data,
                        topic = topic,
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
                    cefr = "",
                    source = ReadingRepository.SOURCE_PASTED,
                    targetWords = emptyList(),
                    questions = emptyList(),
                ),
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("阅读") },
                navigationIcon = {
                    IconButton(onClick = onExit) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
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
                ReadingPhase.Setup -> SetupView(onGenerate = ::generate)
                ReadingPhase.PasteInput -> PasteView(onSave = ::savePasted)
                ReadingPhase.Generating -> CenterColumn {
                    CircularProgressIndicator()
                    Text(
                        text = if (progressChars > 0) "AI 正在写文章… 已生成 $progressChars 字" else "AI 正在写文章，会把到期复习词编进去…",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
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

@Composable
private fun SetupView(onGenerate: (String) -> Unit) {
    val context = LocalContext.current
    val app = remember { context.applicationContext as LazyDogApplication }
    val savedTopics by app.userPreferences.topics.collectAsState(initial = emptySet())
    var selectedTopic by rememberSaveable { mutableStateOf("") }
    var customTopic by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "挑个主题，AI 会写一篇约 $TARGET_LENGTH 词的短文，把你到期的复习词编进去，再带最多 $MAX_NEW_WORDS 个新词。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            savedTopics.take(4).forEach { topic ->
                FilterChip(
                    selected = selectedTopic == topic,
                    onClick = {
                        selectedTopic = if (selectedTopic == topic) "" else topic
                        customTopic = ""
                    },
                    label = { Text(topic) },
                )
            }
        }
        OutlinedTextField(
            value = customTopic,
            onValueChange = {
                customTopic = it
                if (it.isNotBlank()) selectedTopic = ""
            },
            label = { Text("或者自己写一个主题") },
            placeholder = { Text("比如：一次搞砸的露营") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { onGenerate(customTopic.trim().ifBlank { selectedTopic }) },
            enabled = selectedTopic.isNotBlank() || customTopic.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Outlined.AutoAwesome, contentDescription = null)
            Text("生成短文", modifier = Modifier.padding(start = 8.dp))
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

@Composable
private fun MaterialContent(
    material: MaterialView,
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
            IconButton(onClick = { scope.launch { app.speechController.speak(material.body) } }) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.VolumeUp,
                    contentDescription = "朗读全文",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                "快速双击查词 · 快速三击讲句",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
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
                            Text(
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
                        IconButton(onClick = { scope.launch { app.speechController.speak(target.term) } }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.VolumeUp,
                                contentDescription = "朗读 ${target.term}",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }

        if (material.questions.isNotEmpty()) {
            Text("读懂了吗", style = MaterialTheme.typography.titleMedium)
            QuestionList(material.questions, onAllAnswered = onQuestionsCompleted)
        }
    }
}

@Composable
private fun QuestionList(
    questions: List<ReadingQuestion>,
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
                    Surface(
                        onClick = { if (!answered) answers[qIndex] = oIndex },
                        enabled = !answered,
                        color = container,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        InteractiveEnglishText(
                            text = option,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(12.dp),
                            onSingleTap = { if (!answered) answers[qIndex] = oIndex },
                        )
                    }
                }
                if (selected != null && question.explanationZh.isNotBlank()) {
                    Text(
                        text = (if (selected == question.answerIndex) "对了。" else "不对。") + question.explanationZh,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
