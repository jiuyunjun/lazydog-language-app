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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lazydog.english.LazyDogApplication
import kotlinx.coroutines.flow.first
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
    data class Summary(
        val answered: Int,
        val correctFirstTry: Int,
        val hintUsed: Int,
        /** 本轮里被排回来重考的词数（10 分钟延迟回忆，拼写训练DESIGN.md §13）。 */
        val retested: Int,
    ) : SpellingPhase
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
    var answered by remember { mutableStateOf(0) }
    // 每个词本轮最多排回来一次：延迟重考是为了验一遍，不是罚站。
    val retested = remember { mutableStateListOf<Long>() }
    var answer by remember { mutableStateOf(SpellingAnswer()) }

    LaunchedEffect(Unit) {
        // 难度偏置只在开局取一次：一轮做到一半忽然换题型，用户只会觉得莫名其妙（§11）。
        val difficulty = app.progressRepository.observeDifficulty().first()
        val queue = repository.spellingQueue().map { it.toSpellingCard(difficulty) }
        phase = if (queue.isEmpty()) SpellingPhase.Empty else SpellingPhase.Round(queue, 0)
    }

    // 练完或没题时把没放完的音掐掉，但留着已经热起来的蓝牙链路。
    val stillAnswering = phase is SpellingPhase.Round
    LaunchedEffect(stillAnswering) {
        if (!stillAnswering) app.speechController.stop(keepLink = true)
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
                        onResolved = { resolution ->
                            // 接触卡不计成绩：看过一个词不构成"写得出"的任何证据。
                            if (!resolution.wasExposure) {
                                answered += 1
                                if (resolution.correct && !resolution.usedHint) correctFirstTry += 1
                                if (resolution.usedHint) hintUsed += 1
                            }
                            // 复习阶梯落回最低一档的词（刚接触的、答错退档的、靠提示写对的）
                            // 排到这一轮末尾再考一次，而不是等明天——刚学完立刻答对说明不了什么。
                            val requeue = resolution.needsDelayedRetest &&
                                resolution.nextProgress != null &&
                                p.card.itemId !in retested
                            val cards = if (requeue) {
                                retested += p.card.itemId
                                // 重考不再推一次复习时间：同一个词在同一轮里答两次，
                                // 记成两轮复习会把 lapseCount 和 stability 一起撑歪
                                // （D-028「一张卡只算一轮复习」）。画像照记。
                                p.cards + p.card.copy(
                                    progress = resolution.nextProgress,
                                    delayed = true,
                                    dueForReview = false,
                                )
                            } else {
                                p.cards
                            }
                            phase = if (p.index + 1 < cards.size) {
                                p.copy(cards = cards, index = p.index + 1)
                            } else {
                                SpellingPhase.Summary(answered, correctFirstTry, hintUsed, retested.size)
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
            text = "${phase.answered} 道题，${phase.correctFirstTry} 个一次就写对；${phase.hintUsed} 个用了提示。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Text(
            text = if (phase.retested > 0) {
                "其中 ${phase.retested} 个词在这一轮里排回来又考了一遍——刚写对不算记住。"
            } else {
                "用过提示的词会自己排回来，不用记着回头找。"
            },
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
