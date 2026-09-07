package com.lazydog.english.feature.pronunciation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CircleNotifications
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.lazydog.english.core.designsystem.LazyDogTheme
import com.lazydog.english.domain.pronunciation.Phoneme
import com.lazydog.english.domain.pronunciation.PronunciationStage
import com.lazydog.english.domain.pronunciation.SkillEstimate

/**
 * 发音模块共用的小组件。
 *
 * 两条口径写在这里，页面只管调用：
 *
 * - **听辨和发音永远分两条显示**，[SkillRow] 一次只画一条，没有任何函数把两个数合成一个。
 * - **样本不够就走灰态**（[SkillRow] 的 `confident` 为 false），并且附一句人话说明为什么。
 */

/** 一条能力线。[label] 只有「听辨」「发音」两种，别在这里发明第三种。 */
@Composable
fun SkillRow(
    label: String,
    estimate: SkillEstimate,
    modifier: Modifier = Modifier,
) {
    val confident = estimate.confident
    val track = MaterialTheme.colorScheme.secondaryContainer
    val bar = if (confident) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }
    val textColor = if (confident) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.outline
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = if (confident) {
                    "$label ${estimate.percent}%，${estimate.sampleCount} 次"
                } else {
                    "$label，只练过 ${estimate.sampleCount} 次，还看不准"
                }
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (confident) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outline,
            modifier = Modifier.width(32.dp),
        )
        LinearProgressIndicator(
            progress = { estimate.score.coerceIn(0f, 1f) },
            color = bar,
            trackColor = track,
            gapSize = 0.dp,
            drawStopIndicator = {},
            modifier = Modifier
                .weight(1f)
                .height(4.dp)
                .clearAndSetSemantics {},
        )
        Text(
            text = "${estimate.percent}%",
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            color = textColor,
            modifier = Modifier
                .width(40.dp)
                .clearAndSetSemantics {},
        )
    }
}

/**
 * 「才练了 N 次，这个数还不作数」。
 *
 * 这句话必须和灰掉的百分比一起出现：只把数字变灰，用户只会以为是样式，
 * 不会知道系统在说「我还不确定」。
 */
@Composable
fun LowConfidenceNote(estimate: SkillEstimate, modifier: Modifier = Modifier) {
    Text(
        text = "才练了 ${estimate.sampleCount} 次，这个数还不作数",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = modifier,
    )
}

/**
 * 阶段的视觉表达。
 *
 * **同时给文字、图标和进度**，颜色不是唯一信息（设计文档 §29.1）：五个阶段里
 * 只有「稳了」用了语义色，其余全靠形状和文字区分，色觉障碍用户照样读得出来。
 */
@Composable
fun stageIcon(stage: PronunciationStage): ImageVector = when (stage) {
    PronunciationStage.Unseen -> Icons.Outlined.RadioButtonUnchecked
    PronunciationStage.Introduced -> Icons.Outlined.CircleNotifications
    PronunciationStage.Discriminating -> Icons.Outlined.VolumeUp
    PronunciationStage.Producing -> Icons.Outlined.Mic
    PronunciationStage.Stable -> Icons.Outlined.Check
}

@Composable
fun stageColor(stage: PronunciationStage): Color = when (stage) {
    PronunciationStage.Unseen -> MaterialTheme.colorScheme.outline
    PronunciationStage.Stable -> LazyDogTheme.extendedColors.correct
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
fun StageLabel(stage: PronunciationStage, modifier: Modifier = Modifier) {
    val color = stageColor(stage)
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(
            imageVector = stageIcon(stage),
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(14.dp),
        )
        Text(text = stage.labelZh, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

/**
 * 音标符号。
 *
 * TalkBack 念 `/ɪ/` 会念成一串乱码，所以每个音标都得带一句能念的语义标签
 * （设计文档 §29.1 的无障碍要求）。
 */
@Composable
fun IpaText(
    ipa: String,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.headlineSmall,
    color: Color = MaterialTheme.colorScheme.onSurface,
    spokenName: String? = null,
    modifier: Modifier = Modifier,
) {
    Text(
        text = "/$ipa/",
        style = style,
        fontFamily = FontFamily.Monospace,
        color = color,
        modifier = modifier.semantics {
            contentDescription = spokenName?.let { "音标 $it" } ?: "音标 $ipa"
        },
    )
}

/** 一个音位对的显示名，`/ɪ/ ↔ /iː/`。 */
fun contrastLabel(left: Phoneme?, right: Phoneme?): String =
    "/${left?.ipa ?: "?"}/ ↔ /${right?.ipa ?: "?"}/"

@Composable
fun SectionSpacer(height: Int = 8) {
    Spacer(Modifier.padding(top = height.dp))
}

@Composable
fun SectionTitle(text: String, trailing: String? = null, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            Text(
                text = trailing,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
fun ColumnScopeSpacer() = Spacer(Modifier.height(4.dp))
