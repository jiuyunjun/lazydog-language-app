package com.lazydog.english.feature.settings

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Interests
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.lazydog.english.LazyDogApplication
import com.lazydog.english.core.backup.AutoBackupWorker
import com.lazydog.english.core.data.UserPreferences
import com.lazydog.english.core.model.SampleData
import com.lazydog.english.core.network.AzureSpeechTokenClient
import com.lazydog.english.core.network.OpenAiCompatClient
import com.lazydog.english.core.reminder.StudyReminder
import com.lazydog.english.domain.speaking.SpeechRate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private data class VoiceOption(val label: String, val voice: String)

/** 全部使用 Azure HD（Dragon HD）音色，比上一代 neural 自然明显。 */
private val voiceOptions = listOf(
    VoiceOption("美音 · Ava（女）", "en-US-Ava:DragonHDLatestNeural"),
    VoiceOption("美音 · Andrew（男）", "en-US-Andrew:DragonHDLatestNeural"),
    VoiceOption("美音 · Emma（女）", "en-US-Emma:DragonHDLatestNeural"),
    VoiceOption("英音 · Sonia（女）", "en-GB-Sonia:DragonHDLatestNeural"),
    VoiceOption("英音 · Ryan（男）", "en-GB-Ryan:DragonHDLatestNeural"),
)

private val reminderOptions = listOf("关闭", "08:00", "12:30", "20:00", "21:30")
private val themeOptions = listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色")

private enum class OpenDialog { None, DailyMinutes, MaxNewWords, Goals, Reminder, Theme, Voice, ConfirmRestore }

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    prefs: UserPreferences,
    onStartAssessment: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val dailyMinutes by prefs.dailyMinutes.collectAsState(initial = 12)
    val maxNewWords by prefs.maxNewWords.collectAsState(initial = 5)
    val goal by prefs.learningGoal.collectAsState(initial = "")
    val topics by prefs.topics.collectAsState(initial = emptySet())
    val aiModel by prefs.aiModel.collectAsState(initial = "")
    val speechRegion by prefs.speechRegion.collectAsState(initial = "")
    val speechRate by prefs.speechRate.collectAsState(initial = SpeechRate.Normal)
    val autoRead by prefs.autoReadWords.collectAsState(initial = true)
    val learnerLevel by prefs.learnerLevel.collectAsState(initial = "")
    val levelConfidence by prefs.learnerLevelConfidence.collectAsState(initial = 0)
    val reminderTime by prefs.reminderTime.collectAsState(initial = "")
    val themeMode by prefs.themeMode.collectAsState(initial = "system")
    val ttsVoice by prefs.ttsVoice.collectAsState(initial = UserPreferences.DEFAULT_TTS_VOICE)

    var dialog by rememberSaveable { mutableStateOf(OpenDialog.None) }
    var pendingReminder by remember { mutableStateOf("") }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ ->
        // 没给权限也先记下时间：worker 触发时会再检查，一旦用户在系统里放开就能收到。
        val time = pendingReminder
        scope.launch {
            prefs.setReminderTime(time)
            StudyReminder.schedule(context, time)
        }
    }

    fun applyReminder(option: String) {
        if (option == "关闭") {
            scope.launch {
                prefs.setReminderTime("")
                StudyReminder.cancel(context)
            }
            return
        }
        pendingReminder = option
        if (Build.VERSION.SDK_INT >= 33) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            scope.launch {
                prefs.setReminderTime(option)
                StudyReminder.schedule(context, option)
            }
        }
    }

    var aiTestState by remember { mutableStateOf<String?>(null) }
    var speechTestState by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }

    val goalSummary = buildString {
        append(goal.ifBlank { "未设置" })
        if (topics.isNotEmpty()) {
            append(" · ")
            append(topics.take(2).joinToString("、"))
        }
    }
    val aiSummary = aiTestState ?: "$aiModel · 内置本地配置，点击测试连接"
    val speechSummary = speechTestState ?: "内置本地配置 · $speechRegion · 点击测试连接"

    fun runAiConnectionTest() {
        if (testing) return
        testing = true
        aiTestState = "正在测试连接…"
        scope.launch {
            val client = OpenAiCompatClient(
                baseUrl = prefs.aiBaseUrl.first(),
                apiKey = prefs.aiApiKey.first(),
            )
            val model = prefs.aiModel.first()
            aiTestState = when (val result = client.testConnection(model)) {
                is OpenAiCompatClient.ConnectionResult.Success ->
                    if (result.modelListed) "连接正常，$model 可用"
                    else "连接正常，但模型列表里没有 $model（共 ${result.modelCount} 个）"
                is OpenAiCompatClient.ConnectionResult.Failure ->
                    "连接失败：${result.reason}"
            }
            testing = false
        }
    }

    fun runSpeechConnectionTest() {
        if (testing) return
        testing = true
        speechTestState = "正在测试连接…"
        scope.launch {
            val client = AzureSpeechTokenClient(
                subscriptionKey = prefs.speechKey.first(),
                region = prefs.speechRegion.first(),
            )
            speechTestState = when (val result = client.testConnection()) {
                is AzureSpeechTokenClient.TokenResult.Success -> "连接正常 · $speechRegion"
                is AzureSpeechTokenClient.TokenResult.Failure -> "连接失败：${result.reason}"
            }
            testing = false
        }
    }

    // ---- 数据备份到外部文件夹（重装不丢数据）----

    val app = remember { context.applicationContext as LazyDogApplication }
    val backupFolderUri by prefs.backupFolderUri.collectAsState(initial = "")
    var backupStatus by remember { mutableStateOf<String?>(null) }
    var backupBusy by remember { mutableStateOf(false) }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        scope.launch {
            prefs.setBackupFolderUri(uri.toString())
            AutoBackupWorker.schedule(context)
            backupStatus = "已选好文件夹，之后每天会自动备份一次"
        }
    }

    val backupFolderName = remember(backupFolderUri) {
        if (backupFolderUri.isBlank()) null
        else runCatching { DocumentFile.fromTreeUri(context, Uri.parse(backupFolderUri))?.name }.getOrNull()
    }

    fun runBackupNow() {
        if (backupBusy || backupFolderUri.isBlank()) return
        backupBusy = true
        backupStatus = "正在备份…"
        scope.launch {
            val payload = app.backupRepository.export()
            backupStatus = app.backupFileStore.write(backupFolderUri, payload).fold(
                onSuccess = { "已备份 · ${payload.knowledgeItems.size} 条知识记录" },
                onFailure = { "备份失败：${it.message}" },
            )
            backupBusy = false
        }
    }

    fun runRestoreNow() {
        if (backupBusy || backupFolderUri.isBlank()) return
        backupBusy = true
        backupStatus = "正在恢复…"
        scope.launch {
            app.backupFileStore.read(backupFolderUri).fold(
                onSuccess = { payload ->
                    app.backupRepository.restore(payload)
                    backupStatus = "已恢复 · ${payload.knowledgeItems.size} 条知识记录"
                },
                onFailure = { backupStatus = "恢复失败：${it.message}" },
            )
            backupBusy = false
        }
    }

    val backupSummary = backupStatus ?: when {
        backupFolderUri.isBlank() -> "还没设置 · 点击选一个文件夹"
        else -> "${backupFolderName ?: "已选文件夹"} · 每天自动备份一次"
    }

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
    ) {
        Text(
            text = "设置",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
        )

        SettingsGroupTitle("学习节奏")
        SettingsRow(
            Icons.Outlined.Insights,
            "能力画像",
            if (learnerLevel.isBlank()) "还没测 · 点击开始 5 分钟小测"
            else "$learnerLevel · 置信度 $levelConfidence% · 点击重测",
            onClick = onStartAssessment,
        )
        SettingsRow(
            Icons.Outlined.Timer,
            "每日目标时长",
            "$dailyMinutes 分钟",
            onClick = { dialog = OpenDialog.DailyMinutes },
        )
        SettingsRow(
            Icons.Outlined.AddCircleOutline,
            "每天最多新知识",
            "$maxNewWords 个词 · 1 个语法点",
            onClick = { dialog = OpenDialog.MaxNewWords },
        )
        SettingsRow(
            Icons.Outlined.Interests,
            "学习目标与兴趣",
            goalSummary,
            onClick = { dialog = OpenDialog.Goals },
        )

        SettingsGroupTitle("服务")
        SettingsRow(Icons.Outlined.SmartToy, "AI 服务", aiSummary, onClick = ::runAiConnectionTest)
        SettingsRow(Icons.Outlined.GraphicEq, "Azure Speech", speechSummary, onClick = ::runSpeechConnectionTest)

        SettingsGroupTitle("提醒")
        SettingsRow(
            Icons.Outlined.Notifications,
            "学习提醒",
            if (reminderTime.isBlank()) "关闭" else "每天 $reminderTime 提醒（时间可能有几分钟浮动）",
            onClick = { dialog = OpenDialog.Reminder },
        )

        SettingsGroupTitle("外观与音频")
        SettingsRow(
            Icons.Outlined.Contrast,
            "主题",
            themeOptions.firstOrNull { it.first == themeMode }?.second ?: "跟随系统",
            onClick = { dialog = OpenDialog.Theme },
        )
        SettingsRow(
            Icons.Outlined.RecordVoiceOver,
            "发音口音",
            voiceOptions.firstOrNull { it.voice == ttsVoice }?.label ?: ttsVoice,
            onClick = { dialog = OpenDialog.Voice },
        )
        SettingsRow(
            Icons.Outlined.Speed,
            "朗读语速",
            "${speechRate.label} · 点击切换",
            onClick = { scope.launch { prefs.saveSpeechRate(speechRate.next()) } },
        )
        SettingsRow(
            Icons.AutoMirrored.Outlined.VolumeUp,
            "自动朗读单词",
            if (autoRead) "开 · 单词出现时自动读一遍" else "关 · 想听就点小喇叭",
            onClick = { scope.launch { prefs.setAutoReadWords(!autoRead) } },
        )

        SettingsGroupTitle("数据备份")
        SettingsRow(
            Icons.Outlined.Shield,
            "备份文件夹",
            backupSummary,
            onClick = { folderPicker.launch(null) },
        )
        if (backupFolderUri.isNotBlank()) {
            SettingsRow(
                Icons.Outlined.Shield,
                "立即备份",
                "把当前的知识库和学习偏好写一份到这个文件夹",
                onClick = ::runBackupNow,
            )
            SettingsRow(
                Icons.Outlined.Shield,
                "从这个文件夹恢复",
                "会先清空本机现有数据，再按备份内容重建",
                onClick = { dialog = OpenDialog.ConfirmRestore },
            )
        }
        Text(
            text = "没有账号，也不往云上传东西。备份只存在你选的这个文件夹里，密钥不会被导出。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
        )
    }

    when (dialog) {
        OpenDialog.None -> Unit
        OpenDialog.DailyMinutes -> ChoiceDialog(
            title = "每日目标时长",
            options = listOf(5, 8, 12, 15, 20).map { "$it 分钟" },
            selectedIndex = listOf(5, 8, 12, 15, 20).indexOf(dailyMinutes),
            onSelect = { index ->
                scope.launch { prefs.saveDailyMinutes(listOf(5, 8, 12, 15, 20)[index]) }
                dialog = OpenDialog.None
            },
            onDismiss = { dialog = OpenDialog.None },
        )
        OpenDialog.MaxNewWords -> ChoiceDialog(
            title = "每天最多新词",
            options = listOf(3, 5, 8).map { "$it 个" },
            selectedIndex = listOf(3, 5, 8).indexOf(maxNewWords),
            onSelect = { index ->
                scope.launch { prefs.setMaxNewWords(listOf(3, 5, 8)[index]) }
                dialog = OpenDialog.None
            },
            onDismiss = { dialog = OpenDialog.None },
        )
        OpenDialog.Reminder -> ChoiceDialog(
            title = "学习提醒",
            options = reminderOptions,
            selectedIndex = if (reminderTime.isBlank()) 0 else reminderOptions.indexOf(reminderTime),
            onSelect = { index ->
                applyReminder(reminderOptions[index])
                dialog = OpenDialog.None
            },
            onDismiss = { dialog = OpenDialog.None },
        )
        OpenDialog.Theme -> ChoiceDialog(
            title = "主题",
            options = themeOptions.map { it.second },
            selectedIndex = themeOptions.indexOfFirst { it.first == themeMode },
            onSelect = { index ->
                scope.launch { prefs.setThemeMode(themeOptions[index].first) }
                dialog = OpenDialog.None
            },
            onDismiss = { dialog = OpenDialog.None },
        )
        OpenDialog.Voice -> ChoiceDialog(
            title = "发音口音",
            options = voiceOptions.map { it.label },
            selectedIndex = voiceOptions.indexOfFirst { it.voice == ttsVoice },
            onSelect = { index ->
                scope.launch { prefs.setTtsVoice(voiceOptions[index].voice) }
                dialog = OpenDialog.None
            },
            onDismiss = { dialog = OpenDialog.None },
        )
        OpenDialog.Goals -> GoalsDialog(
            currentGoal = goal,
            currentTopics = topics,
            onConfirm = { newGoal, newTopics ->
                scope.launch { prefs.saveGoalAndTopics(newGoal, newTopics) }
                dialog = OpenDialog.None
            },
            onDismiss = { dialog = OpenDialog.None },
        )
        OpenDialog.ConfirmRestore -> AlertDialog(
            onDismissRequest = { dialog = OpenDialog.None },
            title = { Text("确定要恢复吗？") },
            text = { Text("会先清空这台手机上现有的单词、语法和学习记录，再换成备份文件夹里的内容。这一步做完撤不回去。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        dialog = OpenDialog.None
                        runRestoreNow()
                    },
                ) { Text("清空并恢复") }
            },
            dismissButton = {
                TextButton(onClick = { dialog = OpenDialog.None }) { Text("算了") }
            },
        )
    }
}

@Composable
private fun ChoiceDialog(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEachIndexed { index, option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(index) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = index == selectedIndex, onClick = { onSelect(index) })
                        Text(option, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("算了") }
        },
    )
}

@Composable
private fun GoalsDialog(
    currentGoal: String,
    currentTopics: Set<String>,
    onConfirm: (goal: String, topics: Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var goal by rememberSaveable { mutableStateOf(currentGoal) }
    // Set 不能进 SavedState，旋转丢失可接受。
    var topics by remember { mutableStateOf(currentTopics) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("学习目标与兴趣") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("目标", style = MaterialTheme.typography.labelLarge)
                FlowChips(
                    options = SampleData.goalOptions,
                    isSelected = { it == goal },
                    onToggle = { goal = it },
                )
                Text("兴趣（最多 5 个）", style = MaterialTheme.typography.labelLarge)
                FlowChips(
                    options = SampleData.topicOptions,
                    isSelected = { it in topics },
                    onToggle = {
                        topics = if (it in topics) topics - it
                        else if (topics.size < 5) topics + it else topics
                    },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(goal, topics) },
                enabled = goal.isNotBlank() && topics.isNotEmpty(),
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("算了") }
        },
    )
}

@Composable
private fun FlowChips(
    options: List<String>,
    isSelected: (String) -> Boolean,
    onToggle: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        options.chunked(3).forEach { rowOptions ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowOptions.forEach { option ->
                    FilterChip(
                        selected = isSelected(option),
                        onClick = { onToggle(option) },
                        label = { Text(option) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsGroupTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 2.dp),
    )
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    name: String,
    value: String,
    onClick: (() -> Unit)? = null,
) {
    ListItem(
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
        headlineContent = { Text(name) },
        supportingContent = {
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        leadingContent = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
    )
}
