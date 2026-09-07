package com.lazydog.english.feature.pronunciation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.lazydog.english.LazyDogApplication
import com.lazydog.english.core.designsystem.LazyDogTheme
import com.lazydog.english.domain.pronunciation.PhonemeCatalog
import com.lazydog.english.domain.pronunciation.PracticeQueue
import com.lazydog.english.domain.pronunciation.PronunciationProgress
import com.lazydog.english.domain.pronunciation.PronunciationStage
import com.lazydog.english.domain.pronunciation.PronunciationStages
import com.lazydog.english.domain.pronunciation.PronunciationTarget

/**
 * 我的声音 · 声音盲区画像（`发音与音标学习DESIGN.md` §16，设计稿 `design/pronunciation/Profile.dc.html`）。
 *
 * 顶上那两个数是**计数不是分数**：它们存在的意义就是让「听得出 14 / 发得出 6」这个差距可见。
 * 这一屏没有任何一处把两条线合成一个总分。
 *
 * 分组顺序照设计文档：需要优先练 → 听得出读不准 → 正在变稳 → 已经稳定 → 还看不准。
 * 最后那一组是**低置信**，它们不排优先级、不标红，只是需要多测几次。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun VoiceProfileScreen(
    onExit: () -> Unit,
    onPractice: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val app = remember { context.applicationContext as LazyDogApplication }
    val catalog = remember { app.phonemeCatalog }
    val all by app.pronunciationRepository.progress.collectAsState(initial = emptyList())
    val now = remember(all) { System.currentTimeMillis() }

    val hears = all.count {
        it.perception.confident && it.perception.score >= PronunciationStages.PERCEPTION_OK
    }
    val says = all.count {
        it.production.confident && it.production.score >= PronunciationStages.PRODUCTION_OK
    }
    val priority = PracticeQueue.weakest(all, now = now).filterNot { it.hearsButCannotSay }
    val hearsNotSays = all.filter { it.hearsButCannotSay }
    val improving = all.filter {
        it.stage == PronunciationStage.Producing && it !in hearsNotSays
    }
    val stable = all.filter { it.stage == PronunciationStage.Stable }
    val unproven = PracticeQueue.needsMoreEvidence(all)

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("我的声音") },
                navigationIcon = {
                    IconButton(onClick = onExit) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Counts(hears = hears, says = says)

            if (all.isEmpty()) {
                Text(
                    text = "还没有任何记录。练几轮之后这里会长出一张属于你的表。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            Group("需要优先练", "弱 · 证据够 · 用得上", priority, catalog, onPractice)
            Group("听得出，读不准", "这一组只排跟读，耳朵那关已经过了", hearsNotSays, catalog, onPractice)
            Group("正在变稳", null, improving, catalog, onPractice)

            if (stable.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionTitle("已经稳定")
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        stable.forEach { progress ->
                            Surface(
                                color = LazyDogTheme.extendedColors.correctContainer,
                                shape = MaterialTheme.shapes.small,
                            ) {
                                Text(
                                    text = labelOf(progress, catalog),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = LazyDogTheme.extendedColors.onCorrectContainer,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                )
                            }
                        }
                    }
                    Text(
                        text = "稳定的也会偶尔回来抽查一次，不会就此不再出现。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }

            if (unproven.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionTitle("还看不准")
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            unproven.forEach { progress ->
                                Row {
                                    Text(
                                        text = labelOf(progress, catalog),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.outline,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        text = "练过 ${progress.perception.sampleCount} 次",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                }
                            }
                            Text(
                                text = "样本太少，先不给分也不排优先级，只安排它们多出现几次。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                }
            }

            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("这个顺序是怎么排的", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "弱到什么程度 × 证据够不够 × 这个音有多常用 × 多久没练了。" +
                            "四项都占权重，所以最弱的那个不一定排第一——只练过三次的不会插队。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun Counts(hears: Int, says: Int) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                listOf("听得出来" to hears, "自己发得出" to says).forEach { (label, value) ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Text(
                            text = "$value",
                            style = MaterialTheme.typography.headlineMedium,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }
            Text(
                text = "这两个数不会合成一个总分。听得出来和发得出来是两件事，" +
                    "中间那些就是接下来要干的活。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun Group(
    title: String,
    note: String?,
    items: List<PronunciationProgress>,
    catalog: PhonemeCatalog,
    onPractice: (String) -> Unit,
) {
    if (items.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle(title, trailing = note)
        items.forEach { progress ->
            Surface(
                onClick = {
                    if (PronunciationTarget.isContrast(progress.targetId)) {
                        onPractice(PronunciationTarget.rawId(progress.targetId))
                    }
                },
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(
                        text = labelOf(progress, catalog),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.width(104.dp),
                    )
                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        if (progress.perception.hasEvidence) SkillRow("听辨", progress.perception)
                        if (progress.production.hasEvidence) SkillRow("发音", progress.production)
                    }
                }
            }
        }
    }
}

private fun labelOf(progress: PronunciationProgress, catalog: PhonemeCatalog): String {
    val raw = PronunciationTarget.rawId(progress.targetId)
    return if (PronunciationTarget.isContrast(progress.targetId)) {
        catalog.contrast(raw)?.let {
            "/${catalog.phoneme(it.leftPhonemeId)?.ipa ?: "?"}/ ↔ /${catalog.phoneme(it.rightPhonemeId)?.ipa ?: "?"}/"
        } ?: raw
    } else {
        catalog.phoneme(raw)?.let { "/${it.ipa}/" } ?: raw
    }
}
