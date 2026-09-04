package com.lazydog.english.feature.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.Rule
import androidx.compose.material.icons.outlined.Abc
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.lazydog.english.LazyDogApplication
import com.lazydog.english.core.data.TodayReport
import com.lazydog.english.domain.planning.DailyPlanner
import com.lazydog.english.domain.planning.DailyStep
import com.lazydog.english.domain.progress.LearningActivity
import com.lazydog.english.domain.progress.MINIMUM_RETRIEVALS
import com.lazydog.english.domain.progress.reachedDailyMinimum
import java.time.LocalDate
import kotlinx.coroutines.launch

private val DailyStep.icon: ImageVector
    get() = when (this) {
        DailyStep.Words -> Icons.Outlined.Abc
        DailyStep.Grammar -> Icons.AutoMirrored.Outlined.Rule
        DailyStep.Production -> Icons.Outlined.Edit
        DailyStep.Reading -> Icons.AutoMirrored.Outlined.Article
        DailyStep.Speaking -> Icons.Outlined.Mic
    }

@Composable
fun TodayScreen(
    modifier: Modifier = Modifier,
    onStartAssessment: () -> Unit,
    onStartStep: (DailyStep) -> Unit,
) {
    val context = LocalContext.current
    val app = remember { context.applicationContext as LazyDogApplication }
    val scope = rememberCoroutineScope()
    val prefs = app.userPreferences
    val today = remember { LocalDate.now().toString() }

    // 初值用占位符，避免 DataStore 首帧前横幅闪现。
    val learnerLevel by prefs.learnerLevel.collectAsState(initial = "…")
    val dailyMinutes by prefs.dailyMinutes.collectAsState(initial = 12)
    val doneSteps by prefs.todayDoneSteps(today).collectAsState(initial = emptySet())
    val dueVocab by app.knowledgeRepository.observeDueVocabularyCount().collectAsState(initial = 0)
    val dueGrammar by app.knowledgeRepository.observeDueGrammarCount().collectAsState(initial = 0)

    // 进步证据和活跃度都从既有学习事件推，不额外记账（`持续学习DESIGN.md` §14、§7.1）。
    val reportFlow = remember { app.progressRepository.observeToday() }
    val activityFlow = remember { app.progressRepository.observeActivity() }
    val report by reportFlow.collectAsState(initial = TodayReport.Empty)
    val activity by activityFlow.collectAsState(initial = LearningActivity.None)
    val wrappedUp by prefs.wrappedUpToday(today).collectAsState(initial = false)

    val plan = remember(dailyMinutes, dueVocab, dueGrammar) {
        DailyPlanner.plan(dailyMinutes, dueVocabCount = dueVocab, dueGrammarCount = dueGrammar)
    }
    val allDone = plan.isNotEmpty() && plan.all { it.step.id in doneSteps }
    val nextStep = plan.firstOrNull { it.step.id !in doneSteps }
    val minimumDone = reachedDailyMinimum(report.progress, doneSteps.size)

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Text(
            text = "今天",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(top = 16.dp, bottom = 12.dp),
        )

        if (learnerLevel.isBlank()) {
            OutlinedCard(
                onClick = onStartAssessment,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Insights,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("先花 5 分钟摸个底", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = "测出水平后，AI 出的词、语法和文章都会更合身。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = if (allDone) "今天的洋屁放完了" else "今天约 $dailyMinutes 分钟",
                    style = MaterialTheme.typography.titleMedium,
                )
                // 最低目标写在最显眼的地方：今天再累也能过的那条线（§6）。
                Text(
                    text = if (minimumDone) "今天最低目标已经达成"
                    else "今天最低目标：$MINIMUM_RETRIEVALS 次回忆 · 约 2 分钟",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (minimumDone) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = if (allDone) {
                        "复习计划已经更新，明天见。"
                    } else if (dueVocab + dueGrammar > 0) {
                        buildList {
                            if (dueVocab > 0) add("$dueVocab 个词")
                            if (dueGrammar > 0) add("$dueGrammar 个语法点")
                        }.joinToString("、", postfix = "到期。先还债，再学新的。")
                    } else {
                        "没有到期的复习，轻松学点新的。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (activity.journeyDays > 0) {
            ActivityLine(activity)
        }

        Text(
            text = "今天的顺序",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp, bottom = 4.dp, start = 4.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            plan.forEach { planned ->
                val done = planned.step.id in doneSteps
                Surface(
                    onClick = { if (!done) onStartStep(planned.step) },
                    color = MaterialTheme.colorScheme.surface,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            imageVector = if (done) Icons.Outlined.CheckCircle else planned.step.icon,
                            contentDescription = null,
                            tint = if (done) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp),
                        )
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = planned.step.title,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (done) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = if (done) "完成了" else "${planned.note} · 约 ${planned.step.minutes} 分钟",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                        if (!done) {
                            TextButton(
                                onClick = {
                                    scope.launch { prefs.markTodayStepDone(today, planned.step.id) }
                                },
                            ) {
                                Text("跳过", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
        }

        // 进步证据：今天真的学到了什么，而不是加了多少分（§14.1、§22、§31）。
        if (report.progress.hasAnything) {
            ProgressEvidence(report, modifier = Modifier.padding(top = 16.dp))
        }

        when {
            allDone -> DoneNote("今天的步骤都走完了。想加练随时去「学习」页。")

            // 收工是用户自己按的，那就真的收工——不再摆一个继续学习的大按钮（§6）。
            wrappedUp -> {
                DoneNote("今天到这里。明天见。")
                TextButton(
                    onClick = { scope.launch { prefs.setWrappedUp(today, false) } },
                    modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
                ) {
                    Text("还想再学一会儿")
                }
            }

            nextStep == null -> Unit

            // 已经过了最低目标：继续和收工是平等的两个选项，收工不做成灰色小字。
            minimumDone -> Column(
                modifier = Modifier.padding(top = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Button(
                    onClick = { onStartStep(nextStep.step) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                ) {
                    Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                    Text(
                        text = "再学几分钟：${nextStep.step.title}",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
                TextButton(
                    onClick = { scope.launch { prefs.setWrappedUp(today, true) } },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("今天到这里")
                }
            }

            else -> Button(
                onClick = { onStartStep(nextStep.step) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp, bottom = 16.dp)
                    .height(56.dp),
            ) {
                Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                Text(
                    text = if (doneSteps.isEmpty()) "开始今天的学习" else "继续：${nextStep.step.title}",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
        }
    }
}

/**
 * 活跃度三个数一起给（§7.1）。
 *
 * 只显示连续天数的问题是断一次就归零，而人恰恰在断掉那天最需要一个回来的理由。
 * 旅程和最近三十天断不掉，它们是这个理由。
 */
@Composable
private fun ActivityLine(activity: LearningActivity) {
    Row(
        modifier = Modifier.padding(top = 10.dp, start = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ActivityStat("学习旅程", "${activity.journeyDays} 天")
        ActivityStat("最近 30 天", "${activity.activeDaysIn30} 天")
        ActivityStat("连续", "${activity.currentStreak} 天")
    }
}

@Composable
private fun ActivityStat(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * 今天的战报（§14.1）：说的都是知识点，不是积分。
 *
 * "重新记住了 xxx"是这里最值得看的一行——它直接指着一个用户上次栽过、这次想起来的词。
 */
@Composable
private fun ProgressEvidence(report: TodayReport, modifier: Modifier = Modifier) {
    val progress = report.progress
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.large,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("今天学到了什么", style = MaterialTheme.typography.titleSmall)
            if (progress.learned > 0) {
                Text(
                    text = "新学 ${progress.learned} 个",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (progress.reviewed > 0) {
                val percent = progress.rememberedPercent
                Text(
                    text = "回忆 ${progress.reviewed} 次，想起来 ${progress.remembered} 次" +
                        if (percent != null) " · $percent%" else "",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (report.recoveredNames.isNotEmpty()) {
                Text(
                    text = "上次没想起来、今天想起来了：" + report.recoveredNames.take(3).joinToString("、"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun DoneNote(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.TaskAlt,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}
