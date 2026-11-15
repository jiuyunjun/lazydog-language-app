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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.lazydog.english.core.speech.PlaybackSource
import com.lazydog.english.core.speech.PlaybackState
import com.lazydog.english.core.speech.PlaybackStatus
import com.lazydog.english.core.speech.SpeechController
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
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
 * **不要给音标设 `FontFamily.Monospace`。** 设计稿里音标是 Roboto Mono，那是因为浏览器
 * 从 Google Fonts 拉了一份带 IPA 的字体；Android 系统的等宽字体（Droid Sans Mono）
 * 没有 IPA 扩展和修饰符那两个区段，`ː` `ɪ` `θ` `ŋ` `ʃ` 在真机上全是豆腐块。
 * 词卡那边的音标一直用的就是默认字体，这里照做。
 *
 * 等宽只留给**纯数字**（百分比、分数）——那里的理由是列对齐，而数字等宽字体是有的。
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
        color = color,
        modifier = modifier.semantics {
            contentDescription = spokenName?.let { "音标 $it" } ?: "音标 $ipa"
        },
    )
}

/**
 * 连着放几个词，一个放完再放下一个。
 *
 * 播放器本身不排队（`语音服务DESIGN.md` §12：点别的 = 顶掉当前这段），而「对比着听」
 * 要的恰恰是两个词挨着出来。所以排队放在这里：盯着全局播放状态，等上一个落回空闲再放下一个。
 *
 * **被别的播放顶掉、或者放失败了，就整串停下**——用户中途点了别的词，后面那个不该再冒出来
 * 盖掉他刚点的。调用方换题或离开时取消这个协程即可。
 */
suspend fun SpeechController.playInTurn(words: List<String>, gapMillis: Long = 350L) {
    words.filter { it.isNotBlank() }.forEachIndexed { index, word ->
        if (index > 0) delay(gapMillis)
        val source = PlaybackSource.word(word)
        play(source)
        withTimeoutOrNull(8_000L) {
            playback.first { it.sourceId == source.id && it.status != PlaybackStatus.Error }
        } ?: return
        val end = withTimeoutOrNull(15_000L) {
            playback.first { it.sourceId != source.id || it.status == PlaybackStatus.Error }
        } ?: return
        if (end.sourceId != null) return
    }
}

/**
 * 「听这个词」的小喇叭。状态一律来自全局播放状态，按钮自己不存 `isPlaying`
 * （`语音服务DESIGN.md` §23）；连播时轮到哪个词，哪个喇叭亮，用户才知道现在响的是哪一个。
 */
@Composable
fun WordPlayButton(
    word: String,
    playback: PlaybackState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val status = playback.statusOf(PlaybackSource.word(word).id)
    IconButton(onClick = onClick, modifier = modifier) {
        if (status == PlaybackStatus.Loading) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            Icon(
                imageVector = Icons.Outlined.VolumeUp,
                contentDescription = "听 $word",
                tint = if (status == PlaybackStatus.Playing) MaterialTheme.colorScheme.primary else tint,
            )
        }
    }
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
