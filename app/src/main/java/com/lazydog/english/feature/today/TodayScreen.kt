package com.lazydog.english.feature.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lazydog.english.LazyDogApplication
import com.lazydog.english.core.data.TodayReport
import com.lazydog.english.core.designsystem.appCopy
import com.lazydog.english.domain.planning.DailyPlanner
import com.lazydog.english.domain.planning.DailyStep
import com.lazydog.english.domain.progress.LearningActivity
import com.lazydog.english.domain.progress.LongTermProof
import com.lazydog.english.domain.progress.MINIMUM_RETRIEVALS
import com.lazydog.english.domain.progress.Mood
import com.lazydog.english.domain.progress.mood
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
    val copy = appCopy
    val today = remember { LocalDate.now().toString() }

    // 初值用占位符，避免 DataStore 首帧前横幅闪现。
    val learnerLevel by prefs.learnerLevel.collectAsState(initial = "…")
    val dailyMinutes by prefs.dailyMinutes.collectAsState(initial = 12)
    val doneSteps by prefs.todayDoneSteps(today).collectAsState(initial = emptySet())
    val skippedSteps by prefs.todaySkippedSteps(today).collectAsState(initial = emptySet())
    val dueVocab by app.knowledgeRepository.observeDueVocabularyCount().collectAsState(initial = 0)
    val dueGrammar by app.knowledgeRepository.observeDueGrammarCount().collectAsState(initial = 0)

    // 进步证据和活跃度都从既有学习事件推，不额外记账（`持续学习DESIGN.md` §14、§7.1）。
    val reportFlow = remember { app.progressRepository.observeToday() }
    val activityFlow = remember { app.progressRepository.observeActivity() }
    val report by reportFlow.collectAsState(initial = TodayReport.Empty)
    val activity by activityFlow.collectAsState(initial = LearningActivity.None)
    val wrappedUp by prefs.wrappedUpToday(today).collectAsState(initial = false)

    // 中断回来、或者今天已经做累了，今天就只排一步（§26、§25）。
    val mood = mood(daysAway = activity.daysAway, fatigue = report.fatigue)
    val plan = remember(dailyMinutes, dueVocab, dueGrammar, mood) {
        DailyPlanner.plan(dailyMinutes, dueVocabCount = dueVocab, dueGrammarCount = dueGrammar, mood = mood)
    }
    val allDone = plan.isNotEmpty() && plan.all { it.step.id in doneSteps }
    val nextStep = DailyPlanner.nextStep(plan, doneSteps, skippedSteps)
    val minimumDone = reachedDailyMinimum(report.progress, doneSteps.size)

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            // 底部这一下是给底部导航栏留的。外面那层 Scaffold 的 padding 加在滚动容器**外面**，
            // 所以滚到底时最后一张卡是贴着导航栏收住的，中间一点空隙都没有。
            .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
    ) {
        Column(
            modifier = Modifier.padding(top = 16.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(text = "今天", style = MaterialTheme.typography.headlineSmall)
            // 三个数原来在页面中段竖排成三组、占掉近一屏。收到标题下面一行，
            // 三个仍然一起给（`持续学习DESIGN.md` §7.1）——少给一个就回到"断一次归零"那个问题。
            if (activity.journeyDays > 0) ActivityLine(activity)
        }

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
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = when {
                        wrappedUp -> copy.todaySignOff
                        allDone -> copy.todayFinishedTitle
                        mood == Mood.Comeback -> copy.todayGreeting
                        mood == Mood.Tired -> copy.todayMinimumReachedTitle
                        else -> copy.todayPlannedMinutes(plan.sumOf { it.step.minutes })
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = if (allDone) {
                        copy.todayFinishedNote
                    } else if (mood == Mood.Comeback) {
                        // §26 点名不要说"你已经落后 74 个复习"——那是在为回来这件事加一道门槛。
                        // 也确实不用补：FSRS 里过期越久可提取性越低，本来就是连续的，不会堆成债。
                        copy.todayRecoveryNote
                    } else if (mood == Mood.Tired) {
                        copy.todayFatigueNote
                    } else if (dueVocab + dueGrammar > 0) {
                        buildList {
                            if (dueVocab > 0) add("$dueVocab 个词")
                            if (dueGrammar > 0) add("$dueGrammar 个语法点")
                        }.joinToString("、", postfix = copy.todayDueSuffix)
                    } else {
                        copy.todayNothingDue
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                }
                // 最低目标是今天再累也能过的那条线（§6）。原来它只是卡片里的一行灰字，
                // 和上下两行分不出轻重；给它一条进度条，"还差几次"不用读句子也能看出来。
                MinimumGoal(reviewed = report.progress.reviewed, done = minimumDone)
            }
        }

        when {
            // 收工是用户自己按的，那就真的收工——不再摆一个继续学习的大按钮（§6）。
            wrappedUp -> {
                TextButton(
                    onClick = { scope.launch { prefs.setWrappedUp(today, false) } },
                    modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
                ) {
                    Text(copy.todayKeepGoing)
                }
            }

            allDone -> DoneNote(copy.todayAllStepsDone)

            nextStep == null -> DoneNote("今天的安排已跳过，想学时可在下方恢复。跳过不计入学习成果。")

            // 已经过了最低目标：继续和收工是平等的两个选项，收工不做成灰色小字。
            minimumDone -> Column(
                modifier = Modifier.padding(top = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { scope.launch { prefs.setWrappedUp(today, true) } },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                ) {
                    Text(copy.todayStopHere, style = MaterialTheme.typography.titleMedium)
                }
                OutlinedButton(
                    onClick = { onStartStep(nextStep.step) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp),
                ) {
                    Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                    Text(
                        text = "再学几分钟：${nextStep.step.title}",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
            }

            else -> Button(
                onClick = { onStartStep(nextStep.step) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp, bottom = 16.dp)
                    .heightIn(min = 56.dp),
            ) {
                Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                Text(
                    text = if (doneSteps.isEmpty()) copy.todayStart else "继续：${nextStep.step.title}",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
        }

        if (!wrappedUp) {
            Text(
                text = copy.todayPlanTitle,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 28.dp, bottom = 8.dp, start = 4.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val dashColor = MaterialTheme.colorScheme.outlineVariant
                plan.forEach { planned ->
                    val done = planned.step.id in doneSteps
                    val skipped = !done && planned.step.id in skippedSteps
                    Surface(
                        onClick = { if (!done && !skipped) onStartStep(planned.step) },
                        // 待办那几行原来用的是 `surface`，和页面背景一模一样，看上去不像能点。
                        // 只有还要做的事给容器底色；做完和跳过的退成透明，让底色本身成为"轮到你了"的信号。
                        color = if (done || skipped) Color.Transparent
                        else MaterialTheme.colorScheme.surfaceContainer,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (skipped) Modifier.dashedOutline(dashColor, 12.dp) else Modifier),
                    ) {
                        Row(
                            modifier = Modifier
                                .heightIn(min = 64.dp)
                                .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Icon(
                                imageVector = if (done) Icons.Outlined.CheckCircle else planned.step.icon,
                                contentDescription = null,
                                tint = when {
                                    done -> MaterialTheme.colorScheme.primary
                                    skipped -> MaterialTheme.colorScheme.outline
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.size(22.dp),
                            )
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = planned.step.title,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = when {
                                        done -> MaterialTheme.colorScheme.onSurfaceVariant
                                        skipped -> MaterialTheme.colorScheme.outline
                                        else -> MaterialTheme.colorScheme.onSurface
                                    },
                                )
                                Text(
                                    text = if (done) "完成了" else if (skipped) "今天已跳过 · 不计入完成"
                                    else "${planned.note} · 约 ${planned.step.minutes} 分钟",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                            if (!done) {
                                TextButton(
                                    onClick = {
                                        scope.launch { prefs.setTodayStepSkipped(today, planned.step.id, !skipped) }
                                    },
                                    // 「跳过」是这一行里最不该被点的那个动作，收成灰色；
                                    // 「恢复」相反，它是跳过之后唯一的出口，留主色。
                                    colors = ButtonDefaults.textButtonColors(
                                        contentColor = if (skipped) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.outline,
                                    ),
                                ) {
                                    Text(if (skipped) "恢复" else "跳过", style = MaterialTheme.typography.labelMedium)
                                }
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

        // 长期证明单独一张：它讲的不是今天，是几个月的跨度（§14.3）。
        report.proof?.let { LongTermProofCard(it, modifier = Modifier.padding(top = 12.dp)) }
    }
}

/**
 * 活跃度三个数一起给（§7.1）。
 *
 * 只显示连续天数的问题是断一次就归零，而人恰恰在断掉那天最需要一个回来的理由。
 * 旅程和最近三十天断不掉，它们是这个理由——所以三个数必须一起出现，少给一个就等于没写。
 *
 * 排版上收成标题下的一行：它是背景信息，不该在页面中段占掉三组两行的位置，
 * 把真正要做的事挤到屏幕外面去。
 */
@Composable
private fun ActivityLine(activity: LearningActivity) {
    val streakLabel = if (activity.restDaysUsed > 0) {
        "连续 ${activity.currentStreak} 天 · 休过 ${activity.restDaysUsed} 天"
    } else {
        "连续 ${activity.currentStreak} 天"
    }
    Text(
        text = "旅程 ${activity.journeyDays} 天 · 近 30 天 ${activity.activeDaysIn30} 天 · $streakLabel",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.outline,
    )
}

/**
 * 最低目标那条线（§6）：回忆满 [MINIMUM_RETRIEVALS] 次，或者完成任一步。
 *
 * 进度条只画得出回忆那一半——"完成任一步"是另一条路，条到不了满格也可能已经达成，
 * 所以达成之后直接把条填满，不让它继续显示一个已经不作数的比例。
 */
@Composable
private fun MinimumGoal(reviewed: Int, done: Boolean) {
    val copy = appCopy
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "今天的最低目标",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = if (done) copy.todayMinimumDone else "回忆 $reviewed / $MINIMUM_RETRIEVALS 次",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        LinearProgressIndicator(
            progress = {
                if (done) 1f else (reviewed.toFloat() / MINIMUM_RETRIEVALS).coerceIn(0f, 1f)
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp),
        )
        if (!done) {
            Text(
                text = "回忆满 $MINIMUM_RETRIEVALS 次，或完成任一步，今天就算数了",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/** 跳过态的虚线边框。M3 没有虚线描边，就地画一条，不为这一处新造组件。 */
private fun Modifier.dashedOutline(color: Color, radius: Dp) = drawBehind {
    val stroke = 1.dp.toPx()
    drawRoundRect(
        color = color,
        topLeft = Offset(stroke / 2f, stroke / 2f),
        size = Size(size.width - stroke, size.height - stroke),
        cornerRadius = CornerRadius(radius.toPx()),
        style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))),
    )
}

/**
 * 今天的战报（§14.1）：说的都是知识点，不是积分。
 *
 * "重新记住了 xxx"是这里最值得看的一行——它直接指着一个用户上次栽过、这次想起来的词。
 */
@Composable
private fun ProgressEvidence(report: TodayReport, modifier: Modifier = Modifier) {
    val copy = appCopy
    val progress = report.progress
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.large,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(copy.todayReportTitle, style = MaterialTheme.typography.titleSmall)
            if (progress.learned > 0) {
                Text(
                    text = copy.todayLearned(progress.learned),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (progress.reviewed > 0) {
                val percent = progress.rememberedPercent
                Text(
                    text = copy.todayRecalled(progress.reviewed, progress.remembered) +
                        if (percent != null) " · $percent%" else "",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (report.recoveredNames.isNotEmpty()) {
                Text(
                    text = copy.todayComebackTitle + report.recoveredNames.take(3).joinToString("、"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/**
 * "你以前不会，现在会了"（§14.3）。
 *
 * 这张卡的说服力全在**具体**上：指名道姓地摆出当时写错的那个拼法，
 * 而不是一句"你的拼写进步了"。用户自己会认出那个错误，那一刻的说服力不需要任何数字。
 */
@Composable
private fun LongTermProofCard(proof: LongTermProof, modifier: Modifier = Modifier) {
    val copy = appCopy
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.large,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = copy.proofDaysAgo(proof.daysAgo),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                text = copy.proofPastAnswer(proof.pastAnswer),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                text = copy.proofNow(proof.term),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
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
