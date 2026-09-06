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
import com.lazydog.english.domain.generation.LearningTargetRequest
import com.lazydog.english.domain.generation.LearningTargetSuggestion
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
    var suggestions by remember { mutableStateOf<List<LearningTargetSuggestion>?>(null) }
    // 挑候选之前用户打的那句中文。挑完 input 会被换成英文目标，这句得留着，
    // 不然「换一批」和「改输入」就只能拿英文再去问一次候选。
    var queryZh by rememberSaveable { mutableStateOf("") }
    val hasDraft = word != null || grammar != null
    val choosing = suggestions != null && !hasDraft
    // 打的是中文就先挑目标。判断只看有没有汉字：混着打「后悔 regret」也算中文，
    // 那种情况下他要的多半也是"帮我找找到底哪个词对"。
    val needsSuggesting = !hasDraft && input.any { it.code in 0x4E00..0x9FFF }

    fun generate(target: String = input.trim()) {
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
                    target, context.trim(), level, topics, model, version, System.currentTimeMillis(),
                )
                if (isVocab) {
                    when (val result = app.contentGenerator.generateNewWords(
                        NewWordsRequest(count = 1, learnerLevel = level, topics = topics,
                            knownTerms = emptyList(), targetTerm = target, sentenceContext = context.trim()),
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
                        GrammarLessonRequest(level, target, emptyList()),
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

    /** 拿那句中文去问有哪些能学的目标。只挑目标，不生成学习卡，也不碰记录。 */
    fun suggest(query: String = input.trim()) {
        if (generating || saving || query.isBlank()) return
        generating = true
        error = null
        queryZh = query
        suggestions = null
        stage = GenerationStage.Thinking("")
        scope.launch {
            try {
                val level = if (isVocab) app.userPreferences.learnerLevel.first().ifBlank { "A2" }
                    else app.userPreferences.grammarLevelDescription.first()
                val topics = if (isVocab) app.userPreferences.topics.first().toList() else emptyList()
                when (val result = app.contentGenerator.suggestLearningTargets(
                    LearningTargetRequest(query, isVocab, level, topics),
                    onStage = { stage = it },
                )) {
                    is GenerationResult.Failure -> error = result.reason
                    is GenerationResult.Success -> suggestions = result.data
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = "没连上，找候选失败了，检查连接后重试。"
            } finally {
                generating = false
            }
        }
    }

    /** 回到输入态。草稿、候选和上一次的错误一起清掉，别让上一轮的东西留在屏幕上。 */
    fun backToInput(restoreQuery: Boolean) {
        word = null
        grammar = null
        metadata = null
        memoryHint = null
        suggestions = null
        error = null
        if (restoreQuery && queryZh.isNotBlank()) input = queryZh
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
                    // 挑候选的时候不摆主按钮：这一屏的主要动作是从列表里点一个，
                    // 底下再放一个大按钮，反而要用户先想清楚这两者是什么关系。
                    if (!choosing) {
                        Button(
                            onClick = {
                                when {
                                    hasDraft -> save()
                                    needsSuggesting -> suggest()
                                    else -> generate()
                                }
                            },
                            enabled = input.isNotBlank() && !generating && !saving,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        ) {
                            Text(
                                when {
                                    saving -> "正在添加"
                                    hasDraft -> "添加"
                                    // 中文进来时先找目标，按钮就得说这一下会发生什么，
                                    // 不能还写着"生成学习卡"——那会让人以为卡马上就出来了。
                                    needsSuggesting -> "找找学什么"
                                    else -> "生成学习卡"
                                },
                            )
                        }
                    }
                    // 草稿的三个出路排在主按钮下面这一行。原来「重新生成」「修改输入」挂在内容最底下、
                    // 「不添加」在底栏，同一件事的选项散在两处，得先翻回去才知道有哪些。
                    // 还没生成时这一行整条不出现：那时候唯一的"取消"就是左上角的返回。
                    if (hasDraft) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            TextButton(onClick = { generate() }, enabled = !generating && !saving) {
                                Text("重新生成")
                            }
                            TextButton(
                                onClick = { backToInput(restoreQuery = true) },
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
                    if (choosing) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            TextButton(onClick = { suggest(queryZh) }, enabled = !generating) {
                                Text("换一批")
                            }
                            TextButton(
                                onClick = { backToInput(restoreQuery = true) },
                                enabled = !generating,
                            ) { Text("改输入") }
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
                if (!hasDraft && !choosing) {
                    Text("AI 会先把整张卡写出来给你看，看过之后再决定要不要添加到记录。")
                    OutlinedTextField(value = input, onValueChange = { input = it.take(200); error = null },
                        enabled = !generating,
                        label = { Text(if (isVocab) "英文单词原形，或中文" else "语法名称、英文结构，或中文") },
                        modifier = Modifier.fillMaxWidth())
                    // 不知道英文怎么说，正是最该学的时候。要求先自己译成英文，
                    // 等于把最难的一步留给用户，然后才让他开始学。
                    Text(
                        text = if (isVocab) "打中文也行：「深思熟虑」「表示后悔的说法」都可以，先挑目标再生成卡"
                        else "打中文也行：「已经做完了怎么说」这种描述也认，先挑目标再生成卡",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
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
                suggestions?.takeIf { !hasDraft }?.let { list ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("你说的是「$queryZh」", style = MaterialTheme.typography.titleMedium)
                        Text("挑一个，再给你写整张卡",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        list.forEach { suggestion ->
                            LearningTargetRow(suggestion, enabled = !generating) {
                                // 挑中的那个目标顶掉输入框里的中文：接下来生成、重新生成、
                                // 记进 metadata 的都是它。原来那句中文留在 queryZh 里，
                                // 「改输入」还能把它放回来。
                                input = suggestion.target
                                suggestions = null
                                generate(suggestion.target)
                            }
                        }
                    }
                }
                if (generating) {
                    AiWaiting(if (choosing || (needsSuggesting && suggestions == null)) "正在找有哪些能学的"
                    else "正在生成完整学习卡", stage)
                }
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

/**
 * 一条候选。英文目标排在最上面、字号最大——挑的就是它，
 * 中文标签和那句区别说明是拿来判断"是不是这个"的依据，不该跟它抢。
 */
@Composable
private fun LearningTargetRow(
    suggestion: LearningTargetSuggestion,
    enabled: Boolean,
    onPick: () -> Unit,
) {
    Surface(
        onClick = onPick,
        enabled = enabled,
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(suggestion.target, style = MaterialTheme.typography.titleMedium)
            Text(
                text = suggestion.labelZh,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (suggestion.noteZh.isNotBlank()) {
                Text(
                    text = suggestion.noteZh,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}
