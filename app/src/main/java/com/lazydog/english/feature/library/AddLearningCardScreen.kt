package com.lazydog.english.feature.library

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Info
import androidx.activity.compose.BackHandler
import com.lazydog.english.feature.vocabulary.WordLearningContent
import com.lazydog.english.feature.grammar.GrammarLearningContent
import com.lazydog.english.domain.generation.NewWordsRequest
import com.lazydog.english.domain.generation.MemoryAssistance
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lazydog.english.LazyDogApplication
import com.lazydog.english.core.data.KnowledgeRepository
import com.lazydog.english.core.data.LearningCardMetadata
import com.lazydog.english.core.data.saveGrammarCard
import com.lazydog.english.core.data.saveWordCard
import com.lazydog.english.core.designsystem.AiWaiting
import com.lazydog.english.core.designsystem.LazyDogTheme
import com.lazydog.english.domain.generation.GeneratedGrammarLesson
import com.lazydog.english.domain.generation.GenerationResult
import com.lazydog.english.domain.generation.GenerationStage
import com.lazydog.english.domain.generation.GrammarLessonRequest
import com.lazydog.english.domain.generation.GeneratedWord
import com.lazydog.english.domain.generation.validateWordCard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddLearningCardScreen(
    isVocab: Boolean,
    app: LazyDogApplication,
    repository: KnowledgeRepository,
    onDismiss: () -> Unit,
) {
    // Scope 随页面销毁而取消；返回后，旧请求不会再更新别的卡片。
    val scope = rememberCoroutineScope()
    var input by rememberSaveable { mutableStateOf("") }
    var context by rememberSaveable { mutableStateOf("") }
    var word by remember { mutableStateOf<GenerationResult.Success<GeneratedWord>?>(null) }
    var memoryHint by remember { mutableStateOf<GenerationResult.Success<MemoryAssistance>?>(null) }
    var grammar by remember { mutableStateOf<GenerationResult.Success<GeneratedGrammarLesson>?>(null) }
    var metadata by remember { mutableStateOf<LearningCardMetadata?>(null) }
    var generating by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var stage by remember { mutableStateOf<GenerationStage>(GenerationStage.Connecting) }
    val hasDraft = word != null || grammar != null

    fun generate() {
        if (generating || saving) return
        generating = true
        error = null
        stage = GenerationStage.Thinking("")
        scope.launch {
            try {
                val level = if (isVocab) app.userPreferences.learnerLevel.first().ifBlank { "A2" }
                    else app.userPreferences.grammarLevelDescription.first()
                val topics = if (isVocab) app.userPreferences.topics.first().toList() else emptyList()
                fun buildMetadata(model: String, version: Int) = LearningCardMetadata(
                    input.trim(), context.trim(), level, topics, model, version, System.currentTimeMillis(),
                )
                if (isVocab) {
                    when (val result = app.contentGenerator.generateNewWords(
                        NewWordsRequest(count = 1, learnerLevel = level, topics = topics,
                            knownTerms = emptyList(), targetTerm = input.trim(), sentenceContext = context.trim()),
                        onStage = { stage = it },
                    )) {
                        is GenerationResult.Failure -> error = result.reason
                        is GenerationResult.Success -> {
                            error = validateWordCard(result.data.first())
                            if (error == null) {
                                word = GenerationResult.Success(result.data.first(), result.model, result.promptVersion, result.droppedNotes)
                                memoryHint = null
                                metadata = buildMetadata(result.model, result.promptVersion)
                            }
                        }
                    }
                } else {
                    when (val result = app.contentGenerator.generateGrammarLesson(
                        GrammarLessonRequest(level, input.trim(), emptyList()),
                        onStage = { stage = it },
                    )) {
                        is GenerationResult.Failure -> error = result.reason
                        is GenerationResult.Success -> {
                            grammar = result
                            metadata = buildMetadata(result.model, result.promptVersion)
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = "生成失败，请检查连接后重试。"
            } finally {
                generating = false
            }
        }
    }

    fun save() {
        val snapshot = metadata ?: return
        if (saving || generating) return
        saving = true
        error = null
        scope.launch {
            try {
                val id = word?.let { repository.saveWordCard(it, snapshot, memoryHint) }
                    ?: if (word == null) grammar?.let { repository.saveGrammarCard(it, snapshot) } else null
                if (id != null) onDismiss()
                else error = "记录中已经有这张卡了，未重复保存。"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = "保存失败，学习卡仍在这里，可以重试。"
            } finally {
                saving = false
            }
        }
    }

    BackHandler(enabled = saving) { }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isVocab) "单词学习卡" else "语法学习卡") },
                navigationIcon = {
                    IconButton(onClick = onDismiss, enabled = !saving) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回记录")
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.navigationBarsPadding().imePadding().padding(horizontal = 16.dp)
                    .padding(top = 12.dp, bottom = 16.dp)) {
                    Button(onClick = { if (hasDraft) save() else generate() },
                        enabled = input.isNotBlank() && !generating && !saving,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        Text(if (saving) "正在添加" else if (hasDraft) "添加" else "生成学习卡")
                    }
                    // 草稿的三个出路排在主按钮下面这一行。原来「重新生成」「修改输入」挂在内容最底下、
                    // 「不添加」在底栏，同一件事的选项散在两处，得先翻回去才知道有哪些。
                    // 还没生成时这一行整条不出现：那时候唯一的"取消"就是左上角的返回。
                    if (hasDraft) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            TextButton(onClick = ::generate, enabled = !generating && !saving) {
                                Text("重新生成")
                            }
                            TextButton(
                                onClick = {
                                    word = null; grammar = null; metadata = null; memoryHint = null; error = null
                                },
                                enabled = !generating && !saving,
                            ) { Text("改输入") }
                            TextButton(
                                onClick = onDismiss,
                                enabled = !saving,
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                            ) { Text("不添加") }
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // 草稿态原来和已经存下的卡长得一模一样，唯一的线索是底栏按钮上那两个字，
            // 得用户自己反推。这条横幅把话说在前面。
            if (hasDraft) {
                Surface(color = LazyDogTheme.extendedColors.attentionContainer,
                    modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Outlined.Info, contentDescription = null,
                            tint = LazyDogTheme.extendedColors.attention, modifier = Modifier.size(18.dp))
                        Text("还没添加到记录 · 看完再决定要不要留下",
                            style = MaterialTheme.typography.labelMedium,
                            color = LazyDogTheme.extendedColors.attention)
                    }
                }
            }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (!hasDraft) {
                    Text("AI 会先把整张卡写出来给你看，看过之后再决定要不要添加到记录。")
                    OutlinedTextField(value = input, onValueChange = { input = it.take(200); error = null },
                        enabled = !generating, label = { Text(if (isVocab) "英文单词原形" else "语法名称或结构") },
                        modifier = Modifier.fillMaxWidth())
                    if (isVocab) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedTextField(value = context, onValueChange = { context = it.take(1000) },
                            enabled = !generating, label = { Text("语境（可选）") },
                            modifier = Modifier.fillMaxWidth())
                        // 这个框留白时用户多半以为它可填可不填、填了也不知道去哪儿了。
                        Text("贴上你遇到它的那句话，释义和例句会照着那个意思来",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp))
                    }
                }
                if (generating) AiWaiting("正在生成完整学习卡", stage)
                word?.let { result ->
                    androidx.compose.runtime.key(result) {
                        WordLearningContent(card = result.data, memoryAssistance = memoryHint,
                            onMemoryHint = { memoryHint = it })
                    }
                }
                grammar?.let { GrammarLearningContent(it.data) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}
