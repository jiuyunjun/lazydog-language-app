package com.lazydog.english.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.lazydog.english.LazyDogApplication
import com.lazydog.english.core.ai.AiTask
import com.lazydog.english.core.ai.ModelCatalog
import com.lazydog.english.core.data.UserPreferences
import kotlinx.coroutines.launch

/**
 * 「各功能使用的模型」：一个独立页面，不是弹窗。
 *
 * 这里要翻的是一份几十上百行的模型清单，还要在九个功能之间来回比对——浮窗放不下，
 * 滚起来也别扭。所以做成两级正经页面，退出走系统返回。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSettingsScreen(
    prefs: UserPreferences,
    onPick: (AiTask?) -> Unit,
    onExit: () -> Unit,
) {
    val defaultModel by prefs.aiModel.collectAsState(initial = "")
    val overrides by prefs.aiTaskModels.collectAsState(initial = emptyMap())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("各功能使用的模型") },
                navigationIcon = {
                    IconButton(onClick = onExit) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            item {
                ModelRow(
                    name = "默认模型",
                    // 默认模型是其它功能的兜底，单独放在最上面：改它等于一次改掉所有"跟随"的功能。
                    value = defaultModel.ifBlank { "还没设置" },
                    onClick = { onPick(null) },
                )
                HorizontalDivider()
                Text(
                    text = "下面每一项都可以单独指定。没指定的跟随默认模型——默认模型一改，它们跟着改。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
            items(AiTask.entries) { task ->
                ModelRow(
                    name = task.labelZh,
                    value = overrides[task] ?: "跟随默认 · ${task.noteZh}",
                    onClick = { onPick(task) },
                )
            }
        }
    }
}

/**
 * 给某一项挑模型。[task] 为 null 时改的是默认模型。
 *
 * 列表默认只显示看着像对话模型的那些：`/models` 会把生图、嵌入、语音、重排全列出来，
 * 聚合网关能有几百个，选错了要到生成时才报错。筛选只是按名字猜，所以留了「显示全部」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPickScreen(
    prefs: UserPreferences,
    task: AiTask?,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val app = remember { context.applicationContext as LazyDogApplication }
    val scope = rememberCoroutineScope()
    val catalog = app.modelCatalog
    val state by catalog.state.collectAsState()

    val defaultModel by prefs.aiModel.collectAsState(initial = "")
    val overrides by prefs.aiTaskModels.collectAsState(initial = emptyMap())
    val current = if (task == null) defaultModel else overrides[task]

    var showAll by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) { catalog.load() }

    fun choose(model: String?) {
        scope.launch {
            if (task == null) prefs.setAiModel(model.orEmpty()) else prefs.setAiTaskModel(task, model)
            onExit()
        }
    }

    val loaded = state as? ModelCatalog.State.Loaded
    val models = when {
        loaded == null -> emptyList()
        showAll -> loaded.all
        else -> loaded.chat
    }
    val hiddenCount = loaded?.let { it.all.size - it.chat.size } ?: 0

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(task?.labelZh ?: "默认模型") },
                navigationIcon = {
                    IconButton(onClick = onExit) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            item {
                Text(
                    text = task?.noteZh ?: "其它功能没单独指定时用的就是它。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
            // 默认模型这一项没有"跟随"可言，它自己就是被跟随的那个。
            if (task != null) {
                item {
                    ModelOptionRow(
                        label = "跟随默认（${defaultModel.ifBlank { "还没设置" }}）",
                        selected = current == null,
                        onClick = { choose(null) },
                    )
                }
            }
            items(models) { model ->
                ModelOptionRow(label = model, selected = model == current, onClick = { choose(model) })
            }
            // 手改过地址、或者服务端不给列表时，至少让当前这个值还看得见、选得回来。
            if (!current.isNullOrBlank() && current !in models) {
                item {
                    ModelOptionRow(
                        label = "$current（不在列表里）",
                        selected = true,
                        onClick = { choose(current) },
                    )
                }
            }
            item {
                when (val s = state) {
                    is ModelCatalog.State.Loading -> Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.padding(2.dp))
                        Text("正在拉模型列表…", style = MaterialTheme.typography.bodySmall)
                    }
                    is ModelCatalog.State.Failed -> Column(Modifier.padding(16.dp)) {
                        Text(
                            text = "拉不到模型列表：${s.reason}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        TextButton(onClick = { scope.launch { catalog.load(force = true) } }) { Text("重试") }
                    }
                    else -> if (hiddenCount > 0 || showAll) {
                        TextButton(
                            onClick = { showAll = !showAll },
                            modifier = Modifier.padding(horizontal = 8.dp),
                        ) {
                            Text(
                                if (showAll) "只看对话模型" else "显示全部（另有 $hiddenCount 个生图、嵌入、语音等）",
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelRow(name: String, value: String, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(name) },
        supportingContent = {
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
    )
}

@Composable
private fun ModelOptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
