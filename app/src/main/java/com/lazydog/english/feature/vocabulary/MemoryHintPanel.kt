package com.lazydog.english.feature.vocabulary

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.lazydog.english.LazyDogApplication
import com.lazydog.english.core.designsystem.InteractiveEnglishText
import com.lazydog.english.core.designsystem.LazyDogTheme
import com.lazydog.english.domain.generation.GenerationResult
import com.lazydog.english.domain.generation.GenerationStage
import com.lazydog.english.domain.generation.MemoryAssistance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 生成一条提示时页面处在哪一步。存着的提示本身来自数据库，不在这里维护。 */
private sealed interface HintPhase {
    data object Idle : HintPhase
    data class Generating(val stage: GenerationStage, val partialHook: String) : HintPhase
    data class Failed(val reason: String) : HintPhase
}

/**
 * 一个词的记忆提示卡（词汇记忆提示DESIGN.md §7）。
 *
 * 首屏只显示核心意思、记忆钩子和这条提示用的是哪种记忆方式——展开的那些内容一次性铺出来，
 * 用户读到第三块就已经不在记这个词了。其余的收在「更多记忆提示」里。
 *
 * [fallbackHintZh] 是老的一句话记忆方法（vocabulary_details.memoryHintZh）。它先顶着，
 * 直到用户真的为这个词要一条结构化提示；两者不叠着显示，同一件事说两遍只会打架。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MemoryHintPanel(
    itemId: Long,
    modifier: Modifier = Modifier,
    fallbackHintZh: String = "",
) {
    val context = LocalContext.current
    val app = remember { context.applicationContext as LazyDogApplication }
    val scope = rememberCoroutineScope()
    val repository = app.memoryHintRepository

    // 记住这条 Flow：每次重组都新建一个，就是每次重组都重订一次数据库查询。
    val hintFlow = remember(itemId) { repository.observe(itemId) }
    val hint by hintFlow.collectAsState(initial = null)
    var phase by remember(itemId) { mutableStateOf<HintPhase>(HintPhase.Idle) }
    var expanded by remember(itemId) { mutableStateOf(false) }

    fun request(regenerate: Boolean) {
        phase = HintPhase.Generating(GenerationStage.Connecting, "")
        scope.launch {
            val result = repository.generate(
                itemId = itemId,
                learnerLevel = app.userPreferences.vocabLevelDescription.first(),
                regenerate = regenerate,
                onStage = { stage ->
                    phase = (phase as? HintPhase.Generating)?.copy(stage = stage) ?: HintPhase.Generating(stage, "")
                },
                onPartialHook = { text ->
                    phase = (phase as? HintPhase.Generating)?.copy(partialHook = text) ?: phase
                },
            )
            phase = when (result) {
                is GenerationResult.Success -> {
                    // 新的一条来了就收起细节：先让人看见那句钩子。
                    expanded = false
                    HintPhase.Idle
                }
                is GenerationResult.Failure -> HintPhase.Failed(result.reason)
            }
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val current = hint
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Lightbulb,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    text = "怎么记",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.weight(1f),
                )
                if (current != null) StrategyChips(current)
            }

            when (val p = phase) {
                is HintPhase.Generating -> GeneratingRow(p)
                is HintPhase.Failed -> Text(
                    text = "没拿到提示：${p.reason}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                HintPhase.Idle -> Unit
            }

            if (current != null) {
                // 这几段里夹的英文（词根、易混词、搭配）正是最该能点开查的东西。
                InteractiveEnglishText(
                    text = current.memoryHookZh,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                if (current.coreMeaningZh.isNotBlank()) {
                    InteractiveEnglishText(
                        text = current.coreMeaningZh,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                AnimatedVisibility(visible = expanded) { MemoryHintDetails(current) }
            } else if (fallbackHintZh.isNotBlank()) {
                InteractiveEnglishText(
                    text = fallbackHintZh,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            } else if (phase == HintPhase.Idle) {
                Text(
                    text = "还没给这个词找过记忆的角度。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }

            val busy = phase is HintPhase.Generating
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (current != null && current.hasDetails) {
                    TextButton(onClick = { expanded = !expanded }) {
                        Icon(
                            imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                            contentDescription = null,
                        )
                        Text(
                            text = if (expanded) "收起" else "更多记忆提示",
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
                if (!busy) {
                    TextButton(onClick = { request(regenerate = current != null) }) {
                        Icon(
                            imageVector = if (current == null) Icons.Outlined.AutoAwesome else Icons.Outlined.Refresh,
                            contentDescription = null,
                        )
                        Text(
                            // 已经有一条时是「换一条」而不是「再生成」：请求里会带上旧钩子，
                            // 明确要求换个角度，不然多半只是把同一句话重新措辞。
                            text = when {
                                current != null -> "换一条"
                                phase is HintPhase.Failed -> "再试一次"
                                else -> "生成记忆提示"
                            },
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GeneratingRow(phase: HintPhase.Generating) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        Text(
            // 钩子一到就显示钩子；还没到就说清楚现在卡在哪一步——
            // "模型在想"和"没接通"用户该做的反应不一样。
            text = phase.partialHook.ifBlank {
                when (val s = phase.stage) {
                    GenerationStage.Connecting -> "正在接通…"
                    is GenerationStage.Thinking -> "模型正在想这个词该怎么记…"
                    is GenerationStage.Writing -> "正在写…（${s.chars} 字）"
                }
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

/** 这条提示用的是哪一两种记忆方式（§4：主策略 1 个，次策略 0~1 个）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StrategyChips(hint: MemoryAssistance) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOfNotNull(hint.primaryType, hint.secondaryType).forEach { type ->
            Surface(
                color = MaterialTheme.colorScheme.secondary,
                shape = MaterialTheme.shapes.extraSmall,
            ) {
                Text(
                    text = type.labelZh,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondary,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}

/** 展开后的内容。每一块都可能是空的——§10 的"宁缺毋滥"就长这样，空了就不占位置。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MemoryHintDetails(hint: MemoryAssistance) {
    val extended = LazyDogTheme.extendedColors
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp)) {
        if (hint.morphologyZh.isNotBlank()) {
            DetailBlock("构词") {
                // 构词几乎全是英文词根，点开查的需求最强。
                InteractiveEnglishText(
                    text = hint.morphologyZh,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
        if (hint.weakSegment.isNotBlank() || hint.commonErrors.isNotEmpty()) {
            DetailBlock("拼写") {
                if (hint.weakSegment.isNotBlank()) {
                    Text(
                        text = "最容易错的一段：${hint.weakSegment}",
                        style = MaterialTheme.typography.bodySmall,
                        color = extended.attention,
                    )
                }
                if (hint.commonErrors.isNotEmpty()) {
                    Text(
                        text = "别写成：${hint.commonErrors.joinToString("、")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        }
        if (!hint.pronunciation.isEmpty) {
            DetailBlock("发音") {
                if (hint.pronunciation.syllables.isNotEmpty()) {
                    Text(
                        // 重音那一节标出来，其余按顺序摆着；stress 为 0 表示没给或给错了。
                        text = hint.pronunciation.syllables.mapIndexed { index, syllable ->
                            if (index + 1 == hint.pronunciation.stress) "ˈ$syllable" else syllable
                        }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                if (hint.pronunciation.noteZh.isNotBlank()) {
                    InteractiveEnglishText(
                        text = hint.pronunciation.noteZh,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        }
        if (hint.visualAssociationZh.isNotBlank()) {
            DetailBlock("画面") {
                Text(
                    text = hint.visualAssociationZh,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
        if (hint.confusions.isNotEmpty()) {
            DetailBlock("别和它们弄混") {
                hint.confusions.forEach { confusion ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        InteractiveEnglishText(
                            text = confusion.word,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Text(
                            text = confusion.differenceZh,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }
        }
        if (hint.collocations.isNotEmpty()) {
            DetailBlock("常一起出现") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    hint.collocations.forEach { phrase ->
                        InteractiveEnglishText(
                            text = phrase,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }
        }
        if (hint.exampleEn.isNotBlank()) {
            DetailBlock("典型用法") {
                InteractiveEnglishText(
                    text = hint.exampleEn,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
        if (hint.recallQuestionZh.isNotBlank()) {
            DetailBlock("回头自测") {
                Text(
                    text = hint.recallQuestionZh,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

@Composable
private fun DetailBlock(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
        )
        content()
    }
}
