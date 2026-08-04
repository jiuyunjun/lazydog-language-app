package com.lazydog.english.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Interests
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.lazydog.english.core.data.UserPreferences

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    prefs: UserPreferences,
) {
    val dailyMinutes by prefs.dailyMinutes.collectAsState(initial = 12)
    val goal by prefs.learningGoal.collectAsState(initial = "")
    val topics by prefs.topics.collectAsState(initial = emptySet())
    val aiBaseUrl by prefs.aiBaseUrl.collectAsState(initial = "")
    val aiModel by prefs.aiModel.collectAsState(initial = "")

    val goalSummary = buildString {
        append(goal.ifBlank { "未设置" })
        if (topics.isNotEmpty()) {
            append(" · ")
            append(topics.take(2).joinToString("、"))
        }
    }
    val aiSummary = if (aiBaseUrl.isBlank()) "未配置" else "$aiModel · 连接测试待实现"

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
    ) {
        Text(
            text = "设置",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
        )

        SettingsGroupTitle("学习节奏")
        SettingsRow(Icons.Outlined.Timer, "每日目标时长", "$dailyMinutes 分钟")
        SettingsRow(Icons.Outlined.AddCircleOutline, "每天最多新知识", "5 个词 · 1 个语法点")
        SettingsRow(Icons.Outlined.Interests, "学习目标与兴趣", goalSummary)

        SettingsGroupTitle("服务")
        SettingsRow(Icons.Outlined.SmartToy, "AI 服务", aiSummary)
        SettingsRow(Icons.Outlined.GraphicEq, "Azure Speech", "未配置，朗读暂不可用")

        SettingsGroupTitle("提醒")
        SettingsRow(Icons.Outlined.Notifications, "学习提醒", "后续版本提供")

        SettingsGroupTitle("外观与音频")
        SettingsRow(Icons.Outlined.Contrast, "主题", "跟随系统")
        SettingsRow(Icons.Outlined.RecordVoiceOver, "发音口音", "美音")

        SettingsGroupTitle("数据")
        SettingsRow(Icons.Outlined.Shield, "数据与隐私", "导出 / 导入 / 清除 · 后续版本提供")
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
private fun SettingsRow(icon: ImageVector, name: String, value: String) {
    ListItem(
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
