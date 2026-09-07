package com.lazydog.english.feature.pronunciation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.lazydog.english.LazyDogApplication
import com.lazydog.english.domain.pronunciation.Phoneme
import com.lazydog.english.domain.pronunciation.PhonemeCategory
import com.lazydog.english.domain.pronunciation.PhonemeContrast
import com.lazydog.english.domain.pronunciation.PronunciationProgress
import com.lazydog.english.domain.pronunciation.PronunciationStage
import com.lazydog.english.domain.pronunciation.PronunciationTarget

/**
 * 全部声音（`发音与音标学习DESIGN.md` §29，设计稿 `design/pronunciation/AllSounds.dc.html`）。
 *
 * 刻意**不做成学校那张密集音标表，也不当模块首页**：那张表的问题是它把「背完 48 个符号」
 * 摆成了目标。这里是一个查阅入口，按元音 / 辅音 / 对比分三段，每一格显示的是
 * 「你在这个音上到哪一步了」，不是「这个符号你背没背」。
 */
private enum class SoundTab(val labelZh: String) { Vowels("元音"), Consonants("辅音"), Contrasts("对比") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AllSoundsScreen(
    onExit: () -> Unit,
    onOpenSound: (String) -> Unit,
    onPracticeContrast: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val app = remember { context.applicationContext as LazyDogApplication }
    val catalog = remember { app.phonemeCatalog }
    val progressList by app.pronunciationRepository.progress.collectAsState(initial = emptyList())
    val progressByTarget = remember(progressList) { progressList.associateBy { it.targetId } }
    var tab by rememberSaveable { mutableStateOf(SoundTab.Vowels) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("全部声音") },
                navigationIcon = {
                    IconButton(onClick = onExit) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        if (catalog.isEmpty) {
            CatalogUnavailable(Modifier.padding(padding))
            return@Scaffold
        }
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                SoundTab.entries.forEachIndexed { index, entry ->
                    SegmentedButton(
                        selected = tab == entry,
                        onClick = { tab = entry },
                        shape = SegmentedButtonDefaults.itemShape(index, SoundTab.entries.size),
                    ) { Text(entry.labelZh) }
                }
            }

            LazyColumn(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    bottom = 32.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (tab) {
                    SoundTab.Contrasts -> {
                        items(catalog.contrasts, key = { it.id }) { contrast ->
                            ContrastRow(
                                contrast = contrast,
                                catalog = catalog,
                                progress = progressByTarget[PronunciationTarget.ofContrast(contrast.id)],
                                onClick = { onPracticeContrast(contrast.id) },
                            )
                        }
                    }

                    else -> {
                        val category = if (tab == SoundTab.Vowels) {
                            PhonemeCategory.Vowel
                        } else {
                            PhonemeCategory.Consonant
                        }
                        val groups = catalog.phonemes
                            .filter { it.category == category }
                            .groupBy { it.groupZh }
                        groups.forEach { (group, phonemes) ->
                            item(key = "group-$group") {
                                val stable = phonemes.count {
                                    progressByTarget[PronunciationTarget.ofPhoneme(it.id)]?.stage ==
                                        PronunciationStage.Stable
                                }
                                SectionTitle(
                                    text = group,
                                    trailing = "${phonemes.size} 个 · 稳了 $stable",
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                            items(phonemes.chunked(3), key = { it.first().id }) { row ->
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    row.forEach { phoneme ->
                                        PhonemeTile(
                                            phoneme = phoneme,
                                            progress = progressByTarget[
                                                PronunciationTarget.ofPhoneme(phoneme.id),
                                            ],
                                            onClick = { onOpenSound(phoneme.id) },
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                        }
                    }
                }

                item(key = "legend") { StageLegend(Modifier.padding(top = 12.dp)) }
            }
        }
    }
}

@Composable
private fun PhonemeTile(
    phoneme: Phoneme,
    progress: PronunciationProgress?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val stage = progress?.stage ?: PronunciationStage.Unseen
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = if (stage == PronunciationStage.Unseen) {
            MaterialTheme.colorScheme.surface
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        border = if (stage == PronunciationStage.Unseen) {
            androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        } else {
            null
        },
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            IpaText(
                ipa = phoneme.ipa,
                style = MaterialTheme.typography.titleLarge,
                color = if (stage == PronunciationStage.Unseen) {
                    MaterialTheme.colorScheme.outline
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                spokenName = phoneme.shortDescriptionZh,
            )
            StageLabel(stage)
            if (progress != null && stage != PronunciationStage.Unseen) {
                // 两条极细的进度并排：一眼能看出「听得出但读不准」，不需要读数字。
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    MiniBar(progress.perception.score, progress.perception.confident, Modifier.weight(1f))
                    MiniBar(progress.production.score, progress.production.confident, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun MiniBar(value: Float, confident: Boolean, modifier: Modifier = Modifier) {
    androidx.compose.material3.LinearProgressIndicator(
        progress = { value.coerceIn(0f, 1f) },
        color = if (confident) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.outlineVariant
        },
        trackColor = MaterialTheme.colorScheme.secondaryContainer,
        gapSize = 0.dp,
        drawStopIndicator = {},
        modifier = modifier.height(3.dp),
    )
}

@Composable
private fun ContrastRow(
    contrast: PhonemeContrast,
    catalog: com.lazydog.english.domain.pronunciation.PhonemeCatalog,
    progress: PronunciationProgress?,
    onClick: () -> Unit,
) {
    OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = contrastLabel(
                        catalog.phoneme(contrast.leftPhonemeId),
                        catalog.phoneme(contrast.rightPhonemeId),
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                StageLabel(progress?.stage ?: PronunciationStage.Unseen)
            }
            Text(
                text = contrast.minimalPairs.take(3)
                    .joinToString(" · ") { "${it.leftWord} / ${it.rightWord}" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (progress != null && progress.perception.hasEvidence) {
                SkillRow("听辨", progress.perception)
                if (!progress.perception.confident) LowConfidenceNote(progress.perception)
            }
        }
    }
}

/**
 * 状态图例。
 *
 * 最后那句「只是这个模块内的状态」不是客套：本模块不接全局 Mastery（设计文档 §19），
 * 不说明白的话用户会以为「稳了」等于这个词/这个音在整个 App 里已经掌握。
 */
@Composable
private fun StageLegend(modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("五种状态怎么读", style = MaterialTheme.typography.titleSmall)
            listOf(
                PronunciationStage.Unseen to "没见过这个音",
                PronunciationStage.Introduced to "看过音位卡，知道大概什么声",
                PronunciationStage.Discriminating to "在最小对立里稳定分得出",
                PronunciationStage.Producing to "多个词里能发出来，还不稳",
                PronunciationStage.Stable to "换词、换人、进句子都保持得住",
            ).forEach { (stage, note) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = stageIcon(stage),
                        contentDescription = null,
                        tint = stageColor(stage),
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = stage.labelZh,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.width(72.dp),
                    )
                    Text(
                        text = note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
            Text(
                text = "状态同时用文字、图标和进度表达，不靠颜色区分。" +
                    "「稳了」只是这个模块内的状态，不代表全局掌握。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/** 音位表读不出来。只该让这一个模块进不去，不该把 App 拖垮。 */
@Composable
fun CatalogUnavailable(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("音位表读不出来", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "发音模块的音位表随安装包一起走，这次没读到。重装一次应该就好了，" +
                "App 的其他部分不受影响。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
