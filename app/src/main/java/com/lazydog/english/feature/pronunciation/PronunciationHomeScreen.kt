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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.lazydog.english.LazyDogApplication
import com.lazydog.english.domain.pronunciation.PhonemeCatalog
import com.lazydog.english.domain.pronunciation.PhonemeContrast
import com.lazydog.english.domain.pronunciation.PracticeQueue
import com.lazydog.english.domain.pronunciation.PronunciationProgress
import com.lazydog.english.domain.pronunciation.PronunciationTarget

/**
 * 发音与音标首页（`发音与音标学习DESIGN.md` §6，设计稿 `design/pronunciation/Main.dc.html`）。
 *
 * **首要动作是「继续练」，不是展示全部功能入口。** 音标表在下面第二屏，
 * 因为「今天练什么」这个决定不该丢回给用户——他要是知道自己哪个音弱，
 * 也就不需要这个模块了。
 *
 * 推荐来自 [PracticeQueue]：证据不足的目标不进队列，只安排它们多出现几次。
 * 一条记录都没有时走 [PracticeQueue.coldStart]，推目录里最难的几组，而不是显示空首页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PronunciationHomeScreen(
    onExit: () -> Unit,
    onScreening: () -> Unit,
    onPractice: (String) -> Unit,
    onOpenAllSounds: () -> Unit,
    onOpenProfile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val app = remember { context.applicationContext as LazyDogApplication }
    val catalog = remember { app.phonemeCatalog }
    val progressList by app.pronunciationRepository.progress.collectAsState(initial = emptyList())
    val screened by app.userPreferences.voiceScreeningDone.collectAsState(initial = true)
    val now = remember(progressList) { System.currentTimeMillis() }

    val ranked = remember(progressList, now) {
        PracticeQueue.weakest(progressList, baseValueOf = { 1f }, now = now)
            .filter { PronunciationTarget.isContrast(it.targetId) }
    }
    val unproven = remember(progressList) { PracticeQueue.needsMoreEvidence(progressList) }
    val recommended: Pair<PhonemeContrast, PronunciationProgress?>? = remember(ranked, unproven) {
        val fromQueue = ranked.firstOrNull()
            ?: unproven.firstOrNull { PronunciationTarget.isContrast(it.targetId) }
        val contrast = fromQueue?.let { catalog.contrast(PronunciationTarget.rawId(it.targetId)) }
        when {
            contrast != null -> contrast to fromQueue
            else -> PracticeQueue.coldStart(catalog, count = 1).firstOrNull()?.let { it to null }
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("发音与音标") },
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
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            // 没摸过底就先摆这一条：让用户从第一个音标开始，正是这个模块想避免的事。
            if (!screened) {
                ScreeningPrompt(onStart = onScreening, modifier = Modifier.padding(top = 8.dp))
            }

            recommended?.let { (contrast, progress) ->
                Recommendation(
                    contrast = contrast,
                    progress = progress,
                    catalog = catalog,
                    onPractice = { onPractice(contrast.id) },
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionTitle("我的声音")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HomeTile(
                        title = "全部声音",
                        note = "${catalog.phonemes.size} 个音位",
                        onClick = onOpenAllSounds,
                        modifier = Modifier.weight(1f),
                    )
                    HomeTile(
                        title = "声音对比",
                        note = "${catalog.contrasts.size} 组",
                        onClick = onOpenAllSounds,
                        modifier = Modifier.weight(1f),
                    )
                    HomeTile(
                        title = "我的画像",
                        note = "听得出 / 发得出",
                        onClick = onOpenProfile,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            if (ranked.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = "我的薄弱项",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onOpenProfile) { Text("查看全部") }
                    }
                    ranked.take(3).forEach { progress ->
                        val contrast = catalog.contrast(PronunciationTarget.rawId(progress.targetId))
                        WeaknessRow(
                            label = contrast?.let {
                                contrastLabel(catalog.phoneme(it.leftPhonemeId), catalog.phoneme(it.rightPhonemeId))
                            } ?: PronunciationTarget.rawId(progress.targetId),
                            progress = progress,
                            onClick = { contrast?.let { onPractice(it.id) } },
                        )
                    }
                    // 证据不足的单独放在下面，不混进薄弱项：它们需要的是多测几次，不是马上开练。
                    unproven.take(2).forEach { progress ->
                        val contrast = catalog.contrast(PronunciationTarget.rawId(progress.targetId))
                        WeaknessRow(
                            label = contrast?.let {
                                contrastLabel(catalog.phoneme(it.leftPhonemeId), catalog.phoneme(it.rightPhonemeId))
                            } ?: PronunciationTarget.rawId(progress.targetId),
                            progress = progress,
                            onClick = { contrast?.let { onPractice(it.id) } },
                        )
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "音标是标签，声音才是要学的东西。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

@Composable
private fun Recommendation(
    contrast: PhonemeContrast,
    progress: PronunciationProgress?,
    catalog: PhonemeCatalog,
    onPractice: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "今天最值得练",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                text = contrastLabel(
                    catalog.phoneme(contrast.leftPhonemeId),
                    catalog.phoneme(contrast.rightPhonemeId),
                ),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                text = contrast.minimalPairs.firstOrNull()
                    ?.let { "从 ${it.leftWord} / ${it.rightWord} 开始。" }
                    .orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            if (progress != null && progress.perception.hasEvidence) {
                SkillRow("听辨", progress.perception)
                if (progress.production.hasEvidence) SkillRow("发音", progress.production)
                if (!progress.perception.confident) LowConfidenceNote(progress.perception)
            } else {
                Text(
                    text = "还没练过这一组。先听六道题，看看你现在分不分得清。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Button(onClick = onPractice, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("练 2 分钟")
            }
        }
    }
}

@Composable
private fun HomeTile(
    title: String,
    note: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.height(84.dp),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(
                text = note,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun WeaknessRow(
    label: String,
    progress: PronunciationProgress,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
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
                text = label,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.width(104.dp),
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f),
            ) {
                if (progress.perception.hasEvidence) SkillRow("听辨", progress.perception)
                if (progress.production.hasEvidence) {
                    SkillRow("发音", progress.production)
                } else if (!progress.perception.confident) {
                    LowConfidenceNote(progress.perception)
                }
            }
        }
    }
}
