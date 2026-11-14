package com.lazydog.english.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.lazydog.english.LazyDogApplication
import com.lazydog.english.core.data.TodayReport
import com.lazydog.english.domain.planning.DailyPlanner
import com.lazydog.english.domain.planning.DailyStep
import com.lazydog.english.domain.planning.PlannedStep
import com.lazydog.english.domain.progress.LearningActivity
import com.lazydog.english.domain.progress.Mood
import com.lazydog.english.domain.progress.mood
import java.time.LocalDate

/**
 * 今天的计划和它的进度。今天页和各步骤的收尾页看的是同一份，
 * 所以只在这里算一次——两处各算一遍，迟早一个说"下一步语法"、另一个说"今天做完了"。
 */
data class TodayPlanState(
    val today: String,
    val plan: List<PlannedStep>,
    val mood: Mood,
    val doneSteps: Set<String>,
    val skippedSteps: Set<String>,
    val report: TodayReport,
    val activity: LearningActivity,
    val dueVocab: Int,
    val dueGrammar: Int,
) {
    val allDone: Boolean get() = plan.isNotEmpty() && plan.all { it.step.id in doneSteps }

    val nextStep: PlannedStep? get() = DailyPlanner.nextStep(plan, doneSteps, skippedSteps)

    /**
     * 刚做完 [current] 之后该接哪一步。
     *
     * 把 [current] 当成已完成来算：收尾页出现的那一刻，"已完成"的标记可能还没写回来，
     * 不排除它的话按钮会先闪一下"下一步：单词"——也就是刚做完的这一步。
     */
    fun nextAfter(current: DailyStep): PlannedStep? =
        DailyPlanner.nextStep(plan, doneSteps + current.id, skippedSteps)
}

@Composable
fun rememberTodayPlanState(): TodayPlanState {
    val context = LocalContext.current
    val app = remember { context.applicationContext as LazyDogApplication }
    val prefs = app.userPreferences
    val today = remember { LocalDate.now().toString() }

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

    // 中断回来、或者今天已经做累了，今天就只排一步（§26、§25）。
    val mood = mood(daysAway = activity.daysAway, fatigue = report.fatigue)
    val plan = remember(dailyMinutes, dueVocab, dueGrammar, mood) {
        DailyPlanner.plan(dailyMinutes, dueVocabCount = dueVocab, dueGrammarCount = dueGrammar, mood = mood)
    }
    return TodayPlanState(
        today = today,
        plan = plan,
        mood = mood,
        doneSteps = doneSteps,
        skippedSteps = skippedSteps,
        report = report,
        activity = activity,
        dueVocab = dueVocab,
        dueGrammar = dueGrammar,
    )
}

/**
 * 一步做完之后的两个出口：接着做今天的下一步，或者先到这里。
 *
 * 今日学习应该是一条连续的流程（`UI_BRIEF.md` §6），不是每做完一步都被扔回首页再点一次「继续」。
 * 计划里没有下一步了（做完了、剩下的都跳过了、或者今天只排了这一步），就只留「先到这里」。
 */
@Composable
fun DailyStepFinishActions(
    current: DailyStep,
    onNextStep: (DailyStep) -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
    finishLabel: String = "先到这里",
) {
    val state = rememberTodayPlanState()
    val next = state.nextAfter(current)
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (next != null) {
            Button(
                onClick = { onNextStep(next.step) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp),
            ) {
                Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                Text(
                    text = "下一步：${next.step.title}",
                    modifier = Modifier.padding(start = 8.dp),
                    maxLines = 1,
                )
            }
            OutlinedButton(
                onClick = onFinish,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp),
            ) {
                Text(finishLabel)
            }
        } else {
            Button(
                onClick = onFinish,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp),
            ) {
                Text(finishLabel)
            }
        }
    }
}
