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
 * 「各功能使用的模型」的第一级：默认一行 + 九个功能各一行。
 *
 * 每一行点进去都是同一种页面（[ModelPickScreen]），里面能配这个功能的两件事：用哪个模型、
 * 思考多久。默认那行配的是"没单独设过的功能"用什么——改它等于一次改掉所有跟随的功能。
 *
 * 做成独立页面而不是弹窗：要翻的是几十上百行的模型清单，还要在九个功能之间来回比对，
 * 浮窗放不下也滚不顺。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSettingsScreen(
    prefs: UserPreferences,
    onPick: (AiTask?) -> Unit,
    onExit: () -> Unit,
) {
    val defaultModel by prefs.aiModel.collectAsState(initial = "")
    val defaultEffort by prefs.defaultEffort.collectAsState(initial = AiTask.DEFAULT_EFFORT)
    val models by prefs.aiTaskModels.collectAsState(initial = emptyMap())
    val efforts by prefs.aiTaskEfforts.collectAsState(initial = emptyMap())

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
                SettingRow(
                    name = "默认",
                    value = summaryLine(
                        model = defaultModel.ifBlank { "还没设置" },
                        effort = effortText(defaultEffort),
                    ),
                    onClick = { onPick(null) },
                )
                HorizontalDivider()
                Text(
                    text = "下面每一项都可以单独指定模型和思考力度。没指定的跟随默认——默认一改，它们跟着改。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
            items(AiTask.entries) { task ->
                SettingRow(
                    name = task.labelZh,
                    value = summaryLine(
                        model = models[task] ?: "跟随默认",
                        effort = efforts[task]?.let(::effortText)
                            ?: "${defaultEffortText(defaultEffort, task)}（跟随默认）",
                    ),
                    onClick = { onPick(task) },
                )
            }
        }
    }
}

/**
 * 配一个功能：用哪个模型、思考多久。[task] 为 null 时配的是默认。
 *
 * 两件事放同一页，是因为它们回答的是同一个问题——"这个功能怎么跑"；分成两级页面
 * 就要为了改一个力度翻两层。选中即生效，不自动退出：自动退出会让"选模型"和"选力度"
 * 一个跳走一个不跳，用起来莫名其妙。
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
    val defaultEffort by prefs.defaultEffort.collectAsState(initial = AiTask.DEFAULT_EFFORT)
    val models by prefs.aiTaskModels.collectAsState(initial = emptyMap())
    val efforts by prefs.aiTaskEfforts.collectAsState(initial = emptyMap())

    val currentModel = if (task == null) defaultModel.ifBlank { null } else models[task]
    /** 默认那一项永远有值（没设过就是 [AiTask.DEFAULT_EFFORT]）；功能项没设过是 null，即"跟随默认"。 */
    val currentEffort = if (task == null) defaultEffort else efforts[task]
    /** 这个功能实际会用的模型——力度支不支持要按它来判断。 */
    val effectiveModel = currentModel ?: defaultModel

    val unsupported by prefs.noReasoningEffortModels.collectAsState(initial = emptySet())
    val rejected by prefs.rejectedEfforts(effectiveModel).collectAsState(initial = emptySet())
    val modelIgnoresEffort = effectiveModel.isNotBlank() && effectiveModel in unsupported

    var showAll by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { catalog.load() }

    val loaded = state as? ModelCatalog.State.Loaded
    val modelList = when {
        loaded == null -> emptyList()
        showAll -> loaded.all
        else -> loaded.chat
    }
    val hiddenCount = loaded?.let { it.all.size - it.chat.size } ?: 0

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(task?.labelZh ?: "默认") },
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
                    text = task?.noteZh ?: "没单独设过的功能，用的就是这里配的模型和力度。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }

            // ---- 模型 ----
            item { SectionTitle("模型") }
            // 默认那一项没有"跟随"可言，它自己就是被跟随的那个。
            if (task != null) {
                item {
                    OptionRow(
                        label = "跟随默认（${defaultModel.ifBlank { "还没设置" }}）",
                        selected = currentModel == null,
                        onClick = { scope.launch { prefs.setAiTaskModel(task, null) } },
                    )
                }
            }
            items(modelList) { model ->
                OptionRow(
                    label = model,
                    selected = model == currentModel,
                    onClick = {
                        scope.launch {
                            if (task == null) prefs.setAiModel(model) else prefs.setAiTaskModel(task, model)
                        }
                    },
                )
            }
            // 手改过地址、或者服务端不给列表时，至少让当前这个值还看得见、选得回来。
            if (currentModel != null && currentModel !in modelList) {
                item {
                    OptionRow(
                        label = "$currentModel（不在列表里）",
                        selected = true,
                        onClick = {},
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

            // ---- 思考力度 ----
            item { SectionTitle("思考力度") }
            item {
                Text(
                    text = if (modelIgnoresEffort) {
                        "$effectiveModel 不认这个参数（试过一次被拒了），这里选什么都不会发出去。"
                    } else {
                        "推理模型开口前会先想一阵，这段实测能占掉整次调用的大半。" +
                            "取值随模型而异，选了不支持的会自动往下退，不会因此报错。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (modelIgnoresEffort) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            item {
                OptionRow(
                    label = if (task == null) {
                        "各功能用自己的推荐值（听力 low、点词 none…）"
                    } else {
                        "跟随默认（${defaultEffortText(defaultEffort, task)}）"
                    },
                    selected = if (task == null) currentEffort == AiTask.PER_TASK else currentEffort == null,
                    enabled = !modelIgnoresEffort,
                    onClick = {
                        scope.launch {
                            if (task == null) {
                                prefs.setDefaultEffort(AiTask.PER_TASK)
                            } else {
                                prefs.setAiTaskEffort(task, null)
                            }
                        }
                    },
                )
            }
            items(AiTask.ALL_EFFORTS) { effort ->
                OptionRow(
                    // 被这个模型拒过的取值标出来：不是猜的，是真发过一次被顶回来了。
                    label = "$effort${effortNote(effort)}" + if (effort in rejected) " · 这个模型不支持" else "",
                    selected = currentEffort == effort,
                    enabled = !modelIgnoresEffort && effort !in rejected,
                    onClick = {
                        scope.launch {
                            if (task == null) prefs.setDefaultEffort(effort) else prefs.setAiTaskEffort(task, effort)
                        }
                    },
                )
            }
            item {
                OptionRow(
                    label = "${AiTask.MODEL_DEFAULT_LABEL}（不发这个参数）",
                    selected = currentEffort == AiTask.MODEL_DEFAULT,
                    enabled = !modelIgnoresEffort,
                    onClick = {
                        scope.launch {
                            if (task == null) {
                                prefs.setDefaultEffort(AiTask.MODEL_DEFAULT)
                            } else {
                                prefs.setAiTaskEffort(task, AiTask.MODEL_DEFAULT)
                            }
                        }
                    },
                )
            }
        }
    }
}

private fun summaryLine(model: String, effort: String): String = "$model · 思考 $effort"

private fun effortText(stored: String?): String = when (stored) {
    null -> "推荐值"
    AiTask.MODEL_DEFAULT -> AiTask.MODEL_DEFAULT_LABEL
    AiTask.PER_TASK -> "各功能的推荐值"
    else -> stored
}

/** 某个功能"跟随默认"时实际会用到的力度。默认那一项选了"各功能推荐"时，落到这个功能自己的推荐值。 */
private fun defaultEffortText(defaultEffort: String, task: AiTask): String =
    if (defaultEffort == AiTask.PER_TASK) {
        "${effortText(task.reasoningEffort)}（推荐）"
    } else {
        effortText(defaultEffort)
    }

private fun effortNote(effort: String): String = when (effort) {
    "none" -> "（不思考，最快）"
    "minimal" -> "（几乎不思考）"
    "low" -> "（少想一会儿）"
    "medium" -> "（多数模型的默认）"
    "high", "xhigh", "max" -> "（更慢，换质量）"
    else -> ""
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun SettingRow(name: String, value: String, onClick: () -> Unit) {
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
private fun OptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val color = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, enabled = enabled, onClick = onClick)
        Text(label, style = MaterialTheme.typography.bodyLarge, color = color)
    }
}
