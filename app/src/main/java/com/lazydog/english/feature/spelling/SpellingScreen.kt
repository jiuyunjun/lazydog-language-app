package com.lazydog.english.feature.spelling

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lazydog.english.LazyDogApplication
import com.lazydog.english.core.ask.ProvideAskContext
import com.lazydog.english.core.data.KnowledgeRepository
import com.lazydog.english.feature.ask.AskTopBarAction

/**
 * 拼写练习（设计稿 59～61、63 屏）：一轮 12 个词的会话外壳。
 *
 * 每个词考什么由它自己的拼写阶段决定，不是全场同一种题。单张卡的出题、
 * 提示梯度和判分都在 [SpellingCardView] 里——单词复习里熟词的产出卡走的是同一个，
 * 这一页只负责组队列、翻页和收尾。
 */
private sealed interface SpellingPhase {
    data object Loading : SpellingPhase
    data object Empty : SpellingPhase
    data class Round(
        val cards: List<SpellingCard>,
        val index: Int,
    ) : SpellingPhase {
        val card: SpellingCard get() = cards[index]
    }
    data class Summary(val total: Int, val correctFirstTry: Int, val hintUsed: Int) : SpellingPhase
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpellingScreen(
    repository: KnowledgeRepository,
    onExit: () -> Unit,
    onOpenProfile: () -> Unit,
) {
    val context = LocalContext.current
    val app = remember { context.applicationContext as LazyDogApplication }

    var phase by remember { mutableStateOf<SpellingPhase>(SpellingPhase.Loading) }
    var correctFirstTry by remember { mutableStateOf(0) }
    var hintUsed by remember { mutableStateOf(0) }
    var answer by remember { mutableStateOf(SpellingAnswer()) }

    LaunchedEffect(Unit) {
        val queue = repository.spellingQueue().map { it.toSpellingCard() }
        phase = if (queue.isEmpty()) SpellingPhase.Empty else SpellingPhase.Round(queue, 0)
    }

    // 练完或没题时把没放完的音掐掉，但留着已经热起来的蓝牙链路。
    val stillAnswering = phase is SpellingPhase.Round
    LaunchedEffect(stillAnswering) {
        if (!stillAnswering) app.speechController.stopSpeaking(keepLink = true)
    }

    val round = phase as? SpellingPhase.Round
    ProvideAskContext(round?.let { spellingAskContext(it.card, answer) })

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (round != null) {
                            "拼写练习 · ${round.index + 1} / ${round.cards.size}"
                        } else {
                            "拼写练习"
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onExit) {
                        Icon(Icons.Outlined.Close, contentDescription = "退出")
                    }
                },
                actions = { AskTopBarAction() },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (val p = phase) {
                SpellingPhase.Loading -> CenterBox { CircularProgressIndicator() }
                SpellingPhase.Empty -> CenterBox {
                    Text(
                        text = "还没有能练拼写的词。先去学几个新词，或者复习几张卡，回来就有题了。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    TextButton(onClick = onExit) { Text("知道了") }
                }
                is SpellingPhase.Round -> {
                    LinearProgressIndicator(
                        progress = { (p.index + 1).toFloat() / p.cards.size },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                    )
                    SpellingCardView(
                        card = p.card,
                        repository = repository,
                        onAnswerChange = { answer = it },
                        onResolved = { correct, usedHint ->
                            if (correct && !usedHint) correctFirstTry += 1
                            if (usedHint) hintUsed += 1
                            phase = if (p.index + 1 < p.cards.size) {
                                p.copy(index = p.index + 1)
                            } else {
                                SpellingPhase.Summary(p.cards.size, correctFirstTry, hintUsed)
                            }
                        },
                    )
                }
                is SpellingPhase.Summary -> SummaryView(
                    phase = p,
                    onExit = onExit,
                    onOpenProfile = onOpenProfile,
                )
            }
        }
    }
}

@Composable
private fun SummaryView(
    phase: SpellingPhase.Summary,
    onExit: () -> Unit,
    onOpenProfile: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("这一轮练完了", style = MaterialTheme.typography.headlineSmall)
        Text(
            text = "${phase.total} 个词，${phase.correctFirstTry} 个一次就写对；${phase.hintUsed} 个用了提示。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "用过提示的词会自己排回来，不用记着回头找。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onExit, modifier = Modifier.fillMaxWidth()) { Text("完成") }
        TextButton(onClick = onOpenProfile) { Text("看看我的拼写弱点") }
    }
}

@Composable
private fun CenterBox(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        content()
    }
}
