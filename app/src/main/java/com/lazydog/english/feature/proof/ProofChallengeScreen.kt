package com.lazydog.english.feature.proof

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.lazydog.english.LazyDogApplication
import com.lazydog.english.core.designsystem.AiWaiting
import com.lazydog.english.core.designsystem.InteractiveEnglishText
import com.lazydog.english.core.designsystem.LazyDogTheme
import com.lazydog.english.core.designsystem.SpeakButton
import com.lazydog.english.core.speech.PlaybackSource
import com.lazydog.english.domain.generation.GenerationResult
import com.lazydog.english.domain.generation.GenerationStage
import com.lazydog.english.domain.progress.PROOF_DECOY_COUNT
import com.lazydog.english.domain.progress.PROOF_TERM_COUNT
import com.lazydog.english.domain.progress.PROOF_WINDOW_MAX_DAYS
import com.lazydog.english.domain.progress.PROOF_WINDOW_MIN_DAYS
import com.lazydog.english.domain.progress.ProofChallenge
import com.lazydog.english.domain.progress.pickProofTerms
import kotlinx.coroutines.flow.first

/**
 * 进步挑战（`持续学习DESIGN.md` §15）。
 *
 * 目的只有一个：**让用户亲耳听见自己变强了**。用两到四周前学的四个词组一句话，
 * 他听懂了，这个结论就是他自己的耳朵得出的，不是 App 给的一个分数。
 *
 * 所以这一页刻意没有分数、没有计时、没有连击。听懂了就是听懂了。
 */
private sealed interface ProofPhase {
    data object Loading : ProofPhase
    /** 手上的旧词不够四个，这一轮出不了——不凑数（见 `pickProofTerms`）。 */
    data object NotYet : ProofPhase
    data class Failed(val reason: String) : ProofPhase
    data class Listening(val challenge: ProofChallenge, val playCount: Int) : ProofPhase
    data class Picking(val challenge: ProofChallenge) : ProofPhase
    data class Revealed(val challenge: ProofChallenge, val picked: Set<String>) : ProofPhase
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProofChallengeScreen(onExit: () -> Unit) {
    val context = LocalContext.current
    val app = remember { context.applicationContext as LazyDogApplication }

    var phase by remember { mutableStateOf<ProofPhase>(ProofPhase.Loading) }
    var stage by remember { mutableStateOf<GenerationStage>(GenerationStage.Connecting) }
    var picked by remember { mutableStateOf(setOf<String>()) }

    LaunchedEffect(Unit) {
        val chosen = pickProofTerms(app.progressRepository.proofCandidates())
        if (chosen.isEmpty()) {
            phase = ProofPhase.NotYet
            return@LaunchedEffect
        }
        val terms = chosen.map { it.term }
        val result = app.contentGenerator.generateProofSentence(
            terms = terms,
            learnerLevel = app.userPreferences.learnerLevelDescription.first(),
            onStage = { stage = it },
        )
        phase = when (result) {
            is GenerationResult.Failure -> ProofPhase.Failed(result.reason)
            is GenerationResult.Success -> ProofPhase.Listening(
                challenge = ProofChallenge(
                    terms = terms,
                    sentenceEn = result.data.sentenceEn,
                    sentenceZh = result.data.sentenceZh,
                    oldestDaysAgo = chosen.maxOf { it.learnedDaysAgo },
                    decoys = app.progressRepository.decoyTerms(
                        excludedItemIds = chosen.map { it.itemId },
                        count = PROOF_DECOY_COUNT,
                    ),
                ),
                playCount = 0,
            )
        }
    }

    // 人走了就别念了。
    LaunchedEffect(phase) {
        if (phase is ProofPhase.NotYet || phase is ProofPhase.Failed) app.speechController.stop()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("还记得吗") },
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
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (val p = phase) {
                ProofPhase.Loading -> AiWaiting("正在挑几个你两周前学的词…", stage)

                ProofPhase.NotYet -> Hint(
                    title = "再过些日子来",
                    body = "这一关要用 $PROOF_WINDOW_MIN_DAYS~$PROOF_WINDOW_MAX_DAYS 天前学过的 " +
                        "$PROOF_TERM_COUNT 个词凑一句话。现在还不够——用刚学的词考你，证明不了什么。",
                )

                is ProofPhase.Failed -> Hint(title = "这次没出成", body = p.reason)

                is ProofPhase.Listening -> {
                    Hint(
                        title = "先听，不看字",
                        body = "这句话里藏着几个你 ${p.challenge.oldestDaysAgo} 天前学过的词。听清楚了再看选项。",
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SpeakButton(
                            source = PlaybackSource.sentence(p.challenge.sentenceEn),
                            contentDescription = if (p.playCount == 0) "播放" else "再听一次",
                            iconSize = 32.dp,
                        )
                        Text(
                            text = if (p.playCount == 0) "点一下播放" else "已经听了 ${p.playCount} 遍 · 想听几遍都行",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(
                        onClick = { phase = ProofPhase.Picking(p.challenge) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("听完了，看选项")
                    }
                    // 播放次数不计分：这一关证明的是"听懂了"，不是"一遍就听懂了"。
                    TextButton(onClick = { phase = ProofPhase.Listening(p.challenge, p.playCount + 1) }) {
                        Text("再听一遍")
                    }
                }

                is ProofPhase.Picking -> {
                    Hint(
                        title = "哪几个词在这句话里？",
                        body = "选出你听到的。有几个是混进来的，不在这句话里。",
                    )
                    SpeakButton(
                        source = PlaybackSource.sentence(p.challenge.sentenceEn),
                        contentDescription = "再听一次",
                        iconSize = 28.dp,
                    )
                    val options = remember(p.challenge) { p.challenge.options(seed = p.challenge.terms.size) }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        options.chunked(2).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { option ->
                                    FilterChip(
                                        selected = option in picked,
                                        onClick = {
                                            picked = if (option in picked) picked - option else picked + option
                                        },
                                        label = { Text(option) },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }
                    }
                    Button(
                        onClick = { phase = ProofPhase.Revealed(p.challenge, picked) },
                        enabled = picked.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("看答案")
                    }
                }

                is ProofPhase.Revealed -> Revealed(p, onExit)
            }
        }
    }
}

@Composable
private fun Revealed(phase: ProofPhase.Revealed, onExit: () -> Unit) {
    val challenge = phase.challenge
    val hit = challenge.terms.count { it in phase.picked }
    val extended = LazyDogTheme.extendedColors

    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                // 这句话是这一关的全部意义（§15）：不是"你得了几分"，是"你听懂了多久以前的东西"。
                text = "你听懂了 ${challenge.oldestDaysAgo} 天前学的 $hit 个表达",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            if (hit < challenge.terms.size) {
                Text(
                    text = "没听出来的那几个，下次复习会再遇到。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }

    InteractiveEnglishText(text = challenge.sentenceEn, style = MaterialTheme.typography.titleMedium)
    Text(
        text = challenge.sentenceZh,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        challenge.terms.forEach { term ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    tint = if (term in phase.picked) extended.correct else MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.size(18.dp),
                )
                Text(term, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }

    Button(onClick = onExit, modifier = Modifier.fillMaxWidth()) {
        Text("收工")
    }
}

@Composable
private fun Hint(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
