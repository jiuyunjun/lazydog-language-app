package com.lazydog.english.feature.scenario

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Casino
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.SyncProblem
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.lazydog.english.LazyDogApplication
import com.lazydog.english.core.ask.ProvideAskContext
import com.lazydog.english.core.data.ScenarioReplyMode
import com.lazydog.english.core.data.ScenarioSessionSnapshot
import com.lazydog.english.core.data.ScenarioStage
import com.lazydog.english.core.designsystem.InteractiveEnglishText
import com.lazydog.english.domain.ask.AskContext
import com.lazydog.english.domain.ask.AskContextKind
import com.lazydog.english.domain.ask.AskDetail
import com.lazydog.english.core.designsystem.rememberWaitedSeconds
import com.lazydog.english.core.designsystem.stageDetail
import com.lazydog.english.domain.generation.GenerationResult
import com.lazydog.english.domain.generation.GenerationStage
import com.lazydog.english.feature.ask.AskTopBarAction
import com.lazydog.english.domain.scenario.CommunicationFailure
import com.lazydog.english.domain.scenario.ScenarioBrief
import com.lazydog.english.domain.scenario.ScenarioDifficulty
import com.lazydog.english.domain.scenario.ScenarioGenerationRequest
import com.lazydog.english.domain.scenario.ScenarioImprovement
import com.lazydog.english.domain.scenario.ScenarioJudgement
import com.lazydog.english.domain.scenario.ScenarioMessage
import com.lazydog.english.domain.scenario.ScenarioReplyOption
import com.lazydog.english.domain.scenario.ScenarioSource
import com.lazydog.english.domain.scenario.ScenarioSpeaker
import com.lazydog.english.domain.scenario.ScenarioSummary
import com.lazydog.english.domain.scenario.ScenarioSummaryRequest
import com.lazydog.english.domain.scenario.ScenarioTurn
import com.lazydog.english.domain.scenario.ScenarioTurnRequest
import com.lazydog.english.domain.speaking.TranscriptionResult
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private enum class ScenarioPhase { Loading, Pick, Brief, Conversation, Summary, Replay, Finish }

private data class ScenarioSeed(
    val title: String,
    val who: String,
    val seed: String,
    val icon: ImageVector,
)

private val recommendedSeeds = listOf(
    ScenarioSeed("酒店房型订错了", "和不太松口的前台经理谈", "酒店给错房型，争取升级或退差价", Icons.Outlined.AutoAwesome),
    ScenarioSeed("账单里多了一项", "请餐厅经理核对并处理", "餐厅账单多收费，礼貌指出并达成解决方案", Icons.Outlined.AutoAwesome),
    ScenarioSeed("面试时间撞了", "和招聘人员重新约时间", "工作面试时间冲突，需要说明情况并改约", Icons.Outlined.AutoAwesome),
    ScenarioSeed("房东一直不修东西", "催一位拖延的房东", "出租屋设备坏了，要求房东明确维修时间", Icons.Outlined.AutoAwesome),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScenarioScreen(sessionId: Long?, onExit: () -> Unit) {
    val context = LocalContext.current
    val app = remember { context.applicationContext as LazyDogApplication }
    val scope = rememberCoroutineScope()

    val sessionRepository = app.scenarioSessionRepository
    var currentSessionId by remember { mutableStateOf(sessionId) }
    var phase by remember { mutableStateOf(if (sessionId == null) ScenarioPhase.Pick else ScenarioPhase.Loading) }
    /** 生成走到哪一步了：没接通 / 模型在想 / 正在写。 */
    var stage by remember { mutableStateOf<GenerationStage>(GenerationStage.Connecting) }
    var customSeed by remember { mutableStateOf("") }
    var selectedSeed by remember { mutableStateOf<ScenarioSeed?>(recommendedSeeds.first()) }
    var brief by remember { mutableStateOf<ScenarioBrief?>(null) }
    var difficultyTier by remember { mutableStateOf(2) }
    var messages by remember { mutableStateOf<List<ScenarioMessage>>(emptyList()) }
    var options by remember { mutableStateOf<List<ScenarioReplyOption>>(emptyList()) }
    var hint by remember { mutableStateOf("") }
    var replyMode by remember { mutableStateOf(ScenarioReplyMode.Options) }
    var input by remember { mutableStateOf("") }
    var achievedGoals by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var communicationFailure by remember { mutableStateOf<CommunicationFailure?>(null) }
    var readyToFinish by remember { mutableStateOf(false) }
    var summary by remember { mutableStateOf<ScenarioSummary?>(null) }
    var replayIndex by remember { mutableStateOf(0) }
    var savedPhraseCount by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var transcribing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var goalsExpanded by remember { mutableStateOf(false) }

    fun snapshot(stage: ScenarioStage, briefValue: ScenarioBrief = brief ?: kotlin.error("brief required")) =
        ScenarioSessionSnapshot(
            brief = briefValue,
            stage = stage,
            messages = messages,
            options = options,
            hint = hint,
            replyMode = replyMode,
            input = input,
            achievedGoals = achievedGoals,
            communicationFailure = communicationFailure,
            readyToFinish = readyToFinish,
            summary = summary,
            replayIndex = replayIndex,
            savedPhraseCount = savedPhraseCount,
        )

    suspend fun persist(value: ScenarioSessionSnapshot) {
        currentSessionId = sessionRepository.save(currentSessionId, value)
    }

    fun persistAndExit() {
        val current = brief
        if (current == null) {
            onExit()
            return
        }
        val stage = when (phase) {
            ScenarioPhase.Brief -> ScenarioStage.Brief
            ScenarioPhase.Conversation -> ScenarioStage.Conversation
            ScenarioPhase.Summary -> ScenarioStage.Summary
            ScenarioPhase.Replay -> ScenarioStage.Replay
            ScenarioPhase.Finish -> ScenarioStage.Finished
            else -> null
        }
        if (stage == null) onExit() else scope.launch {
            persist(snapshot(stage, current))
            onExit()
        }
    }

    BackHandler(enabled = phase != ScenarioPhase.Loading) { persistAndExit() }

    LaunchedEffect(sessionId) {
        if (sessionId == null) return@LaunchedEffect
        val saved = sessionRepository.get(sessionId)
        if (saved == null) {
            error = "找不到这次演练，可能已经被清理。"
            phase = ScenarioPhase.Pick
            return@LaunchedEffect
        }
        brief = saved.brief
        messages = saved.messages
        options = saved.options
        hint = saved.hint
        replyMode = saved.replyMode
        input = saved.input
        achievedGoals = saved.achievedGoals
        communicationFailure = saved.communicationFailure
        readyToFinish = saved.readyToFinish
        summary = saved.summary
        replayIndex = saved.replayIndex.coerceIn(0, saved.summary?.improvements?.lastIndex?.coerceAtLeast(0) ?: 0)
        savedPhraseCount = saved.savedPhraseCount
        phase = when (saved.stage) {
            ScenarioStage.Brief -> ScenarioPhase.Brief
            ScenarioStage.Conversation -> ScenarioPhase.Conversation
            ScenarioStage.Summary -> ScenarioPhase.Summary
            ScenarioStage.Replay -> ScenarioPhase.Replay
            ScenarioStage.Finished -> ScenarioPhase.Finish
        }
    }

    LaunchedEffect(input) {
        if (input.isBlank() || brief == null) return@LaunchedEffect
        val stage = when (phase) {
            ScenarioPhase.Conversation -> ScenarioStage.Conversation
            ScenarioPhase.Replay -> ScenarioStage.Replay
            else -> return@LaunchedEffect
        }
        delay(600)
        persist(snapshot(stage).copy(input = input))
    }

    fun transcribe() {
        if (transcribing) return
        transcribing = true
        error = null
        scope.launch {
            when (val result = app.speechController.transcribeOnce()) {
                is TranscriptionResult.Done -> input = listOf(input.trim(), result.text).filter { it.isNotBlank() }.joinToString(" ")
                TranscriptionResult.NothingRecognized -> error = "没听清，再说一次试试。"
                is TranscriptionResult.Failed -> error = result.reason
            }
            transcribing = false
        }
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) transcribe() else error = "需要麦克风权限才能把语音转成文字。"
    }

    fun requestTranscription() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            transcribe()
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun createScenario(source: ScenarioSource, seed: String, harder: Boolean = false) {
        if (busy) return
        if (source != ScenarioSource.Random && seed.isBlank()) {
            error = "先写一个情况，或者选下面推荐的一个。"
            return
        }
        val requestedTier = if (harder) (difficultyTier + 1).coerceAtMost(3) else difficultyTier
        difficultyTier = requestedTier
        busy = true
        error = null
        scope.launch {
            val tier = requestedTier
            val result = app.contentGenerator.generateScenario(
                request = ScenarioGenerationRequest(
                    source = source,
                    seedZh = seed,
                    // 演练练的是产出，按表达等级配英文难度，不跟着通常更高的词汇等级走。
                    learnerLevel = app.userPreferences.expressionLevelDescription.first(),
                    learningGoal = app.userPreferences.learningGoal.first(),
                    topics = app.userPreferences.topics.first().toList(),
                    difficulty = ScenarioDifficulty(
                        informationLoad = tier,
                        cooperation = (4 - tier).coerceIn(1, 3),
                        followUpPressure = tier,
                        requiresPoliteRefusal = tier >= 2,
                        includesMisunderstanding = tier >= 3,
                    ),
                    excludedScenarioIds = app.userPreferences.recentScenarioIds(),
                ),
                onStage = { stage = it },
            )
            when (result) {
                is GenerationResult.Success -> {
                    brief = result.data
                    persist(
                        ScenarioSessionSnapshot(
                            brief = result.data,
                            stage = ScenarioStage.Brief,
                        ),
                    )
                    phase = ScenarioPhase.Brief
                }
                is GenerationResult.Failure -> error = result.reason
            }
            busy = false
        }
    }

    fun startConversation() {
        val current = brief ?: return
        scope.launch { app.userPreferences.recordScenarioPlayed(current.scenarioId) }
        val openingMessages = listOf(
            ScenarioMessage(0, ScenarioSpeaker.Opponent, current.openingLineEn, current.openingSubtextZh),
        )
        messages = openingMessages
        options = current.initialReplyOptions
        achievedGoals = emptyMap()
        communicationFailure = null
        readyToFinish = false
        phase = ScenarioPhase.Conversation
        scope.launch {
            persist(
                ScenarioSessionSnapshot(
                    brief = current,
                    stage = ScenarioStage.Conversation,
                    messages = openingMessages,
                    options = current.initialReplyOptions,
                ),
            )
        }
    }

    fun submitReply(reply: String) {
        val current = brief ?: return
        val cleanReply = reply.trim()
        if (cleanReply.isBlank() || busy) return
        busy = true
        error = null
        val transcriptBefore = messages
        val request = ScenarioTurnRequest(current, transcriptBefore, cleanReply)
        scope.launch {
            val pair = coroutineScope {
                // 只有对话这一路报阶段：判定是并行跑的，两路都往同一个状态写会来回跳。
                val turnCall = async { app.contentGenerator.generateScenarioTurn(request, onStage = { stage = it }) }
                val judgeCall = async { app.contentGenerator.judgeScenarioTurn(request) }
                turnCall.await() to judgeCall.await()
            }
            val turn = (pair.first as? GenerationResult.Success<ScenarioTurn>)?.data
            val judgement = (pair.second as? GenerationResult.Success<ScenarioJudgement>)?.data
            if (turn == null || judgement == null) {
                error = listOfNotNull(
                    (pair.first as? GenerationResult.Failure)?.reason,
                    (pair.second as? GenerationResult.Failure)?.reason,
                ).joinToString("；").ifBlank { "这一轮没生成好，请重试。" }
            } else {
                val userTurn = transcriptBefore.count { it.speaker == ScenarioSpeaker.User } + 1
                val updatedMessages = transcriptBefore +
                    ScenarioMessage(userTurn, ScenarioSpeaker.User, cleanReply) +
                    ScenarioMessage(userTurn, ScenarioSpeaker.Opponent, turn.opponentReplyEn, turn.opponentSubtextZh)
                messages = updatedMessages
                val updatedGoals = achievedGoals + judgement.achievedGoalIds.associateWith { userTurn }
                achievedGoals = updatedGoals
                val updatedFailure = judgement.communicationFailure
                communicationFailure = updatedFailure
                options = turn.replyOptions
                hint = turn.halfSentenceHintEn
                input = ""
                val updatedReady = turn.naturalEnding || updatedGoals.size == current.goals.size || userTurn >= 10
                readyToFinish = updatedReady
                persist(
                    ScenarioSessionSnapshot(
                        brief = current,
                        stage = ScenarioStage.Conversation,
                        messages = updatedMessages,
                        options = turn.replyOptions,
                        hint = turn.halfSentenceHintEn,
                        replyMode = replyMode,
                        achievedGoals = updatedGoals,
                        communicationFailure = updatedFailure,
                        readyToFinish = updatedReady,
                    ),
                )
            }
            busy = false
        }
    }

    fun finishConversation() {
        val current = brief ?: return
        if (busy) return
        busy = true
        error = null
        scope.launch {
            when (
                val result = app.contentGenerator.summarizeScenario(
                    request = ScenarioSummaryRequest(current, messages, achievedGoals.keys),
                    onStage = { stage = it },
                )
            ) {
                is GenerationResult.Success -> {
                    summary = result.data
                    persist(snapshot(ScenarioStage.Summary).copy(summary = result.data))
                    phase = ScenarioPhase.Summary
                }
                is GenerationResult.Failure -> error = result.reason
            }
            busy = false
        }
    }

    fun savePhrases() {
        val currentSummary = summary ?: return
        if (busy) return
        busy = true
        scope.launch {
            savedPhraseCount = currentSummary.keepPhrases.count { phrase ->
                app.knowledgeRepository.saveScenarioExpression(
                    expressionEn = phrase.en,
                    meaningZh = phrase.zh,
                ) != null
            }
            persist(snapshot(ScenarioStage.Finished).copy(savedPhraseCount = savedPhraseCount))
            busy = false
            phase = ScenarioPhase.Finish
        }
    }

    // 只有对话进行中可以摇一摇问；挑场景、总结、重演页不响应。
    ProvideAskContext(
        brief?.takeIf { phase == ScenarioPhase.Conversation }?.let { current ->
            askContextFor(current, messages, achievedGoals.keys)
        },
    )

    Scaffold(
        topBar = {
            if (phase !in setOf(ScenarioPhase.Summary, ScenarioPhase.Finish)) {
                TopAppBar(
                    title = { Text(if (phase == ScenarioPhase.Brief) "开演前" else "情景演练") },
                    navigationIcon = {
                        IconButton(onClick = {
                            persistAndExit()
                        }) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                        }
                    },
                    actions = { AskTopBarAction() },
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (phase) {
                ScenarioPhase.Loading -> FriendlyLoading(
                    title = "把上次演到一半的场景捡回来…",
                    detail = "对话、目标进度和没写完的输入都会恢复。",
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
                ScenarioPhase.Pick -> ScenarioPicker(
                    stage = stage,
                    customSeed = customSeed,
                    onCustomSeedChange = { customSeed = it; selectedSeed = null },
                    selectedSeed = selectedSeed,
                    onSelectSeed = { selectedSeed = it; customSeed = "" },
                    busy = busy,
                    error = error,
                    onUse = {
                        val custom = customSeed.isNotBlank()
                        createScenario(
                            if (custom) ScenarioSource.Custom else ScenarioSource.Recommended,
                            if (custom) customSeed else selectedSeed?.seed.orEmpty(),
                        )
                    },
                    onRandom = { createScenario(ScenarioSource.Random, "给一个适合我学习目标的真实场景") },
                )
                ScenarioPhase.Brief -> brief?.let { current ->
                    ScenarioBriefView(
                        stage = stage,
                        brief = current,
                        busy = busy,
                        error = error,
                        onStart = ::startConversation,
                        onHarder = { createScenario(ScenarioSource.Recommended, current.situationZh, harder = true) },
                    )
                }
                ScenarioPhase.Conversation -> brief?.let { current ->
                    ScenarioConversation(
                        stage = stage,
                        brief = current,
                        messages = messages,
                        options = options,
                        achievedGoals = achievedGoals,
                        goalsExpanded = goalsExpanded,
                        onToggleGoals = { goalsExpanded = !goalsExpanded },
                        replyMode = replyMode,
                        onReplyModeChange = {
                            replyMode = it
                            scope.launch { persist(snapshot(ScenarioStage.Conversation).copy(replyMode = it)) }
                        },
                        input = input,
                        onInputChange = { input = it },
                        hint = hint,
                        failure = communicationFailure,
                        onUseRepair = { repair -> communicationFailure = null; submitReply(repair) },
                        onRewriteRepair = { communicationFailure = null; replyMode = ScenarioReplyMode.Free; input = "" },
                        busy = busy,
                        transcribing = transcribing,
                        error = error,
                        readyToFinish = readyToFinish,
                        onSubmit = ::submitReply,
                        onMic = ::requestTranscription,
                        onFinish = ::finishConversation,
                    )
                }
                ScenarioPhase.Summary -> summary?.let { value ->
                    ScenarioSummaryView(
                        brief = brief!!,
                        summary = value,
                        achievedGoals = achievedGoals,
                        turnCount = messages.count { it.speaker == ScenarioSpeaker.User },
                        busy = busy,
                        error = error,
                        onReplay = {
                            replayIndex = 0
                            input = ""
                            phase = ScenarioPhase.Replay
                            scope.launch { persist(snapshot(ScenarioStage.Replay).copy(replayIndex = 0, input = "")) }
                        },
                        onSave = ::savePhrases,
                    )
                }
                ScenarioPhase.Replay -> summary?.let { value ->
                    val items = value.improvements.take(3)
                    ScenarioReplayView(
                        improvement = items[replayIndex],
                        index = replayIndex,
                        total = items.size,
                        input = input,
                        onInputChange = { input = it },
                        transcribing = transcribing,
                        onMic = ::requestTranscription,
                        onNext = {
                            if (replayIndex + 1 < items.size) {
                                replayIndex += 1
                                input = ""
                                scope.launch {
                                    persist(snapshot(ScenarioStage.Replay).copy(replayIndex = replayIndex, input = ""))
                                }
                            } else savePhrases()
                        },
                        onSkip = ::savePhrases,
                    )
                }
                ScenarioPhase.Finish -> ScenarioFinishView(
                    summary = summary!!,
                    savedCount = savedPhraseCount,
                    onDone = onExit,
                    onAgain = {
                        phase = ScenarioPhase.Pick
                        currentSessionId = null
                        brief = null
                        summary = null
                        customSeed = ""
                        selectedSeed = recommendedSeeds.first()
                        error = null
                    },
                )
            }
        }
    }
}

/**
 * 演练中的提问上下文：处境、对手、目标完成情况和最近两轮对话。
 * 只给对手说过的话和自己写过的话，判定器那套隐藏信息不进上下文。
 */
private fun askContextFor(
    brief: ScenarioBrief,
    messages: List<ScenarioMessage>,
    achievedGoalIds: Set<String>,
): AskContext {
    val lastOpponentLine = messages.lastOrNull { it.speaker == ScenarioSpeaker.Opponent }?.textEn.orEmpty()
    return AskContext(
        kind = AskContextKind.Scenario,
        title = "${brief.titleZh} · 对手 ${brief.opponentName}",
        details = buildList {
            add(AskDetail("处境", brief.situationZh))
            add(AskDetail("对手", "${brief.opponentName}（${brief.opponentRoleZh}）"))
            if (lastOpponentLine.isNotBlank()) add(AskDetail("对方最近这句", lastOpponentLine))
            messages.lastOrNull { it.speaker == ScenarioSpeaker.User }?.let {
                add(AskDetail("你上一句说的", it.textEn))
            }
            add(
                AskDetail(
                    "还没做到的目标",
                    brief.goals.filter { it.id !in achievedGoalIds }
                        .joinToString("；") { it.textZh }
                        .ifBlank { "都做到了" },
                ),
            )
        },
        suggestions = listOf("他这句什么意思？", "我想说的这层意思英文怎么讲？", "这么说会不会太冲？"),
    )
}

@Composable
private fun ScenarioPicker(
    stage: GenerationStage,
    customSeed: String,
    onCustomSeedChange: (String) -> Unit,
    selectedSeed: ScenarioSeed?,
    onSelectSeed: (ScenarioSeed) -> Unit,
    busy: Boolean,
    error: String?,
    onUse: () -> Unit,
    onRandom: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("给个情况，我来当那个不太好说话的人。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = customSeed,
            onValueChange = onCustomSeedChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("自己写一个情况") },
            placeholder = { Text("比如：下周跟房东说提前退租，想拿回押金") },
            minLines = 2,
            maxLines = 4,
        )
        Text("按你的目标推荐", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        recommendedSeeds.forEach { seed ->
            val selected = seed == selectedSeed
            Card(
                onClick = { onSelectSeed(seed) },
                colors = CardDefaults.cardColors(
                    containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.surfaceContainer,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(seed.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(seed.title, style = MaterialTheme.typography.titleSmall)
                        Text(seed.who, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(
                        if (selected) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                        contentDescription = if (selected) "已选择" else null,
                        tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
        ErrorText(error)
        if (busy) {
            FriendlyLoading(
                title = "正在搭场景和对手…",
                detail = "会先准备完成清单，再给四种开场说法。",
                stage = stage,
            )
        }
        Button(onClick = onUse, enabled = !busy, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            if (busy) SmallProgress() else Text("用这个练")
        }
        TextButton(onClick = onRandom, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.Casino, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text("随便给我一个")
        }
    }
}

@Composable
private fun ScenarioBriefView(
    stage: GenerationStage,
    brief: ScenarioBrief,
    busy: Boolean,
    error: String?,
    onStart: () -> Unit,
    onHarder: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("你的处境", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(brief.situationZh, style = MaterialTheme.typography.bodyLarge)
            }
        }
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${brief.opponentName} · ${brief.opponentRoleZh}", style = MaterialTheme.typography.titleMedium)
                Text(brief.opponentPersonalityZh, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text("要做到这 ${brief.goals.size} 件事", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        brief.goals.forEach { GoalRow(it.textZh, false, null) }
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("难度 · ${difficultyLabel(brief.difficulty)}", style = MaterialTheme.typography.titleSmall)
                DifficultyRow("信息量", brief.difficulty.informationLoad, "${brief.difficulty.informationLoad}/3")
                DifficultyRow("对方合作度", brief.difficulty.cooperation, "${brief.difficulty.cooperation}/3")
                DifficultyRow("追问强度", brief.difficulty.followUpPressure, "${brief.difficulty.followUpPressure}/3")
                Text(
                    listOfNotNull(
                        "礼貌拒绝".takeIf { brief.difficulty.requiresPoliteRefusal },
                        "可能有误解".takeIf { brief.difficulty.includesMisunderstanding },
                    ).joinToString(" · ").ifBlank { "没有额外机关" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        ErrorText(error)
        if (busy) {
            FriendlyLoading(
                title = "正在换一个更难缠的对手…",
                detail = "词汇难度不变，只增加信息量、追问和阻力。",
                stage = stage,
            )
        }
        Spacer(Modifier.weight(1f))
        Button(onClick = onStart, enabled = !busy, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("开始") }
        TextButton(onClick = onHarder, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            if (busy) SmallProgress() else Text("换个更难的对手")
        }
    }
}

@Composable
private fun ScenarioConversation(
    stage: GenerationStage,
    brief: ScenarioBrief,
    messages: List<ScenarioMessage>,
    options: List<ScenarioReplyOption>,
    achievedGoals: Map<String, Int>,
    goalsExpanded: Boolean,
    onToggleGoals: () -> Unit,
    replyMode: ScenarioReplyMode,
    onReplyModeChange: (ScenarioReplyMode) -> Unit,
    input: String,
    onInputChange: (String) -> Unit,
    hint: String,
    failure: CommunicationFailure?,
    onUseRepair: (String) -> Unit,
    onRewriteRepair: () -> Unit,
    busy: Boolean,
    transcribing: Boolean,
    error: String?,
    readyToFinish: Boolean,
    onSubmit: (String) -> Unit,
    onMic: () -> Unit,
    onFinish: () -> Unit,
) {
    val conversationListState = rememberLazyListState()
    LaunchedEffect(messages.size, busy, failure) {
        val itemCount = messages.size + (if (failure != null) 1 else 0) + (if (busy) 1 else 0)
        if (itemCount > 0) {
            delay(40)
            conversationListState.animateScrollToItem(itemCount - 1)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Surface(onClick = onToggleGoals, color = MaterialTheme.colorScheme.secondaryContainer) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${brief.opponentName} · ${brief.opponentRoleZh}", style = MaterialTheme.typography.titleSmall)
                    Text(brief.titleZh, style = MaterialTheme.typography.bodySmall)
                }
                Text("${achievedGoals.size}/${brief.goals.size}", style = MaterialTheme.typography.labelLarge)
                Icon(if (goalsExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = "展开目标")
            }
        }
        if (goalsExpanded) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    brief.goals.forEach { GoalRow(it.textZh, it.id in achievedGoals, achievedGoals[it.id]) }
                }
            }
        }
        LazyColumn(
            state = conversationListState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(messages) { message -> MessageBubble(message) }
            failure?.let { currentFailure ->
                item {
                    CommunicationFailureCard(
                        failure = currentFailure,
                        onUse = { onUseRepair(currentFailure.suggestedRewriteEn) },
                        onRewrite = onRewriteRepair,
                    )
                }
            }
            if (busy) item {
                FriendlyLoading(
                    title = "对手正在想怎么回…",
                    detail = "同时检查你完成了哪些目标，不会在中途纠错。",
                stage = stage,
                )
            }
        }
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ErrorText(error)
            if (failure == null && !readyToFinish) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(if (replyMode == ScenarioReplyMode.Options) "挑一句说" else "自己说 · 不用怕说错", style = MaterialTheme.typography.labelMedium)
                    TextButton(onClick = { onReplyModeChange(if (replyMode == ScenarioReplyMode.Options) ScenarioReplyMode.Free else ScenarioReplyMode.Options) }) {
                        Icon(if (replyMode == ScenarioReplyMode.Options) Icons.Outlined.Keyboard else Icons.AutoMirrored.Outlined.List, contentDescription = null)
                        Spacer(Modifier.size(4.dp))
                        Text(if (replyMode == ScenarioReplyMode.Options) "自己说" else "给我选项")
                    }
                }
                if (replyMode == ScenarioReplyMode.Options) {
                    Column(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        options.forEach { option ->
                            OutlinedCard(onClick = { onSubmit(option.en) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                    InteractiveEnglishText(
                                        text = option.en,
                                        onSingleTap = { onSubmit(option.en) },
                                    )
                                    Text(option.zh, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = input,
                        onValueChange = onInputChange,
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        placeholder = { Text("用英文接着说") },
                        trailingIcon = {
                            IconButton(onClick = { onSubmit(input) }, enabled = input.isNotBlank() && !busy) {
                                Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = "发送")
                            }
                        },
                    )
                    Row {
                        TextButton(onClick = onMic, enabled = !transcribing && !busy) {
                            if (transcribing) SmallProgress() else Icon(Icons.Outlined.Mic, contentDescription = null)
                            Spacer(Modifier.size(4.dp))
                            Text(if (transcribing) "在听…" else "语音转文字")
                        }
                        TextButton(onClick = { if (input.isBlank()) onInputChange(hint) else onInputChange("$input $hint") }) {
                            Icon(Icons.Outlined.Lightbulb, contentDescription = null)
                            Spacer(Modifier.size(4.dp))
                            Text("半句提示")
                        }
                    }
                }
            }
            if (readyToFinish && failure == null) {
                Text("这段沟通已经可以收尾了。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = onFinish, enabled = !busy, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    if (busy) SmallProgress() else Text("结束并看总结")
                }
            } else if (messages.count { it.speaker == ScenarioSpeaker.User } >= 3 && failure == null) {
                TextButton(onClick = onFinish, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("先演到这里") }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ScenarioMessage) {
    val context = LocalContext.current
    val app = remember { context.applicationContext as LazyDogApplication }
    val scope = rememberCoroutineScope()
    val user = message.speaker == ScenarioSpeaker.User
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        Surface(
            color = if (user) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer,
            contentColor = if (user) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth(0.84f),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    InteractiveEnglishText(
                        text = message.textEn,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = { scope.launch { app.speechController.speak(message.textEn) } },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Outlined.VolumeUp,
                            contentDescription = "朗读这句话",
                            modifier = Modifier.size(19.dp),
                        )
                    }
                }
                if (!user && message.subtextZh.isNotBlank()) {
                    Text(message.subtextZh, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun CommunicationFailureCard(
    failure: CommunicationFailure,
    onUse: () -> Unit,
    onRewrite: () -> Unit,
) {
    OutlinedCard(colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.SyncProblem, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                Spacer(Modifier.size(8.dp))
                Text("他理解反了", fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onErrorContainer)
            }
            Text("他听成了：${failure.heardAsZh}", color = MaterialTheme.colorScheme.onErrorContainer)
            Text(failure.explanationZh, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
            Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium) {
                InteractiveEnglishText(failure.suggestedRewriteEn, Modifier.padding(12.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onUse, modifier = Modifier.weight(1f)) { Text("就这么说") }
                OutlinedButton(onClick = onRewrite, modifier = Modifier.weight(1f)) { Text("我自己重说") }
            }
        }
    }
}

@Composable
private fun ScenarioSummaryView(
    brief: ScenarioBrief,
    summary: ScenarioSummary,
    achievedGoals: Map<String, Int>,
    turnCount: Int,
    busy: Boolean,
    error: String?,
    onReplay: () -> Unit,
    onSave: () -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(summary.outcomeTitleZh, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("${brief.goals.size} 件事做到 ${achievedGoals.size} 件，用了 $turnCount 轮。${summary.overviewZh}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                brief.goals.forEach { goal -> GoalRow(goal.textZh, goal.id in achievedGoals, achievedGoals[goal.id]) }
            }
        }
        Text("最值得改的三个", style = MaterialTheme.typography.titleMedium)
        summary.improvements.forEachIndexed { index, improvement -> ImprovementCard(index + 1, improvement) }
        ErrorText(error)
        Button(onClick = onReplay, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("把关键那几句重说一遍") }
        TextButton(onClick = onSave, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            if (busy) SmallProgress() else Text("收下这 ${summary.keepPhrases.size} 个表达就行")
        }
    }
}

@Composable
private fun ImprovementCard(number: Int, item: ScenarioImprovement) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("$number  ${item.titleZh}", style = MaterialTheme.typography.titleSmall)
            Text("你说的", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            InteractiveEnglishText(
                item.originalEn,
                textDecoration = TextDecoration.LineThrough,
                color = MaterialTheme.colorScheme.error,
            )
            Text("改成", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            InteractiveEnglishText(item.improvedEn, color = MaterialTheme.colorScheme.primary)
            Text("为什么：${item.reasonZh}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ScenarioReplayView(
    improvement: ScenarioImprovement,
    index: Int,
    total: Int,
    input: String,
    onInputChange: (String) -> Unit,
    transcribing: Boolean,
    onMic: () -> Unit,
    onNext: () -> Unit,
    onSkip: () -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("重说关键句", style = MaterialTheme.typography.titleLarge)
            Text("${index + 1} / $total", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium) {
            Text(improvement.replayContextZh, Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        MessageBubble(ScenarioMessage(0, ScenarioSpeaker.Opponent, improvement.opponentLineEn))
        Text("你上次说的", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
        Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
            InteractiveEnglishText(
                improvement.originalEn,
                Modifier.padding(13.dp),
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
        OutlinedTextField(
            value = input,
            onValueChange = onInputChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("这次怎么说") },
            placeholder = { Text(improvement.promptZh) },
            minLines = 3,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            IconButton(onClick = onMic, enabled = !transcribing) {
                if (transcribing) SmallProgress() else Icon(Icons.Outlined.Mic, contentDescription = "语音转文字")
            }
            improvement.phraseHints.forEach { phrase ->
                OutlinedButton(onClick = { onInputChange(listOf(input, phrase).filter { it.isNotBlank() }.joinToString(" ")) }) {
                    Text(phrase, maxLines = 1)
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Button(onClick = onNext, enabled = input.isNotBlank(), modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text(if (index + 1 < total) "下一句" else "收下这些表达")
        }
        TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) { Text("跳过，直接收表达") }
    }
}

@Composable
private fun ScenarioFinishView(summary: ScenarioSummary, savedCount: Int, onDone: () -> Unit, onAgain: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("这些表达以后能直接用", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("已把 $savedCount 个表达排进复习。下次可能在别的场景里让你再用一遍。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        summary.keepPhrases.forEach { phrase ->
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium) {
                Column(Modifier.fillMaxWidth().padding(13.dp)) {
                    InteractiveEnglishText(phrase.en)
                    Text(phrase.zh, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large) {
            Text("同一个场景一周内不会重复。下次会换个处境，或者换一个更难缠的对手。", Modifier.padding(14.dp))
        }
        Spacer(Modifier.weight(1f))
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("收工") }
        TextButton(onClick = onAgain, modifier = Modifier.fillMaxWidth()) { Text("再来一个场景") }
    }
}

@Composable
private fun GoalRow(text: String, achieved: Boolean, turn: Int?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (achieved) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
            contentDescription = if (achieved) "已完成" else "未完成",
            tint = if (achieved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(19.dp),
        )
        Text(text, Modifier.weight(1f).padding(start = 10.dp), style = MaterialTheme.typography.bodyMedium)
        if (turn != null) Text("第 $turn 轮", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DifficultyRow(label: String, level: Int, trailing: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.fillMaxWidth(0.35f), style = MaterialTheme.typography.bodySmall)
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(3) { index ->
                Surface(
                    color = if (index < level) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.weight(1f).height(6.dp),
                ) {}
            }
        }
        Text(trailing, Modifier.padding(start = 10.dp), style = MaterialTheme.typography.labelSmall)
    }
}

private fun difficultyLabel(value: ScenarioDifficulty): String = when {
    value.followUpPressure >= 3 -> "偏难"
    value.followUpPressure == 2 -> "中等"
    else -> "轻松"
}

@Composable
private fun ErrorText(error: String?) {
    if (!error.isNullOrBlank()) {
        Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun FriendlyLoading(
    title: String,
    detail: String,
    modifier: Modifier = Modifier,
    stage: GenerationStage? = null,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.large,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // 上面那句说的是"在做什么"，这句说的是"卡在哪一步、等了多久"——
                // 干等的时候只有后者会动。
                if (stage != null) {
                    Text(
                        text = stageDetail(stage, rememberWaitedSeconds()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SmallProgress() {
    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
}
