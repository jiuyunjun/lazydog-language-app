package com.lazydog.english.feature.pronunciation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.lazydog.english.LazyDogApplication
import com.lazydog.english.core.designsystem.LazyDogTheme
import com.lazydog.english.core.speech.PlaybackSource
import com.lazydog.english.core.speech.PlaybackStatus
import com.lazydog.english.domain.pronunciation.CommonError
import com.lazydog.english.domain.pronunciation.Phoneme
import com.lazydog.english.domain.pronunciation.PhonemeContrast
import com.lazydog.english.domain.pronunciation.PronunciationProgress
import com.lazydog.english.domain.pronunciation.PronunciationStage
import com.lazydog.english.domain.pronunciation.PronunciationTarget

/**
 * 音位卡（`发音与音标学习DESIGN.md` §8、§30，设计稿 `design/pronunciation/SoundCard.dc.html`）。
 *
 * 顺序是声音 → 动作 → 常见误读 → 对比 → 例词 → 你的表现，符号只是标题：
 * **Sound first, symbol second**（§4.1）。整页只有一个主按钮「开始练习」。
 *
 * **没有孤立音位的音频。** 这一版的播放按钮念的是代表例词，界面上也照直说
 * （「在 sit 里听这个音」）。孤立元音的 TTS 合成质量不可靠，设计文档 §12.1 本来就允许
 * 单音只给示范而不强求；宁可少一个功能，也不要给用户一段不像那个音的示范音。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SoundCardScreen(
    phonemeId: String,
    onExit: () -> Unit,
    onPractice: (String) -> Unit,
    onProduce: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val app = remember { context.applicationContext as LazyDogApplication }
    val catalog = remember { app.phonemeCatalog }
    val phoneme = remember(phonemeId) { catalog.phoneme(phonemeId) }
    val targetId = remember(phonemeId) { PronunciationTarget.ofPhoneme(phonemeId) }
    val progressList by app.pronunciationRepository.progress.collectAsState(initial = emptyList())
    val progress = remember(progressList, targetId) {
        progressList.firstOrNull { it.targetId == targetId }
            ?: PronunciationProgress(targetId = targetId)
    }
    val playback by app.speechController.playback.collectAsState()

    // 打开卡片就算「认识了」：这一步只推进阶段，不写任何作答，看一眼不是一次练习。
    LaunchedEffect(targetId) { app.pronunciationRepository.markCardSeen(targetId) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    phoneme?.let {
                        IpaText(
                            ipa = it.ipa,
                            style = MaterialTheme.typography.titleLarge,
                            spokenName = it.shortDescriptionZh,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onExit) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (progress.stage != PronunciationStage.Unseen) {
                        StageLabel(progress.stage, Modifier.padding(end = 16.dp))
                    }
                },
            )
        },
        bottomBar = {
            if (phoneme != null) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        // 一个主按钮：先听辨。跟读是次操作——听不出来的时候练发音，
                        // 用户没有可以对照的目标（设计文档 §4.1）。
                        Button(
                            onClick = { onPractice(phonemeId) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("开始练习") }
                        androidx.compose.material3.TextButton(
                            onClick = { onProduce(phonemeId) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("跟读这个音") }
                    }
                }
            }
        },
    ) { padding ->
        if (phoneme == null) {
            CatalogUnavailable(Modifier.padding(padding))
            return@Scaffold
        }
        val headline = phoneme.exampleWords.firstOrNull()?.word
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                FilledIconButton(
                    onClick = {
                        headline?.let { app.speechController.onPlayClicked(PlaybackSource.word(it)) }
                    },
                    enabled = headline != null,
                    modifier = Modifier.size(72.dp),
                ) {
                    val status = headline?.let { playback.statusOf(PlaybackSource.word(it).id) }
                    if (status == PlaybackStatus.Loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Icon(
                            Icons.AutoMirrored.Outlined.VolumeUp,
                            contentDescription = headline?.let { "在 $it 里听这个音" } ?: "听这个音",
                            modifier = Modifier.size(32.dp),
                        )
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(phoneme.shortDescriptionZh, style = MaterialTheme.typography.titleLarge)
                    Text(
                        text = headline?.let { "在 $it 里听这个音 · ${phoneme.category.labelZh} · 美音" }
                            ?: "${phoneme.category.labelZh} · 美音",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Articulation(phoneme)

            phoneme.commonErrors.takeIf { it.isNotEmpty() }?.let { CommonErrors(it) }

            val contrasts = remember(phonemeId) { catalog.contrastsOf(phonemeId) }
            if (contrasts.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionTitle("和它最容易混的")
                    contrasts.forEach { contrast ->
                        ContrastCard(contrast, catalog.phoneme(contrast.leftPhonemeId), catalog.phoneme(contrast.rightPhonemeId))
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionTitle("例词 · 点一下听")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    phoneme.exampleWords.forEach { example ->
                        AssistChip(
                            onClick = {
                                app.speechController.onPlayClicked(PlaybackSource.word(example.word))
                            },
                            label = { Text(example.word) },
                            leadingIcon = {
                                Icon(
                                    Icons.AutoMirrored.Outlined.VolumeUp,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                        )
                    }
                }
            }

            Performance(progress)
        }
    }
}

@Composable
private fun Articulation(phoneme: Phoneme) {
    val guide = phoneme.articulation
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("嘴里发生了什么", style = MaterialTheme.typography.titleSmall)
            listOf(
                "舌头" to guide.tongueZh,
                "嘴唇" to guide.lipsZh,
                "下巴" to guide.jawZh,
                "气流" to guide.airflowZh,
                "声带" to guide.voicingZh,
            ).filter { it.second.isNotBlank() }.forEach { (label, value) ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.width(40.dp),
                    )
                    Text(text = value, style = MaterialTheme.typography.bodySmall)
                }
            }
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    text = "关键动作：${guide.keyActionZh}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                )
            }
            if (guide.termZh.isNotBlank()) {
                // 术语收在最后一行的小字里：先给动作，术语只是给想深究的人一个词
                Text(
                    text = guide.termZh,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

/**
 * 中文母语者常见误读。
 *
 * 这是核心内容不是脚注（设计文档 §9），所以它有自己的一块、用 attention 语义色，
 * 而且每条都必须说清「会被听成什么」和「怎么改」——只说「容易读错」等于没说。
 */
@Composable
private fun CommonErrors(errors: List<CommonError>) {
    Surface(
        color = LazyDogTheme.extendedColors.attentionContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Outlined.WarningAmber,
                    contentDescription = null,
                    tint = LazyDogTheme.extendedColors.onAttentionContainer,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = "中文母语者最常踩的",
                    style = MaterialTheme.typography.titleSmall,
                    color = LazyDogTheme.extendedColors.onAttentionContainer,
                )
            }
            errors.forEach { error ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "${error.exampleEn} → 听成 ${error.soundsLikeEn}（读成了 /${error.substituteIpa}/）",
                        style = MaterialTheme.typography.bodyMedium,
                        color = LazyDogTheme.extendedColors.onAttentionContainer,
                    )
                    Text(
                        text = error.fixZh,
                        style = MaterialTheme.typography.bodyMedium,
                        color = LazyDogTheme.extendedColors.onAttentionContainer,
                    )
                }
            }
        }
    }
}

@Composable
private fun ContrastCard(contrast: PhonemeContrast, left: Phoneme?, right: Phoneme?) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = contrastLabel(left, right),
                style = MaterialTheme.typography.titleMedium,
            )
            contrast.minimalPairs.take(3).forEach { pair ->
                Text(
                    text = "${pair.leftWord} / ${pair.rightWord}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 你在这个音上的表现。
 *
 * 两条线**分开显示，不合成**：能听出来和能发出来是两件事，合成之后这个差距就看不见了，
 * 而它恰恰是这个模块要解决的问题（设计文档 §14.3）。
 */
@Composable
private fun Performance(progress: PronunciationProgress) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("你在这个音上的表现", style = MaterialTheme.typography.titleSmall)
            if (!progress.perception.hasEvidence && !progress.production.hasEvidence) {
                Text(
                    text = "还没练过这个音。练几次之后这里会出现两条线。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                return@Column
            }
            if (progress.perception.hasEvidence) SkillRow("听辨", progress.perception)
            if (progress.production.hasEvidence) SkillRow("发音", progress.production)
            val note = buildString {
                append("${progress.perception.sampleCount} 次听辨")
                append(" · ${progress.production.sampleCount} 次跟读")
            }
            Text(
                text = note,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
            if (progress.needsMoreEvidence) {
                Text(
                    text = "样本还少，这两个数先当参考。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}
